/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import vogar.commands.Command;
import vogar.commands.CommandFailedException;
import vogar.commands.Mkdir;
import vogar.monitor.HostMonitor;
import vogar.monitor.SocketHostMonitor;

/**
 * A Mode for running actions. Examples including running in a virtual machine
 * either on the host or a device or within a specific context such as within an
 * Activity.
 */
public abstract class Mode {

    private static final Pattern JAVA_SOURCE_PATTERN = Pattern.compile("\\/(\\w)+\\.java$");

    protected final Environment environment;

    public static class Options {
        public final Classpath buildClasspath;
        public final List<File> sourcepath;
        public final List<String> javacArgs;
        public final File javaHome;
        public final int firstMonitorPort;
        public final int timeoutSeconds;
        public final boolean useBootClasspath;
        public final Classpath classpath;
        public final boolean nativeOutput;

        Options(Classpath buildClasspath,
                List<File> sourcepath,
                List<String> javacArgs,
                File javaHome,
                int firstMonitorPort,
                int timeoutSeconds,
                boolean useBootClasspath,
                Classpath classpath,
                boolean nativeOutput) {
            this.buildClasspath = buildClasspath;
            this.sourcepath = sourcepath;
            this.javacArgs = javacArgs;
            this.javaHome = javaHome;
            this.firstMonitorPort = firstMonitorPort;
            this.timeoutSeconds = timeoutSeconds;
            this.useBootClasspath = useBootClasspath;
            this.classpath = classpath;
            this.nativeOutput = nativeOutput;
        }
    }

    final Options modeOptions;

    /**
     * User classes that need to be included in the classpath for both
     * compilation and execution. Also includes dependencies of all active
     * runners.
     */
    protected final Classpath classpath = new Classpath();

    protected Mode(Environment environment, Options modeOptions) {
        this.environment = environment;
        this.modeOptions = modeOptions;
        this.classpath.addAll(modeOptions.classpath);
    }

    public Environment getEnvironment() {
        return environment;
    }

    public Options getModeOptions() {
        return modeOptions;
    }

    /**
     * Returns a path for a Java tool such as java, javac, jar where
     * the Java home is used if present, otherwise assumes it will
     * come from the path.
     */
    String javaPath (String tool) {
        return (modeOptions.javaHome == null)
            ? tool
            : new File(new File(modeOptions.javaHome, "bin"), tool).getPath();
    }

    /**
     * Initializes the temporary directories and harness necessary to run
     * actions.
     */
    protected void prepare() {
        environment.prepare();
        classpath.addAll(vogarJar());
        installRunner();
    }

    /**
     * Returns the .jar file containing Vogar.
     */
    private File vogarJar() {
        URL jarUrl = Vogar.class.getResource("/vogar/Vogar.class");
        if (jarUrl == null) {
            // should we add an option for IDE users, to use a user-specified vogar.jar?
            throw new IllegalStateException("Vogar cannot find its own .jar");
        }

        /*
         * Parse a URI like jar:file:/Users/jessewilson/vogar/vogar.jar!/vogar/Vogar.class
         * to yield a .jar file like /Users/jessewilson/vogar/vogar.jar.
         */
        String url = jarUrl.toString();
        int bang = url.indexOf("!");
        String JAR_URI_PREFIX = "jar:file:";
        if (url.startsWith(JAR_URI_PREFIX) && bang != -1) {
            return new File(url.substring(JAR_URI_PREFIX.length(), bang));
        } else {
            throw new IllegalStateException("Vogar cannot find the .jar file in " + jarUrl);
        }
    }

    /**
     * Compiles classes for the given action and makes them ready for execution.
     *
     * @return null if the compilation succeeded, or an outcome describing the
     *      failure otherwise.
     */
    public Outcome buildAndInstall(Action action) {
        Console.getInstance().verbose("build " + action.getName());
        environment.prepareUserDir(action);

        try {
            File jar = compile(action);
            postCompile(action, jar);
        } catch (CommandFailedException e) {
            return new Outcome(action.getName(),
                    Result.COMPILE_FAILED, e.getOutputLines());
        } catch (IOException e) {
            return new Outcome(action.getName(), Result.ERROR, e);
        }
        return null;
    }

    /**
     * Returns the .jar file containing the action's compiled classes.
     *
     * @throws CommandFailedException if javac fails
     */
    private File compile(Action action) throws IOException {
        File classesDir = environment.file(action, "classes");
        new Mkdir().mkdirs(classesDir);
        createJarMetadataFiles(action, classesDir);

        Set<File> sourceFiles = new HashSet<File>();
        File javaFile = action.getJavaFile();
        Javac javac = new Javac(javaPath("javac"));
        if (javaFile != null) {
            if (!JAVA_SOURCE_PATTERN.matcher(javaFile.toString()).find()) {
                throw new CommandFailedException(Collections.<String>emptyList(),
                        Collections.singletonList("Cannot compile: " + javaFile));
            }
            sourceFiles.add(javaFile);
            Classpath sourceDirs = Classpath.of(action.getSourcePath());
            sourceDirs.addAll(modeOptions.sourcepath);
            javac.sourcepath(sourceDirs.getElements());
        }
        if (!sourceFiles.isEmpty()) {
            if (!modeOptions.buildClasspath.isEmpty()) {
                javac.bootClasspath(modeOptions.buildClasspath);
            }
            javac.classpath(classpath)
                    .destination(classesDir)
                    .extra(modeOptions.javacArgs)
                    .compile(sourceFiles);
        }

        File jar = environment.hostJar(action);
        new Command(javaPath("jar"), "cvfM", jar.getPath(),
                "-C", classesDir.getPath(), "./").execute();
        return jar;
    }

    /**
     * Writes files to {@code classesDir} to be included in the .jar file for
     * {@code action}.
     */
    protected void createJarMetadataFiles(Action action, File classesDir) throws IOException {
        OutputStream propertiesOut
                = new FileOutputStream(new File(classesDir, TestProperties.FILE));
        Properties properties = new Properties();
        fillInProperties(properties, action);
        properties.store(propertiesOut, "generated by " + Mode.class.getName());
        propertiesOut.close();
    }

    /**
     * Fill in properties for running in this mode
     */
    protected void fillInProperties(Properties properties, Action action) {
        properties.setProperty(TestProperties.TEST_CLASS_OR_PACKAGE, action.getTargetClass());
        properties.setProperty(TestProperties.QUALIFIED_NAME, action.getName());
        properties.setProperty(TestProperties.MONITOR_PORT, Integer.toString(modeOptions.firstMonitorPort));
        properties.setProperty(TestProperties.TIMEOUT, Integer.toString(modeOptions.timeoutSeconds));
    }

    /**
     * Hook method called after runner compilation.
     */
    protected void installRunner() {}

    /**
     * Hook method called after action compilation.
     */
    protected void postCompile(Action action, File jar) {}

    /**
     * Create the command that executes the action.
     *
     * @param monitorPort the port to accept connections on, or -1 for the
     *     default port.
     */
    protected abstract Command createActionCommand(Action action, int monitorPort);

    protected HostMonitor createHostMonitor(
            Action action, int monitorPort, HostMonitor.Handler handler) {
        return new SocketHostMonitor(monitorPort, handler);
    }

    /**
     * Deletes files and releases any resources required for the execution of
     * the given action.
     */
    public void cleanup(Action action) {
        environment.cleanup(action);
    }

    /**
     * Cleans up after all actions have completed.
     */
    void shutdown() {
        environment.shutdown();
    }

    public Classpath getClasspath() {
        return classpath;
    }
}
