// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * This class is responsible for printing stack traces of exceptions to a specified Appendable object. It includes
 * functionality to avoid printing circular references and to handle cases where certain parts of the trace have already
 * been printed. It also includes emergency logging for null references.
 */
public class StackTracePrinter {

    private static final int MAX_STACK_TRACE_DEPTH = -1;
    public static final String LINE_SEPARATOR = System.lineSeparator();

    /**
     * Prints the stack trace of a throwable to a provided Appendable writer. Avoids printing circular references and
     * handles already printed traces.
     *
     * @param writer         The Appendable object to write the stack trace to.
     * @param throwable      The Throwable object whose stack trace is to be printed.
     */
    public static void print(final @NonNull StringBuilder writer, final @Nullable Throwable throwable)
            throws IOException {
        final Set<Throwable> alreadyPrinted = Collections.newSetFromMap(new IdentityHashMap<>());
        alreadyPrinted.add(throwable);
        Throwable currentThrowable = throwable;
        StackTraceElement[] enclosingTrace = new StackTraceElement[0];
        while (currentThrowable != null) {
            writer.append(currentThrowable.getClass().getName());
            writer.append(": ");
            writer.append(currentThrowable.getMessage());
            writer.append(LINE_SEPARATOR);

            final StackTraceElement[] stackTrace = currentThrowable.getStackTrace();
            int m = stackTrace.length - 1;
            int n = enclosingTrace.length - 1;
            while (m >= 0 && n >= 0 && stackTrace[m].equals(enclosingTrace[n])) {
                m--;
                n--;
            }
            if (MAX_STACK_TRACE_DEPTH >= 0) {
                m = Math.min(m, MAX_STACK_TRACE_DEPTH);
            }
            final int skippedFrames = stackTrace.length - 1 - m;
            for (int i = 0; i <= m; i++) {
                final StackTraceElement stackTraceElement = stackTrace[i];
                final String moduleName = stackTraceElement.getModuleName();
                final String moduleVersion = stackTraceElement.getModuleVersion();
                final String fileName = stackTraceElement.getFileName();
                writer.append("\tat ");
                if (moduleName != null) {
                    writer.append(moduleName);
                    if (moduleVersion != null) {
                        writer.append("@");
                        writer.append(moduleVersion);
                    }
                    writer.append("/");
                }
                writer.append(stackTraceElement.getClassName());
                writer.append(".");
                writer.append(stackTraceElement.getMethodName());
                if (fileName != null) {
                    writer.append("(");
                    writer.append(fileName);
                    writer.append(":");
                    writer.append(stackTraceElement.getLineNumber());
                    writer.append(")");
                } else {
                    writer.append("(Unknown Source)");
                }

                writer.append(LINE_SEPARATOR);
            }
            if (skippedFrames != 0) {
                writer.append("\t... ");
                writer.append(skippedFrames);
                writer.append(" more");
                writer.append(LINE_SEPARATOR);
            }

            final Throwable cause = currentThrowable.getCause();
            enclosingTrace = stackTrace;
            if (cause != null) {
                writer.append("Caused by: "); // Separator for cause
                if (!alreadyPrinted.add(cause)) {
                    writer.append("[CIRCULAR REFERENCE: ").append(cause).append("]");
                    currentThrowable = null;
                } else {
                    currentThrowable = cause;
                }
            } else {
                currentThrowable = null;
            }
        }
    }
}
