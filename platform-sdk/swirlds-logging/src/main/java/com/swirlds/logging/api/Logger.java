// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The logger interface. That is the interface that is used by the application to log messages.
 */
public interface Logger {

    /**
     * Log a message at the error level ({@link Level#ERROR}).
     *
     * @param message the message to log
     */
    default void error(final @NonNull String message) {
        log(Level.ERROR, message);
    }

    /**
     * Log a message (+ exception) at the error level ({@link Level#ERROR}).
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    default void error(final @NonNull String message, final @Nullable Throwable throwable) {
        log(Level.ERROR, message, throwable);
    }

    /**
     * Log a message at the error level ({@link Level#ERROR}). The message can contain placeholders ({@code {}}) that
     * will be replaced by the given arguments.
     *
     * @param message the message to log
     * @param args    the arguments to replace the placeholders with
     */
    default void error(final @NonNull String message, final @Nullable Object... args) {
        log(Level.ERROR, message, args);
    }

    /**
     * Log a message at the error level ({@link Level#ERROR}). The message can contain a placeholder ({@code {}}) that
     * will be replaced by the given argument.
     *
     * @param message the message to log
     * @param arg     the arguments to replace the placeholders with
     */
    default void error(final @NonNull String message, final @Nullable Object arg) {
        log(Level.ERROR, message, arg);
    }

    /**
     * Log a message at the error level ({@link Level#ERROR}). The message can contain placeholders ({@code {}}) that
     * will be replaced by the given arguments.
     *
     * @param message the message to log
     * @param arg1    the argument to replace the first placeholder with
     * @param arg2    the argument to replace the second placeholder with
     */
    default void error(final @NonNull String message, final @Nullable Object arg1, final @Nullable Object arg2) {
        log(Level.ERROR, message, arg1, arg2);
    }

    /**
     * Log a message (+ exception) at the error level ({@link Level#ERROR}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace the placeholders with
     */
    default void error(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object... args) {
        log(Level.ERROR, message, throwable, args);
    }

    /**
     * Log a message (+ exception) at the error level ({@link Level#ERROR}). The message can contain a placeholder
     * ({@code {}}) that will be replaced by the given argument.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     */
    default void error(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object arg1) {
        log(Level.ERROR, message, throwable, arg1);
    }

    /**
     * Log a message (+ exception) at the error level ({@link Level#ERROR}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     * @param arg2      the argument to replace the second placeholder with
     */
    default void error(
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1,
            final @Nullable Object arg2) {
        log(Level.ERROR, message, throwable, arg1, arg2);
    }

    /**
     * Log a message at the warn level ({@link Level#WARN}).
     *
     * @param message the message to log
     */
    default void warn(final @NonNull String message) {
        log(Level.WARN, message);
    }

    /**
     * Log a message (+ exception) at the warn level ({@link Level#WARN}).
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    default void warn(final @NonNull String message, final @Nullable Throwable throwable) {
        log(Level.WARN, message, throwable);
    }

    /**
     * Log a message at the warn level ({@link Level#WARN}). The message can contain placeholders ({@code {}}) that will
     * be replaced by the given arguments.
     *
     * @param message the message to log
     * @param args    the arguments to replace the placeholders with
     */
    default void warn(final @NonNull String message, final @Nullable Object... args) {
        log(Level.WARN, message, args);
    }

    /**
     * Log a message at the warn level ({@link Level#WARN}). The message can contain a placeholder ({@code {}}) that
     * will be replaced by the given argument.
     *
     * @param message the message to log
     * @param arg     the arguments to replace the placeholders with
     */
    default void warn(final @NonNull String message, final @Nullable Object arg) {
        log(Level.WARN, message, arg);
    }

    /**
     * Log a message at the warn level ({@link Level#WARN}). The message can contain placeholders ({@code {}}) that will
     * be replaced by the given arguments.
     *
     * @param message the message to log
     * @param arg1    the argument to replace the first placeholder with
     * @param arg2    the argument to replace the second placeholder with
     */
    default void warn(final @NonNull String message, final @Nullable Object arg1, final @Nullable Object arg2) {
        log(Level.WARN, message, arg1, arg2);
    }

    /**
     * Log a message (+ exception) at the warn level ({@link Level#WARN}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace the placeholders with
     */
    default void warn(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object... args) {
        log(Level.WARN, message, throwable, args);
    }

    /**
     * Log a message (+ exception) at the warn level ({@link Level#WARN}). The message can contain a placeholder
     * ({@code {}}) that will be replaced by the given argument.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     */
    default void warn(final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object arg1) {
        log(Level.WARN, message, throwable, arg1);
    }

