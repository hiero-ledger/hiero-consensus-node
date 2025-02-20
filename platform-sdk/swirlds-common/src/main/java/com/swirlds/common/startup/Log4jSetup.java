// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.startup;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.util.DefaultShutdownCallbackRegistry;
import org.apache.logging.log4j.spi.LoggerContextFactory;

/**
 * Utility methods for bootstrapping log4j.
 */
public final class Log4jSetup {
    public static final String LOG_CONTEXT_NAME = "swirlds-context";

    /**
     * Hidden constructor.
     */
    private Log4jSetup() {}

    /**
     * Start log4j.
     * <p>
     * This method is intended to be called from a separate thread.
     * <p>
     * If this method has already been called previously, it will immediately count down the latch and return
     *
     * @param configPath       the path to the log4j configuration file
     * @param log4jLoadedLatch the latch to count down when log4j has been loaded, or if loading failed
     */
    private static void startLog4j(@NonNull final Path configPath, @NonNull final CountDownLatch log4jLoadedLatch) {
        try {
            final LoggerContext context = (LoggerContext) LogManager.getContext(false);

            // If the context is already loaded, then do not load it again.
            if (context.getName().equals(LOG_CONTEXT_NAME)) {
                log4jLoadedLatch.countDown();
                return;
            }

            context.setConfigLocation(configPath.toUri());
            // set the name, so we know that the context has been loaded
            context.setName(LOG_CONTEXT_NAME);

            final Logger logger = LogManager.getLogger(Log4jSetup.class);

            if (Thread.getDefaultUncaughtExceptionHandler() == null) {
                Thread.setDefaultUncaughtExceptionHandler((final Thread thread, final Throwable e) ->
                        logger.error(EXCEPTION.getMarker(), "exception on thread {}", thread.getName(), e));
            }

            final LoggerContextFactory factory = LogManager.getFactory();
            if (factory instanceof final Log4jContextFactory contextFactory) {
                // Do not allow log4j to use its own shutdown hook.
                // Use our own shutdown hook to stop log4j.
                // This allows us to write a final log message before the logger is shut down.
                ((DefaultShutdownCallbackRegistry) contextFactory.getShutdownCallbackRegistry()).stop();
                Runtime.getRuntime()
                        .addShutdownHook(new ThreadConfiguration(getStaticThreadManager())
                                .setComponent("browser")
                                .setThreadName("shutdown-hook")
                                .setRunnable(() -> {
                                    logger.info(STARTUP.getMarker(), "JVM is shutting down.");
                                    LogManager.shutdown();
                                })
                                .build());
            }
        } catch (final Exception e) {
            LogManager.getLogger(Log4jSetup.class).fatal("Unable to load log context", e);
            System.err.println("FATAL Unable to load log context: " + e);
        }

        log4jLoadedLatch.countDown();
    }

    /**
     * Initialize the log4j configuration and logging subsystem if log4j config file is present in the path specified
     * <p>
     * This method spins up a new thread to initialize log4j
     *
     * @param configPath the path to the log4j configuration file. If null, or if the file does not exist, then
     *                   log4j will not be initialized
     * @return a latch that will be counted down when log4j has been loaded, or if loading failed
     */
    public static CountDownLatch startLoggingFramework(@Nullable final Path configPath) {
        final CountDownLatch log4jLoadedLatch = new CountDownLatch(1);

        if (configPath != null && Files.exists(configPath)) {
            new Thread(() -> startLog4j(configPath, log4jLoadedLatch)).start();
        } else {
            log4jLoadedLatch.countDown();
        }

        return log4jLoadedLatch;
    }
}
