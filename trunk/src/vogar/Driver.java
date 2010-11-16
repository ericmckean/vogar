/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vogar;

import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;
import vogar.commands.Command;
import vogar.commands.Mkdir;
import vogar.monitor.HostMonitor;
import vogar.target.CaliperRunner;
import vogar.util.Threads;
import vogar.util.TimeUtilities;

/**
 * Compiles, installs, runs and reports on actions.
 */
public final class Driver {

    private static final int FOREVER = 60 * 60 * 24 * 28; // four weeks

    /**
     * Assign each runner thread a unique ID. Necessary so threads don't
     * conflict when selecting a monitor port.
     */
    private final ThreadLocal<Integer> runnerThreadId = new ThreadLocal<Integer>() {
        private int next = 0;
        @Override protected synchronized Integer initialValue() {
            return next++;
        }
    };

    private final Timer actionTimeoutTimer = new Timer("action timeout", true);

    @Inject @Named("localTemp") File localTemp;
    @Inject ExpectationStore expectationStore;
    @Inject Mode mode;
    @Inject XmlReportPrinter reportPrinter;
    @Inject @Named("firstMonitorPort") int firstMonitorPort;
    @Inject @Named("smallTimeoutSeconds") int smallTimeoutSeconds;
    @Inject @Named("largeTimeoutSeconds") int largeTimeoutSeconds;
    @Inject JarSuggestions jarSuggestions;
    @Inject ClassFileIndex classFileIndex;
    @Inject @Named("numRunners") int numRunnerThreads;
    @Inject @Named("benchmark") boolean benchmark;
    @Inject OutcomeStore outcomeStore;

    private int successes = 0;
    private int failures = 0;
    private int skipped = 0;

    private List<AnnotatedOutcome> annotatedOutcomes = Lists.newArrayList();
    private final Map<String, Action> actions = Collections.synchronizedMap(
            new LinkedHashMap<String, Action>());
    private final Map<String, Outcome> outcomes = Collections.synchronizedMap(
            new LinkedHashMap<String, Outcome>());
    private boolean recordResults = true;

