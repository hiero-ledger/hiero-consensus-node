// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.InputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.consensus.SyntheticSnapshot;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link TransactionHandlerDataCounter}.
 */
class TransactionHandlerDataCounterTest {

    private static final double TARGET_MAX_ROUNDS = 50.0;
    private static final long CAPACITY = 1000L;
    private WiringModel wiringModel;
    private Randotron random;
    private ComponentWiring<TestRoundHandler, Long> componentWiring;
    private TaskScheduler<Long> taskScheduler;

    @BeforeEach
    void setUp() {
        wiringModel = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                .enableHardBackpressure()
                .build();

        random = Randotron.create();

        final TransactionHandlerDataCounter dataCounter =
                new TransactionHandlerDataCounter(CAPACITY, TARGET_MAX_ROUNDS);

        taskScheduler = wiringModel
                .<Long>schedulerBuilder("test")
                .withType(TaskSchedulerType.SEQUENTIAL_THREAD)
                .withDataCounter(dataCounter)
                .withUnhandledTaskCapacity(CAPACITY)
                .build();
        componentWiring = new ComponentWiring<>(wiringModel, TestRoundHandler.class, taskScheduler);
        componentWiring.bind(round -> {});
    }

    @AfterEach
    void tearDown() {
        // make sure we don't leave any threads aliave in case we add some tests in the future
        if (wiringModel != null) {
            try {

                wiringModel.stop();
            } catch (IllegalStateException ignored) {
                // If the model was not started we wont fail
            }
        }
    }

    @ParameterizedTest
    @MethodSource("testRounds")
    @DisplayName("Test component with data counter and queue size tracking")
    void testComponentWithDataCounterAndQueue(TestRoundData testRound) {
        final InputWire<ConsensusRound> inputWire = componentWiring.getInputWire(TestRoundHandler::handleRound);

        final ConsensusRound round = createConsensusRoundWithTransactions(testRound.transactionCount);
        final long expectedDataCounterValue = testRound.expectedCounterValue;
        // Inject rounds and verify the data counter calculates correct values
        inputWire.put(round);
        assertEquals(
                expectedDataCounterValue,
                taskScheduler.getUnprocessedTaskCount(),
                "Data counter should return " + expectedDataCounterValue + " for round with "
                        + testRound.transactionCount + " transactions");
    }

    @Test
    @DisplayName("Test data counter accumulated value and queue size tracking")
    void testComponentWithDataCounterAndQueue() {

        final InputWire<ConsensusRound> inputWire = componentWiring.getInputWire(TestRoundHandler::handleRound);
        long expectedTotalValue = 0;
        for (final var testRound : testRounds()) {
            expectedTotalValue += testRound.expectedCounterValue;
            inputWire.put(createConsensusRoundWithTransactions(testRound.transactionCount));
        }
        // Verify handler received all rounds and calculated the total load correctly
        assertEquals(
                expectedTotalValue,
                taskScheduler.getUnprocessedTaskCount(),
                "UnprocessedTaskCount should report: " + expectedTotalValue);
    }
    /**
     * Creates a consensus round with the specified number of transactions.
     *
     * @param transactionCount the number of app transactions in the round
     * @return a new ConsensusRound
     */
    private ConsensusRound createConsensusRoundWithTransactions(final int transactionCount) {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setAppTransactionCount(transactionCount)
                .setSystemTransactionCount(0)
                .setConsensusTimestamp(random.nextInstant())
                .build();

        event.signalPrehandleCompletion();

        return new ConsensusRound(
                RandomRosterBuilder.create(random)
                        .withRealKeysEnabled(false)
                        .withSize(4)
                        .build(),
                List.of(event),
                EventWindow.getGenesisEventWindow(),
                SyntheticSnapshot.getGenesisSnapshot(),
                false,
                random.nextInstant());
    }

    /**
     * Test data for round injection.
     */
    private record TestRoundData(int transactionCount, long expectedCounterValue) {}

    /**
     * Test component interface for handling rounds.
     */
    @FunctionalInterface
    private interface TestRoundHandler {
        void handleRound(@NonNull ConsensusRound round);
    }

    // Create and inject rounds with different transaction counts
    public static List<TestRoundData> testRounds() {
        // minimumEffort = 1000 / 50 = 20
        return List.of(
                new TestRoundData(100, 100L), // 100 transactions > minimumEffort (20), uses 100
                new TestRoundData(10, 20L), // 10 transactions < minimumEffort (20), uses 20
                new TestRoundData(0, 20L), // 0 transactions, uses minimumEffort (20)
                new TestRoundData(75, 75L), // 75 transactions > minimumEffort (20), uses 75
                new TestRoundData(5, 20L)); // 5 transactions < minimumEffort (20), uses 20
    }
}
