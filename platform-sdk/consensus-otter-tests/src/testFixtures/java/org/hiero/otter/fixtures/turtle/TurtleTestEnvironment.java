// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.Validator;
import org.hiero.otter.fixtures.generator.TransactionGeneratorImpl;
import org.hiero.otter.fixtures.validator.ValidatorImpl;

/**
 * A test environment for the Turtle framework.
 *
 * <p>This class implements the {@link TestEnvironment} interface and provides methods to access the
 * network, time manager, etc. for tests running on the Turtle framework.
 */
public class TurtleTestEnvironment implements TestEnvironment {

    private static final Logger log = Loggers.getLogger(TurtleTestEnvironment.class);

    static final Duration GRANULARITY = Duration.ofMillis(10);
    static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(200);
    static final Duration STANDARD_DEVIATION_NETWORK_DELAY = Duration.ofMillis(10);

    private final TurtleNetwork network;
    private final TransactionGeneratorImpl generator;
    private final TurtleTimeManager timeManager;

    /**
     * Constructor for the {@link TurtleTestEnvironment} class.
     */
    public TurtleTestEnvironment() {
        final Randotron randotron = Randotron.create();

        try {
            ConstructableRegistry.getInstance()
                    .registerConstructable(
                            new ClassConstructorPair(TestMerkleStateRoot.class, TestMerkleStateRoot::new));
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }

        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);
        final Path rootOutputDirectory = Path.of("build", "turtle");
        network = new TurtleNetwork(randotron, time, rootOutputDirectory, AVERAGE_NETWORK_DELAY, STANDARD_DEVIATION_NETWORK_DELAY);

        generator = new TransactionGeneratorImpl(network);

        timeManager = new TurtleTimeManager(time, GRANULARITY);
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
}
