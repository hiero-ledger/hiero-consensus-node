// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

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
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.Validator;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;
import org.hiero.otter.fixtures.validator.ValidatorImpl;

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
        final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        final Configuration loggerContextConfig = loggerContext.getConfiguration();

        if (loggerContextConfig.getAppender("InMemory") == null) {
            final InMemoryAppender inMemoryAppender = InMemoryAppender.createAppender("InMemory");
            inMemoryAppender.start();
            loggerContextConfig.addAppender(inMemoryAppender);

            final LoggerConfig rootLoggerConfig = loggerContextConfig.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
            rootLoggerConfig.addAppender(inMemoryAppender, null, null);
            rootLoggerConfig.setLevel(org.apache.logging.log4j.Level.ALL);

            final Layout<?> layout = PatternLayout.newBuilder()
                    .withPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%X] [%t] %-5level %logger{36} - %msg %n")
                    .withConfiguration(loggerContextConfig)
                    .build();

            final ConsoleAppender consoleAppender = ConsoleAppender.createDefaultAppenderForLayout(layout);
            rootLoggerConfig.addAppender(consoleAppender, Level.INFO, null);

            loggerContext.updateLoggers();
        }

        final Randotron randotron = Randotron.create();

        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);

        RuntimeObjectRegistry.initialize(time);

        try {
            final ConstructableRegistry registry = ConstructableRegistry.getInstance();
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

        timeManager = new TurtleTimeManager(time, GRANULARITY);

        network = new TurtleNetwork(randotron, timeManager, rootOutputDirectory);

        generator = new TurtleTransactionGenerator(network, randotron);

        timeManager.addTimeTickReceiver(network);
        timeManager.addTimeTickReceiver(generator);
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
    @NonNull
    public Validator validator() {
        log.warn("Validator is not implemented yet");
        return new ValidatorImpl();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() throws InterruptedException {
        generator.stop();
        network.destroy();
    }
}