    /**
     * Log a message (+ exception) at the warn level ({@link Level#WARN}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     * @param arg2      the argument to replace the second placeholder with
     */
    default void warn(
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1,
            final @Nullable Object arg2) {
        log(Level.WARN, message, throwable, arg1, arg2);
    }

    /**
     * Log a message at the info level ({@link Level#INFO}).
     *
     * @param message the message to log
     */
    default void info(final @NonNull String message) {
        log(Level.INFO, message);
    }

    /**
     * Log a message (+ exception) at the info level ({@link Level#INFO}).
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    default void info(final @NonNull String message, final @Nullable Throwable throwable) {
        log(Level.INFO, message, throwable);
    }

    /**
     * Log a message at the info level ({@link Level#INFO}). The message can contain placeholders ({@code {}}) that will
     * be replaced by the given arguments.
     *
     * @param message the message to log
     * @param args    the arguments to replace the placeholders with
     */
    default void info(final @NonNull String message, final @Nullable Object... args) {
        log(Level.INFO, message, args);
    }

    /**
     * Log a message at the info level ({@link Level#INFO}). The message can contain a placeholder ({@code {}}) that
     * will be replaced by the given argument.
     *
     * @param message the message to log
     * @param arg     the arguments to replace the placeholders with
     */
    default void info(final @NonNull String message, final @Nullable Object arg) {
        log(Level.INFO, message, arg);
    }

    /**
     * Log a message at the info level ({@link Level#INFO}). The message can contain placeholders ({@code {}}) that will
     * be replaced by the given arguments.
     *
     * @param message the message to log
     * @param arg1    the argument to replace the first placeholder with
     * @param arg2    the argument to replace the second placeholder with
     */
    default void info(final @NonNull String message, final @Nullable Object arg1, final @Nullable Object arg2) {
        log(Level.INFO, message, arg1, arg2);
    }

    /**
     * Log a message (+ exception) at the info level ({@link Level#INFO}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace the placeholders with
     */
    default void info(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object... args) {
        log(Level.INFO, message, throwable, args);
    }

    /**
     * Log a message (+ exception) at the info level ({@link Level#INFO}). The message can contain a placeholder
     * ({@code {}}) that will be replaced by the given argument.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     */
    default void info(final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object arg1) {
        log(Level.INFO, message, throwable, arg1);
    }

    /**
     * Log a message (+ exception) at the info level ({@link Level#INFO}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     * @param arg2      the argument to replace the second placeholder with
     */
    default void info(
            String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1,
            final @Nullable Object arg2) {
        log(Level.INFO, message, throwable, arg1, arg2);
    }

    /**
     * Log a message at the debug level ({@link Level#DEBUG}).
     *
     * @param message the message to log
     */
    default void debug(final @NonNull String message) {
        log(Level.DEBUG, message);
    }

    /**
     * Log a message (+ exception) at the debug level ({@link Level#DEBUG}).
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    default void debug(final @NonNull String message, final @Nullable Throwable throwable) {
        log(Level.DEBUG, message, throwable);
    }

    /**
     * Log a message at the debug level ({@link Level#DEBUG}). The message can contain placeholders ({@code {}}) that
     * will be replaced by the given arguments.
     *
     * @param message the message to log
     * @param args    the arguments to replace the placeholders with
     */
    default void debug(final @NonNull String message, final @Nullable Object... args) {
        log(Level.DEBUG, message, args);
    }

    /**
     * Log a message at the debug level ({@link Level#DEBUG}). The message can contain a placeholder ({@code {}}) that
     * will be replaced by the given argument.
     *
     * @param message the message to log
     * @param arg     the arguments to replace the placeholders with
     */
    default void debug(final @NonNull String message, final @Nullable Object arg) {
        log(Level.DEBUG, message, arg);
    }

    /**
     * Log a message at the debug level ({@link Level#DEBUG}). The message can contain placeholders ({@code {}}) that
     * will be replaced by the given arguments.
     *
     * @param message the message to log
     * @param arg1    the argument to replace the first placeholder with
     * @param arg2    the argument to replace the second placeholder with
     */
    default void debug(final @NonNull String message, final @Nullable Object arg1, final @Nullable Object arg2) {
        log(Level.DEBUG, message, arg1, arg2);
    }

    /**
     * Log a message (+ exception) at the debug level ({@link Level#DEBUG}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace the placeholders with
     */
    default void debug(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object... args) {
        log(Level.DEBUG, message, throwable, args);
    }

    /**
     * Log a message (+ exception) at the debug level ({@link Level#DEBUG}). The message can contain a placeholder
     * ({@code {}}) that will be replaced by the given argument.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     */
    default void debug(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object arg1) {
        log(Level.DEBUG, message, throwable, arg1);
    }

