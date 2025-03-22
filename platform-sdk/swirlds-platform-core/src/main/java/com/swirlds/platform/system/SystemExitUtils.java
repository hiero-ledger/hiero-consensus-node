// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.logging.legacy.payload.FatalErrorPayload;
import com.swirlds.logging.legacy.payload.SystemExitPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.utility.StackTrace;

/**
 *  Utility methods for shutting down the JVM.
 */
public final class SystemExitUtils {
    private static final Logger logger = LogManager.getLogger(SystemExitUtils.class);

    private SystemExitUtils() {}

    /**
     * Exits the system
     *
     * @param suppliedExitCode the reason for the exit
     * @param message          a message to log, if not null
     * @param haltRuntime      whether to halt the java runtime or not
     */
    public static void exitSystem(
            @Nullable final SystemExitCode suppliedExitCode,
            @Nullable final String message,
            final boolean haltRuntime) {
        // this will warn that the exit code will never be null, but we should probably keep it as a fail safe
        final SystemExitCode exitCode = suppliedExitCode == null ? SystemExitCode.NO_EXIT_CODE : suppliedExitCode;

        final StackTrace stackTrace = StackTrace.getStackTrace();
        final String exitMessage =
                "System exit requested (" + exitCode + ")" + (message == null ? "" : "\nmessage: " + message)
                        + "\nthread requesting exit: "
                        + Thread.currentThread().getName() + "\n"
                        + stackTrace;
        logger.info(STARTUP.getMarker(), exitMessage);

        if (exitCode.isError()) {
            logger.error(EXCEPTION.getMarker(), new SystemExitPayload(exitCode.name(), exitCode.getExitCode()));
        } else {
            logger.info(STARTUP.getMarker(), new SystemExitPayload(exitCode.name(), exitCode.getExitCode()));
        }
        System.out.println("Exiting System (" + exitCode.name() + ")");
        System.exit(exitCode.getExitCode());
        if (haltRuntime) {
            Runtime.getRuntime().halt(exitCode.getExitCode());
        }
    }

    /**
     * Same as {@link #exitSystem(SystemExitCode, String, boolean)}, but with haltRuntime set to false.
     *
     * @param exitCode the reason for the exit
     * @param message  a message to log, if not null
     */
    public static void exitSystem(@NonNull final SystemExitCode exitCode, @Nullable final String message) {
        exitSystem(exitCode, message, false);
    }

    /**
     * Same as {@link #exitSystem(SystemExitCode, String, boolean)}, but with haltRuntime set to false and no message.
     *
     * @param exitCode the reason for the exit
     */
    public static void exitSystem(@NonNull final SystemExitCode exitCode) {
        exitSystem(exitCode, null, false);
    }

    /**
     * Shutdown the JVM as the result of a fatal error.
     */
    public static void handleFatalError(
            @Nullable final String msg, @Nullable final Throwable throwable, @NonNull final SystemExitCode exitCode) {
        logger.fatal(
                EXCEPTION.getMarker(),
                "{}\nCaller stack trace:\n{}\nThrowable provided:",
                new FatalErrorPayload("Fatal error, node will shut down. Reason: " + msg),
                StackTrace.getStackTrace().toString(),
                throwable);

        SystemExitUtils.exitSystem(exitCode, msg);
    }
}
