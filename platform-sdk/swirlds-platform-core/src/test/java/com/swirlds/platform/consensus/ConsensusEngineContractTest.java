// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterFactory;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.Test;

/**
 * Tests that validate that the consensus engine is fulfilling its event contract:
 * <ul>
 *     <li>Each event that reaches consensus must have been previously returned as a pre-consensus event</li>
 *     <li>Each pre-consensus event must eventually reach consensus or become stale (the stale part will be implemented soon)</li>
 * </ul>
 */
public class ConsensusEngineContractTest {
    private static final int NUMBER_OF_EVENTS_PER_TEST = 10_000;
    private static final PlatformContext CONTEXT =
            TestPlatformContextBuilder.create().build();

    /**
     * Tests that the consensus engine can restart from a snapshot and still fulfill its event contract.
     */
    @Test
    void restartTest() {
        // parameters
        final int minNodes = 2;
        final int maxNodes = 15;

        // setup
        final Randotron random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(random.nextInt(minNodes, maxNodes))
                .build();
        final List<PlatformEvent> generatedEvents = generateEvents(random, roster);

        // start from genesis, validate the output
        final TestIntake genesisIntake = new TestIntake(CONTEXT, roster);
        addToIntake(generatedEvents, random, genesisIntake);
        validateOutputContract(genesisIntake.getOutput());

        // get a snapshot from the first run
        final ConsensusSnapshot snapshot = getMiddleSnapshot(genesisIntake);

        // load the snapshot into a new intake and validate that the output is consistent
        final TestIntake restartIntake = new TestIntake(CONTEXT, roster);
        restartIntake.loadSnapshot(snapshot);
        addToIntake(generatedEvents, random, restartIntake);

        assertEquals(
                genesisIntake.getOutput().getLastConsensusRound().getSnapshot(),
                restartIntake.getOutput().getLastConsensusRound().getSnapshot(),
                "Both consensus instances should have reached same last consensus round");
        validateOutputContract(restartIntake.getOutput());
    }

    /**
     * Tests that the consensus engine can handle a big change in the roster while still fulfilling its event contract.
     * <p>
     * This test usually triggers a consensus round to returned at the same time as the last judge is found. This is an
     * edge case that will probably be almost impossible to trigger in production.
     */
    @Test
    void bigRosterChangeTest() {
        // parameters
        final int numNodes = 15;

        // setup
        final Randotron random = Randotron.create();
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(numNodes).build();
        final List<PlatformEvent> generatedEvents = generateEvents(random, roster);

        // first part
        final TestIntake genesisIntake = new TestIntake(CONTEXT, roster);
        addToIntake(generatedEvents, random, genesisIntake);

        validateOutputContract(genesisIntake.getOutput());

        // change roster
        final Roster modifiedRoster = allWeightToOneNode(roster);

        // second part
        final ConsensusSnapshot snapshot = getMiddleSnapshot(genesisIntake);
        final TestIntake restartIntake = new TestIntake(CONTEXT, modifiedRoster);
        restartIntake.loadSnapshot(snapshot);
        addToIntake(generatedEvents, random, restartIntake);
        validateOutputContract(restartIntake.getOutput());
    }

    /**
     * This method modifies the original roster so that all weight is assigned to the first node, and all other nodes
     * have a weight of 0.
     *
     * @param originalRoster the original roster
     * @return a modified roster with all weight assigned to the first node
     */
    @NonNull
    private static Roster allWeightToOneNode(@NonNull final Roster originalRoster) {
        final List<RosterEntry> modifiedEntries = new ArrayList<>();
        modifiedEntries.add(originalRoster
                .rosterEntries()
                .getFirst()
                .copyBuilder()
                .weight(1)
                .build());
        for (int i = 1; i < originalRoster.rosterEntries().size(); i++) {
            modifiedEntries.add(originalRoster
                    .rosterEntries()
                    .get(i)
                    .copyBuilder()
                    .weight(0)
                    .build());
        }
        return originalRoster.copyBuilder().rosterEntries(modifiedEntries).build();
    }

    /**
     * Gets the middle snapshot from the given intake.
     *
     * @param intake the test intake
     * @return the middle snapshot
     */
    @NonNull
    private static ConsensusSnapshot getMiddleSnapshot(@NonNull final TestIntake intake) {
        return intake.getConsensusRounds()
                .get(intake.getConsensusRounds().size() / 2)
                .getSnapshot();
    }

    /**
     * Generates a list of events for the given roster.
     *
     * @param random  the random number generator
     * @param roster  the roster to use to generate events
     * @return a list of generated events
     */
    @NonNull
    private static List<PlatformEvent> generateEvents(@NonNull final Random random, @NonNull final Roster roster) {
        final StandardEventEmitter eventEmitter = new EventEmitterFactory(CONTEXT, random, roster).newStandardEmitter();
        return eventEmitter.emitEvents(NUMBER_OF_EVENTS_PER_TEST).stream()
                .map(EventImpl::getBaseEvent)
                .toList();
    }

    /**
     * Adds the given events to the intake in a random order.
     *
     * @param events  the events to add
     * @param random  the random number generator
     * @param intake  the test intake to add events to
     */
    private static void addToIntake(
            @NonNull final List<PlatformEvent> events, @NonNull final Random random, @NonNull final TestIntake intake) {
        final List<PlatformEvent> copiedEvents =
                events.stream().map(PlatformEvent::copyGossipedData).collect(Collectors.toList());
        Collections.shuffle(copiedEvents, random);
        copiedEvents.forEach(intake::addEvent);
    }

    /**
     * Validates the output contract of the given consensus output.
     * <p>
     * This method checks that:
     * <ul>
     *     <li>Every ancient pre-consensus event has reached consensus</li>
     *     <li>Every consensus event hash is also present in the pre-consensus event hashes</li>
     * </ul>
     *
     * @param output the consensus output to validate
     */
    private static void validateOutputContract(@NonNull final ConsensusOutput output) {
        final EventWindow eventWindow = output.getLastConsensusRound().getEventWindow();
        final Set<Hash> consensusHashes = output.consensusEventHashes();
        final Set<Hash> preConsensusHashes = output.getPreConsensusEventHashes();

        for (final PlatformEvent preConsensusEvent : output.getPreConsensusEvents()) {
            if (eventWindow.isAncient(preConsensusEvent)) {
                assertTrue(
                        consensusHashes.contains(preConsensusEvent.getHash()),
                        "Event %s is an ancient pre-consensus event, but has not been returned as a consensus event. "
                                        .formatted(preConsensusEvent
                                                .getDescriptor()
                                                .shortString())
                                + "Every ancient pre-consensus event added should have reached consensus.");
            }
        }

        for (final Hash consensusHash : consensusHashes) {
            assertTrue(
                    preConsensusHashes.contains(consensusHash),
                    "every consensus event hash should have been returned as a pre-consensus event");
        }
    }
}