    /**
     * Log a message (+ exception) at the debug level ({@link Level#DEBUG}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     * @param arg2      the argument to replace the second placeholder with
     */
    default void debug(
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1,
            final @Nullable Object arg2) {
        log(Level.DEBUG, message, throwable, arg1, arg2);
    }

    /**
     * Log a message at the trace level ({@link Level#TRACE}).
     *
     * @param message the message to log
     */
    default void trace(final @NonNull String message) {
        log(Level.TRACE, message);
    }

    /**
     * Log a message (+ exception) at the trace level ({@link Level#TRACE}).
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    default void trace(final @NonNull String message, final @Nullable Throwable throwable) {
        log(Level.TRACE, message, throwable);
    }

    /**
     * Log a message at the trace level ({@link Level#TRACE}). The message can contain placeholders ({@code {}}) that
     * will be replaced by the given arguments.
     *
     * @param message the message to log
     * @param args    the arguments to replace the placeholders with
     */
    default void trace(final @NonNull String message, final @Nullable Object... args) {
        log(Level.TRACE, message, args);
    }

    /**
     * Log a message at the trace level ({@link Level#TRACE}). The message can contain a placeholder ({@code {}}) that
     * will be replaced by the given argument.
     *
     * @param message the message to log
     * @param arg     the arguments to replace the placeholders with
     */
    default void trace(final @NonNull String message, final @Nullable Object arg) {
        log(Level.TRACE, message, arg);
    }

    /**
     * Log a message at the trace level ({@link Level#TRACE}). The message can contain placeholders ({@code {}}) that
     * will be replaced by the given arguments.
     *
     * @param message the message to log
     * @param arg1    the argument to replace the first placeholder with
     * @param arg2    the argument to replace the second placeholder with
     */
    default void trace(final @NonNull String message, final @Nullable Object arg1, final @Nullable Object arg2) {
        log(Level.TRACE, message, arg1, arg2);
    }

    /**
     * Log a message (+ exception) at the trace level ({@link Level#TRACE}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace the placeholders with
     */
    default void trace(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object... args) {
        log(Level.TRACE, message, throwable, args);
    }

    /**
     * Log a message (+ exception) at the trace level ({@link Level#TRACE}). The message can contain a placeholder
     * ({@code {}}) that will be replaced by the given argument.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     */
    default void trace(
            final @NonNull String message, final @Nullable Throwable throwable, final @Nullable Object arg1) {
        log(Level.TRACE, message, throwable, arg1);
    }

    /**
     * Log a message (+ exception) at the trace level ({@link Level#TRACE}). The message can contain placeholders
     * ({@code {}}) that will be replaced by the given arguments.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     * @param arg2      the argument to replace the second placeholder with
     */
    default void trace(
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1,
            final @Nullable Object arg2) {
        log(Level.TRACE, message, throwable, arg1, arg2);
    }

    /**
     * Log a message at the given level.
     *
     * @param level   the level to log at
     * @param message the message to log
     */
    void log(final @NonNull Level level, final @NonNull String message);

    /**
     * Log a message (+ exception) at the given level.
     *
     * @param level     the level to log at
     * @param message   the message to log
     * @param throwable the exception to log
     */
    void log(final @NonNull Level level, final @NonNull String message, final @Nullable Throwable throwable);

    /**
     * Log a message at the given level. The message can contain placeholders ({@code {}}) that will be replaced by the
     * given arguments.
     *
     * @param level   the level to log at
     * @param message the message to log
     * @param args    the arguments to replace the placeholders with
     */
    void log(final @NonNull Level level, final @NonNull String message, final @Nullable Object... args);

    /**
     * Log a message at the given level. The message can contain a placeholder ({@code {}}) that will be replaced by the
     * given argument.
     *
     * @param level   the level to log at
     * @param message the message to log
     * @param arg     the argument to replace the placeholder with
     */
    void log(final @NonNull Level level, final @NonNull String message, final @Nullable Object arg);

    /**
     * Log a message at the given level. The message can contain placeholders ({@code {}}) that will be replaced by the
     * given arguments.
     *
     * @param level   the level to log at
     * @param message the message to log
     * @param arg1    the argument to replace the first placeholder with
     * @param arg2    the argument to replace the second placeholder with
     */
    void log(
            final @NonNull Level level,
            final @NonNull String message,
            final @Nullable Object arg1,
            final @Nullable Object arg2);

    /**
     * Log a message (+ exception) at the given level. The message can contain placeholders ({@code {}}) that will be
     * replaced by the given arguments.
     *
     * @param level     the level to log at
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace the placeholders with
     */
    void log(
            final @NonNull Level level,
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object... args);

    /**
     * Log a message (+ exception) at the given level. The message can contain a placeholder ({@code {}}) that will be
     * replaced by the given argument.
     *
     * @param level     the level to log at
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     */
    void log(
            final @NonNull Level level,
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1);

