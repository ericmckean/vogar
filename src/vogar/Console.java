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

import java.util.List;

/**
 * Controls, formats and emits output to the command line.
 */
public class Console {
    private static final Console INSTANCE = new Console();

    private boolean stream;
    private boolean color;
    private boolean verbose;
    private String indent;

    private String currentName;
    private CurrentLine currentLine = CurrentLine.NEW;
    private final StringBuilder bufferedOutput = new StringBuilder();

    private Console() {}

    public static Console getInstance() {
        return INSTANCE;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public void setIndent(String indent) {
        this.indent = indent;
    }

    public void setColor(boolean color) {
        this.color = color;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void verbose(String s) {
        newLine();
        System.out.print(s);
        System.out.flush();
        currentLine = CurrentLine.VERBOSE;
    }

    public void info(String s) {
        newLine();
        System.out.println(s);
    }

    public void info(String message, Throwable throwable) {
        newLine();
        System.out.println(message);
        throwable.printStackTrace(System.out);
    }

    public void action(String name) {
        newLine();
        System.out.print("Action " + name);
        System.out.flush();
        currentName = name;
        currentLine = CurrentLine.NAME;
    }

    /**
     * Prints the beginning of the named outcome.
     */
    public void outcome(String name) {
        // if the outcome and action names are the same, omit the outcome name
        if (name.equals(currentName)) {
            return;
        }

        currentName = name;
        newLine();
        System.out.print(indent + name);
        System.out.flush();
        currentLine = CurrentLine.NAME;
    }

    /**
     * Appends the action output immediately to the stream when streaming is on,
     * or to a buffer when streaming is off. Buffered output will be held and
     * printed only if the outcome is unsuccessful.
     */
    public void streamOutput(String output) {
        if (stream) {
            printOutput(output);
        } else {
            bufferedOutput.append(output);
        }
    }

    /**
     * Writes the action's outcome.
     */
    public void printResult(Result result, boolean ok) {
        if (ok) {
            String prefix = (currentLine == CurrentLine.NAME) ? " " : "\n" + indent;
            System.out.println(prefix + green("OK (" + result + ")"));

        } else {
            if (bufferedOutput.length() > 0) {
                printOutput(bufferedOutput.toString());
                bufferedOutput.delete(0, bufferedOutput.length());
            }

            newLine();
            System.out.println(indent + red("FAIL (" + result + ")"));
        }

        currentName = null;
        currentLine = CurrentLine.NEW;
    }

    public void summarizeFailures(List<String> failureNames) {
        System.out.println("Failure summary:");
        for (String failureName : failureNames) {
            System.out.println(red(failureName));
        }
    }

    /**
     * Prints the action output with appropriate indentation.
     */
    private void printOutput(String streamedOutput) {
        String[] lines = messageToLines(streamedOutput);

        if (currentLine != CurrentLine.STREAMED_OUTPUT) {
            newLine();
            System.out.print(indent);
            System.out.print(indent);
        }
        System.out.print(lines[0]);
        currentLine = CurrentLine.STREAMED_OUTPUT;

        for (int i = 1; i < lines.length; i++) {
            newLine();

            if (lines[i].length() > 0) {
                System.out.print(indent);
                System.out.print(indent);
                System.out.print(lines[i]);
                currentLine = CurrentLine.STREAMED_OUTPUT;
            }
        }
    }

    /**
     * Inserts a linebreak if necessary.
     */
    private void newLine() {
        if (currentLine == CurrentLine.NEW) {
            return;
        } else if (currentLine == CurrentLine.VERBOSE) {
            // --verbose means "leave all the verbose output on the screen".
            if (!verbose) {
                // Otherwise we overwrite verbose output whenever something new arrives.
                eraseCurrentLine();
                currentLine = CurrentLine.NEW;
                return;
            }
        }

        System.out.println();
        currentLine = CurrentLine.NEW;
    }

    /**
     * Status of a currently-in-progress line of output.
     */
    enum CurrentLine {

        /**
         * The line is blank.
         */
        NEW,

        /**
         * The line contains streamed application output. Additional streamed
         * output may be appended without additional line separators or
         * indentation.
         */
        STREAMED_OUTPUT,

        /**
         * The line contains the name of an action or outcome. The outcome's
         * result (such as "OK") can be appended without additional line
         * separators or indentation.
         */
        NAME,

        /**
         * The line contains verbose output, and may be overwritten.
         */
        VERBOSE,
    }

    /**
     * Returns an array containing the lines of the given text.
     */
    private String[] messageToLines(String message) {
        // pass Integer.MAX_VALUE so split doesn't trim trailing empty strings.
        return message.split("\r\n|\r|\n", Integer.MAX_VALUE);
    }

    private String green(String message) {
        return color ? ("\u001b[32;1m" + message + "\u001b[0m") : message;
    }

    private String red(String message) {
        return color ? ("\u001b[31;1m" + message + "\u001b[0m") : message;
    }

    private void eraseCurrentLine() {
        System.out.print(color ? "\u001b[2K\r" : "\n");
        System.out.flush();
    }
}