    /**
     * Builds and executes the actions in the given files.
     */
    public boolean buildAndRun(Collection<File> files, Collection<String> classes) {
        if (!actions.isEmpty()) {
            throw new IllegalStateException("Drivers are not reusable");
        }

        new Mkdir().mkdirs(localTemp);

        filesToActions(files);
        classesToActions(classes);

        if (actions.isEmpty()) {
            Console.getInstance().info("Nothing to do.");
            return false;
        }

        Console.getInstance().info("Actions: " + actions.size());
        final long t0 = System.currentTimeMillis();

        // mode.prepare before mode.buildAndInstall to ensure the runner is
        // built. packaging of activity APK files needs the runner along with
        // the action-specific files.
        mode.prepare();

        // build and install actions in a background thread. Using lots of
        // threads helps for packages that contain many unsupported actions
        final BlockingQueue<Action> readyToRun = new ArrayBlockingQueue<Action>(4);

        ExecutorService builders = Threads.threadPerCpuExecutor("builder");

        int totalToRun = 0;
        for (final Action action : actions.values()) {
            final String name = action.getName();
            if (outcomes.containsKey(name)) {
                addEarlyResult(outcomes.get(name));
                continue;
            } else if (expectationStore.get(name).getResult() == Result.UNSUPPORTED) {
                addEarlyResult(new Outcome(name, Result.UNSUPPORTED,
                        "Unsupported according to expectations file"));
                continue;
            }

            final int runIndex = totalToRun++;
            builders.execute(new Runnable() {
                public void run() {
                    try {
                        Console.getInstance().verbose("installing action " + runIndex + "; "
                                + readyToRun.size() + " are runnable");
                        Outcome outcome = mode.buildAndInstall(action);
                        if (outcome != null) {
                            outcomes.put(name, outcome);
                        }

                        readyToRun.put(action);
                        Console.getInstance().verbose("installed action " + runIndex + "; "
                                + readyToRun.size() + " are runnable");
                    } catch (Throwable e) {
                        Console.getInstance().info("unexpected failure!", e);
                    }
                }
            });
        }
        builders.shutdown();

        Console.getInstance().verbose(numRunnerThreads > 1
                ? ("running actions in parallel (" + numRunnerThreads + " threads)")
                : ("running actions in serial"));

        ExecutorService runners = Threads.fixedThreadsExecutor("runner", numRunnerThreads);

        final AtomicBoolean prematurelyExhaustedInput = new AtomicBoolean();
        for (int i = 0; i < totalToRun; i++) {
            runners.execute(new ActionRunner(prematurelyExhaustedInput, i, readyToRun));
        }
        runners.shutdown();
        try {
            runners.awaitTermination(FOREVER, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            recordOutcome(new Outcome("vogar.Vogar", Result.ERROR, e));
        }

        if (prematurelyExhaustedInput.get()) {
            recordOutcome(new Outcome("vogar.Vogar", Result.ERROR,
                    "Expected " + actions.size() + " actions but found fewer."));
        }

        if (reportPrinter.isReady()) {
            Console.getInstance().info("Printing XML Reports... ");
            int numFiles = reportPrinter.generateReports(outcomes.values());
            Console.getInstance().info(numFiles + " XML files written.");
        }

        mode.shutdown();
        final long t1 = System.currentTimeMillis();

        Console.getInstance().summarizeOutcomes(annotatedOutcomes);

        List<String> jarStringList = jarSuggestions.getStringList();
        if (!jarStringList.isEmpty()) {
            Console.getInstance().warn(
                    "consider adding the following to the classpath:",
                    jarStringList);
        }

        if (failures > 0 || skipped > 0) {
            Console.getInstance().info(String.format(
                    "Outcomes: %s. Passed: %d, Failed: %d, Skipped: %d. Took %s.",
                    (successes + failures + skipped), successes, failures, skipped,
                    TimeUtilities.msToString(t1 - t0)));
        } else {
            Console.getInstance().info(String.format("Outcomes: %s. All successful. Took %s.",
                    successes, TimeUtilities.msToString(t1 - t0)));
        }
        return failures == 0;
    }

    private void classesToActions(Collection<String> classNames) {
        for (String className : classNames) {
            Action action = new Action(className, className, null, null, null);
            actions.put(action.getName(), action);
        }
    }

    private void filesToActions(Collection<File> files) {
        for (File file : files) {
            new ActionFinder(actions, outcomes).findActions(file);
        }
    }

    private synchronized void addEarlyResult(Outcome earlyFailure) {
        if (earlyFailure.getResult() == Result.UNSUPPORTED) {
            Console.getInstance().verbose("skipped " + earlyFailure.getName());
            skipped++;

        } else {
            for (String line : earlyFailure.getOutputLines()) {
                Console.getInstance().streamOutput(earlyFailure.getName(), line + "\n");
            }
            recordOutcome(earlyFailure);
        }
    }

    private synchronized void recordOutcome(Outcome outcome) {
        outcomes.put(outcome.getName(), outcome);
        Expectation expectation = expectationStore.get(outcome);
        ResultValue resultValue = outcome.getResultValue(expectation);

        if (resultValue == ResultValue.OK) {
            successes++;
        } else if (resultValue == ResultValue.FAIL) {
            failures++;
        } else { // ResultValue.IGNORE
            skipped++;
        }

        Result result = outcome.getResult();
        Console.getInstance().outcome(outcome.getName());
        Console.getInstance().printResult(outcome.getName(), result, resultValue, expectation);

        AnnotatedOutcome annotatedOutcome = outcomeStore.read(outcome);
        if (recordResults) {
            outcomeStore.write(outcome, annotatedOutcome.outcomeChanged());
        }

        annotatedOutcomes.add(annotatedOutcome);

        JarSuggestions singleOutcomeJarSuggestions = new JarSuggestions();
        singleOutcomeJarSuggestions.addSuggestionsFromOutcome(outcome, classFileIndex,
                mode.getClasspath());
        List<String> jarStringList = singleOutcomeJarSuggestions.getStringList();
        if (!jarStringList.isEmpty()) {
            Console.getInstance().warn(
                    "may have failed because some of these jars are missing from the classpath:",
                    jarStringList);
        }
        jarSuggestions.addSuggestions(singleOutcomeJarSuggestions);
    }

    /**
     * Runs a single action and reports the result.
     */
    private class ActionRunner implements Runnable, HostMonitor.Handler {

        /**
         * All action runners share this atomic boolean. Whenever any of them
         * waits for five minutes without retrieving an executable action, we
         * use this to signal that they should all quit.
         */
        private final AtomicBoolean prematurelyExhaustedInput;
        private final int count;
        private final BlockingQueue<Action> readyToRun;
        private volatile Date killTime;
        private String lastStartedOutcome;
        private String lastFinishedOutcome;

        public ActionRunner(AtomicBoolean prematurelyExhaustedInput, int count,
                BlockingQueue<Action> readyToRun) {
            this.prematurelyExhaustedInput = prematurelyExhaustedInput;
            this.count = count;
            this.readyToRun = readyToRun;
        }

        public int monitorPort(int defaultValue) {
            return numRunnerThreads == 1
                    ? defaultValue
                    : firstMonitorPort + (runnerThreadId.get() % numRunnerThreads);
        }

        public void run() {
            if (prematurelyExhaustedInput.get()) {
                return;
            }

            Console.getInstance().verbose("executing action " + count + "; "
                    + readyToRun.size() + " are ready to run");

            // if it takes 5 minutes for build and install, something is broken
            Action action;
            try {
                action = readyToRun.poll(5 * 60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                recordOutcome(new Outcome("vogar.Vogar", Result.ERROR, e));
                return;
            }

            if (action == null) {
                prematurelyExhaustedInput.set(true); // short-circuit subsequent runners
                return;
            }

            String threadName = Thread.currentThread().getName();
            Thread.currentThread().setName("runner-" + action.getName());
            try {
                execute(action);
                mode.cleanup(action);
            } finally {
                Thread.currentThread().setName(threadName);
            }
        }

        /**
         * Executes a single action and then prints the result.
         */
        private void execute(final Action action) {
            String actionName = action.getName();
            Console.getInstance().action(actionName);
            Expectation expectation = expectationStore.get(actionName);
            int timeoutSeconds = expectation.getTags().contains("large")
                    ? largeTimeoutSeconds
                    : smallTimeoutSeconds;

            Outcome earlyFailure = outcomes.get(actionName);
            if (earlyFailure != null) {
                addEarlyResult(earlyFailure);
                return;
            }

            for (int i = 0; true; i++) {
                /*
                 * If the target process failed midway through a set of
                 * outcomes, that's okay. We pickup right after the first
                 * outcome that wasn't completed.
                 */
                String skipPast = lastStartedOutcome;
                lastStartedOutcome = null;

                if (skipPast == null && i != 0 || actionName.equals(skipPast)) {
                    break;
                }

                Command command = mode.createActionCommand(action, skipPast, monitorPort(-1));
                try {
                    command.start();

                    if (timeoutSeconds != 0) {
                        resetKillTime(timeoutSeconds);
                        scheduleTaskKiller(command, action, timeoutSeconds);
                    }

                    HostMonitor hostMonitor = new HostMonitor(this);
                    boolean completedNormally = mode.useSocketMonitor()
                            ? hostMonitor.attach(monitorPort(firstMonitorPort))
                            : hostMonitor.followStream(command.getInputStream());

                    if (completedNormally) {
                        return;
                    }

                    if (lastStartedOutcome == null) {
                        addEarlyResult(new Outcome(actionName, Result.ERROR,
                            "Target process did not complete normally: " + command));
                    } else if (!lastStartedOutcome.equals(lastFinishedOutcome)) {
                        addEarlyResult(new Outcome(lastStartedOutcome, Result.ERROR,
                            "Target process did not complete normally: " + command));
                    }
                } catch (IOException e) {
                    // if the monitor breaks, assume the worst and don't retry
                    addEarlyResult(new Outcome(actionName, Result.ERROR, e));
                    return;
                } finally {
                    command.destroy();
                }
            }
        }

        private void scheduleTaskKiller(final Command command, final Action action,
                final int timeoutSeconds) {
            actionTimeoutTimer.schedule(new TimerTask() {
                @Override public void run() {
                    // if the kill time has been pushed back, reschedule
                    if (System.currentTimeMillis() < killTime.getTime()) {
                        scheduleTaskKiller(command, action, timeoutSeconds);
                        return;
                    }
                    Console.getInstance().verbose("killing " + action + " because it timed out "
                            + "after " + timeoutSeconds + " seconds. Last started outcome is "
                            + lastStartedOutcome);
                    command.destroy();
                }
            }, killTime);
        }

        /**
         * Sets the time at which we'll kill a task that starts right now.
         */
        private void resetKillTime(int timeoutForTest) {
            /*
             * Give the target process an extra full timeout to self-timeout and
             * report the error. This way, when a JUnit test has one slow
             * method, we still get to see the offending stack trace.
             */
            long delay = TimeUnit.SECONDS.toMillis(timeoutForTest * 2);
            this.killTime = new Date(System.currentTimeMillis() + delay);
        }

        @Override public void start(String outcomeName, String runnerClass) {
            lastStartedOutcome = outcomeName;
            // TODO add to Outcome knowledge about what class was used to run it
            if (CaliperRunner.class.getName().equals(runnerClass)) {
                if (!benchmark) {
                    throw new RuntimeException("you must use --benchmark when running Caliper "
                            + "benchmarks.");
                }
                Console.getInstance().verbose("running " + outcomeName + " with unlimited timeout");
                resetKillTime(FOREVER);
                recordResults = false;
            } else {
                recordResults = true;
            }
        }

        @Override public void output(String outcomeName, String output) {
            Console.getInstance().outcome(outcomeName);
            Console.getInstance().streamOutput(outcomeName, output);
        }

        @Override public void finish(Outcome outcome) {
            lastFinishedOutcome = outcome.getName();
            // TODO: support flexible timeouts for JUnit tests
            resetKillTime(smallTimeoutSeconds);
            recordOutcome(outcome);
        }

        @Override public void print(String string) {
            Console.getInstance().streamOutput(string);
        }
    }
}