    /**
     * Log a message (+ exception) at the given level. The message can contain placeholders ({@code {}}) that will be
     * replaced by the given arguments.
     *
     * @param level     the level to log at
     * @param message   the message to log
     * @param throwable the exception to log
     * @param arg1      the argument to replace the first placeholder with
     * @param arg2      the argument to replace the second placeholder with
     */
    void log(
            final @NonNull Level level,
            final @NonNull String message,
            final @Nullable Throwable throwable,
            final @Nullable Object arg1,
            final @Nullable Object arg2);

    /**
     * Returns a clone of the current logger with the given marker.
     *
     * @param markerName the name of the marker to add
     * @return a clone of the current logger with the given marker
     */
    @NonNull
    Logger withMarker(final @NonNull String markerName);

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key   the key of the context parameter
     * @param value the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    Logger withContext(final @NonNull String key, final @Nullable String value);

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key    the key of the context parameter
     * @param values an array that defines the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    Logger withContext(final @NonNull String key, final @Nullable String... values);

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key   the key of the context parameter
     * @param value the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final int value) {
        return withContext(key, Integer.toString(value));
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key    the key of the context parameter
     * @param values an array that defines the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final @Nullable int... values) {
        if (values == null) {
            return withContext(key, (String) null);
        }
        final String[] stringValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            stringValues[i] = Integer.toString(values[i]);
        }
        return withContext(key, stringValues);
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key   the key of the context parameter
     * @param value the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final long value) {
        return withContext(key, Long.toString(value));
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key    the key of the context parameter
     * @param values an array that defines the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final @Nullable long... values) {
        if (values == null) {
            return withContext(key, (String) null);
        }
        final String[] stringValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            stringValues[i] = Long.toString(values[i]);
        }
        return withContext(key, stringValues);
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key   the key of the context parameter
     * @param value the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final double value) {
        return withContext(key, Double.toString(value));
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key    the key of the context parameter
     * @param values an array that defines the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final @Nullable double... values) {
        if (values == null) {
            return withContext(key, (String) null);
        }
        final String[] stringValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            stringValues[i] = Double.toString(values[i]);
        }
        return withContext(key, stringValues);
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key   the key of the context parameter
     * @param value the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final float value) {
        return withContext(key, Float.toString(value));
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key    the key of the context parameter
     * @param values an array that defines the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final @Nullable float... values) {
        if (values == null) {
            return withContext(key, (String) null);
        }
        final String[] stringValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            stringValues[i] = Float.toString(values[i]);
        }
        return withContext(key, stringValues);
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key   the key of the context parameter
     * @param value the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final boolean value) {
        return withContext(key, Boolean.toString(value));
    }

    /**
     * Returns a clone of the current logger with the given context parameter.
     *
     * @param key    the key of the context parameter
     * @param values an array that defines the value of the context parameter
     * @return a clone of the current logger with the given context
     */
    @NonNull
    default Logger withContext(final @NonNull String key, final @Nullable boolean... values) {
        if (values == null) {
            return withContext(key, (String) null);
        }
        final String[] stringValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            stringValues[i] = Boolean.toString(values[i]);
        }
        return withContext(key, stringValues);
    }

    /**
     * Returns true if the given level is enabled.
     *
     * @param level the level to check
     * @return true if the given level is enabled
     */
    boolean isEnabled(final @NonNull Level level);

    /**
     * Returns true if the {@link Level#TRACE} level is enabled.
     *
     * @return true if the {@link Level#TRACE} level is enabled
     */
    default boolean isTraceEnabled() {
        return isEnabled(Level.TRACE);
    }

    /**
     * Returns true if the {@link Level#DEBUG} level is enabled.
     *
     * @return true if the {@link Level#DEBUG} level is enabled
     */
    default boolean isDebugEnabled() {
        return isEnabled(Level.DEBUG);
    }

    /**
     * Returns true if the {@link Level#INFO} level is enabled.
     *
     * @return true if the {@link Level#INFO} level is enabled
     */
    default boolean isInfoEnabled() {
        return isEnabled(Level.INFO);
    }

    /**
     * Returns true if the {@link Level#WARN} level is enabled.
     *
     * @return true if the {@link Level#WARN} level is enabled
     */
    default boolean isWarnEnabled() {
        return isEnabled(Level.WARN);
    }

    /**
     * Returns true if the {@link Level#ERROR} level is enabled.
     *
     * @return true if the {@link Level#ERROR} level is enabled
     */
    default boolean isErrorEnabled() {
        return isEnabled(Level.ERROR);
    }

    /**
     * Returns the name of the logger.
     *
     * @return the name of the logger
     */
    @NonNull
    String getName();
}
