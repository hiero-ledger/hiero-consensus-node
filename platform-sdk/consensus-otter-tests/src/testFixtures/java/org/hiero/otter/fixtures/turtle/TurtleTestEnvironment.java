// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.MarkerFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;

/**
 * A test environment for the Turtle framework.
 *
 * <p>This class implements the {@link TestEnvironment} interface and provides methods to access the
 * network, time manager, etc. for tests running on the Turtle framework.
 */
public class TurtleTestEnvironment implements TestEnvironment {

    private static final Logger log = LogManager.getLogger(TurtleTestEnvironment.class);

    static final String APP_NAME = "otter";
    static final String SWIRLD_NAME = "123";

    static final Duration GRANULARITY = Duration.ofMillis(10);
    static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(200);
    static final Duration STANDARD_DEVIATION_NETWORK_DELAY = Duration.ofMillis(10);

    private final TurtleNetwork network;
    private final TurtleTransactionGenerator generator;
    private final TurtleTimeManager timeManager;

    /**
     * Constructor for the {@link TurtleTestEnvironment} class.
     */
    public TurtleTestEnvironment() {
        final Randotron randotron = Randotron.create();

        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);

        RuntimeObjectRegistry.reset();
        RuntimeObjectRegistry.initialize(time);

        try {
            final ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.reset();
            registry.registerConstructables("");
            registerMerkleStateRootClassIds();
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }

        final Path rootOutputDirectory = Path.of("build", "turtle");
        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
        } catch (IOException ex) {
            log.warn("Failed to delete directory: {}", rootOutputDirectory, ex);
        }
        initLogging();

        timeManager = new TurtleTimeManager(time, GRANULARITY);

        network = new TurtleNetwork(randotron, timeManager, rootOutputDirectory);

        generator = new TurtleTransactionGenerator(network, randotron);

        timeManager.addTimeTickReceiver(network);
        timeManager.addTimeTickReceiver(generator);
    }

    private static void initLogging() {
        final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        final Configuration loggerContextConfig = loggerContext.getConfiguration();

        if (loggerContextConfig.getAppender("InMemory") == null) {
            final InMemoryAppender inMemoryAppender = InMemoryAppender.createAppender("InMemory");
            inMemoryAppender.start();
            loggerContextConfig.addAppender(inMemoryAppender);

            final LoggerConfig rootLoggerConfig = loggerContextConfig.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
            rootLoggerConfig.addAppender(inMemoryAppender, null, null);
            rootLoggerConfig.setLevel(Level.ALL);

            final PatternLayout layout = PatternLayout.newBuilder()
                    .withPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%X] [%t] [%marker] %-5level %logger{36} - %msg %n")
                    .withConfiguration(loggerContextConfig)
                    .build();

            final ConsoleAppender consoleAppender = ConsoleAppender.newBuilder()
                    .setName("ConsoleMarker")
                    .setLayout(layout)
                    .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                    .build();

            final MarkerFilter noStateHashFilter =
                    MarkerFilter.createFilter(STATE_HASH.name(), Result.DENY, Result.NEUTRAL);
            consoleAppender.addFilter(noStateHashFilter);

            consoleAppender.start();
            rootLoggerConfig.addAppender(consoleAppender, Level.INFO, null);

            final FileAppender fileAppender = FileAppender.newBuilder()
                    .setName("FileLogger")
                    .setLayout(layout)
                    .withFileName("build/turtle/otter.log")
                    .withAppend(true)
                    .build();
            fileAppender.addFilter(noStateHashFilter);
            fileAppender.start();
            rootLoggerConfig.addAppender(fileAppender, Level.DEBUG, null);

            final FileAppender stateHashFileAppender = FileAppender.newBuilder()
                    .setName("StateHashFileLogger")
                    .setLayout(layout)
                    .withFileName("build/turtle/otter-state-hash.log")
                    .withAppend(true)
                    .build();

            // Accept only logs with marker STATE_HASH
            final MarkerFilter onlyStateHashFilter =
                    MarkerFilter.createFilter(STATE_HASH.name(), Result.ACCEPT, Result.DENY);
            stateHashFileAppender.addFilter(onlyStateHashFilter);

            stateHashFileAppender.start();
            rootLoggerConfig.addAppender(stateHashFileAppender, Level.DEBUG, null);

            loggerContext.updateLoggers();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network network() {
        return network;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TransactionGenerator generator() {
        return generator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() throws InterruptedException {
        generator.stop();
        network.destroy();
        ConstructableRegistry.getInstance().reset();
        RuntimeObjectRegistry.reset();
    }
}
