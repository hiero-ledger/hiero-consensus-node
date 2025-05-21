// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Random;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the {@link InOrderLinker} class.
 */
class InOrderLinkerTests {
    private Random random;

    private InOrderLinker inOrderLinker;

    private PlatformEvent genesisSelfParent;
    private PlatformEvent genesisOtherParent;

    private FakeTime time;

    private NodeId selfId = NodeId.of(0);
    private NodeId otherId = NodeId.of(1);

    /**
     * Set up the in order linker for testing
     * <p>
     * This method creates 2 genesis events and submits them to the linker, as a foundation for the tests.
     */
    @BeforeEach
    void setup() {
        random = getRandomPrintSeed();
        time = new FakeTime();
    }

    private void inOrderLinkerSetup(@NonNull final AncientMode ancientMode) {
        inOrderLinker = new ConsensusLinker(
                TestPlatformContextBuilder.create()
                        .withConfiguration(new TestConfigBuilder()
                                .withValue(
                                        EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD,
                                        (ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD))
                                .getOrCreateConfig())
                        .withTime(time)
                        .build(),
                selfId);

        time.tick(Duration.ofSeconds(1));
        genesisSelfParent = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setBirthRound(ROUND_FIRST)
                .setTimeCreated(time.now())
                .build();

        inOrderLinker.linkEvent(genesisSelfParent);

        time.tick(Duration.ofSeconds(1));
        genesisOtherParent = new TestingEventBuilder(random)
                .setCreatorId(otherId)
                .setBirthRound(ROUND_FIRST)
                .setTimeCreated(time.now())
                .build();

        inOrderLinker.linkEvent(genesisOtherParent);

        time.tick(Duration.ofSeconds(1));
    }

    /**
     * Choose an event window that will cause all given events to be considered ancient.
     *
     * @param ancientMode   the ancient mode to use
     * @param ancientEvents the events that will be considered ancient
     * @return the event window that will cause the given events to be considered ancient
     */
    private static EventWindow chooseEventWindow(
            @NonNull final AncientMode ancientMode, final PlatformEvent... ancientEvents) {

        long ancientValue = 0;
        for (final PlatformEvent ancientEvent : ancientEvents) {
            ancientValue = switch (ancientMode) {
                case BIRTH_ROUND_THRESHOLD -> Math.max(ancientValue, ancientEvent.getBirthRound());
                case GENERATION_THRESHOLD -> Math.max(ancientValue, ancientEvent.getGeneration());
            };
        }

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setAncientMode(ancientMode)
                /* one more than the ancient value, so that the events are ancient */
                .setAncientThreshold(ancientValue + 1)
                .build();

        for (final PlatformEvent ancientEvent : ancientEvents) {
            assertTrue(eventWindow.isAncient(ancientEvent));
        }

        return eventWindow;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test standard operation of the in order linker")
    void standardOperation(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        // In the following test events are created with increasing generation and birth round numbers.
        // The linking should fail to occur based on the advancing event window.
        // The values used for birthRound and generation are just for this test and do not reflect real world values.

        final PlatformEvent child1 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(genesisSelfParent.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent1 = inOrderLinker.linkEvent(child1);
        assertNotEquals(null, linkedEvent1);
        assertNotEquals(null, linkedEvent1.getSelfParent(), "Self parent is non-ancient, and should not be null");
        assertNotEquals(null, linkedEvent1.getOtherParent(), "Other parent is non-ancient, and should not be null");

        time.tick(Duration.ofSeconds(1));

        // cause genesisOtherParent to become ancient
        EventWindow eventWindow = chooseEventWindow(ancientMode, genesisOtherParent);
        assertFalse(eventWindow.isAncient(child1));
        inOrderLinker.setEventWindow(eventWindow);

        final PlatformEvent child2 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child1)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(child1.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent2 = inOrderLinker.linkEvent(child2);
        assertNotEquals(null, linkedEvent2);
        assertNotEquals(null, linkedEvent2.getSelfParent(), "Self parent is non-ancient, and should not be null");
        assertNull(linkedEvent2.getOtherParent(), "Other parent is ancient, and should be null");

        time.tick(Duration.ofSeconds(1));

        // cause child1 to become ancient
        eventWindow = chooseEventWindow(ancientMode, child1);
        assertFalse(eventWindow.isAncient(child2));
        inOrderLinker.setEventWindow(eventWindow);

        final PlatformEvent child3 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child1)
                .setOtherParent(child2)
                .setBirthRound(child2.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent3 = inOrderLinker.linkEvent(child3);
        assertNotEquals(null, linkedEvent3);
        assertNull(linkedEvent3.getSelfParent(), "Self parent is ancient, and should be null");
        assertNotEquals(null, linkedEvent3.getOtherParent(), "Other parent is non-ancient, and should not be null");

        time.tick(Duration.ofSeconds(1));
        // make both parents ancient.
        eventWindow = chooseEventWindow(ancientMode, child2, child3);
        inOrderLinker.setEventWindow(eventWindow);

        final PlatformEvent child4 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child2)
                .setOtherParent(child3)
                .setBirthRound(child3.getBirthRound() + 1)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent4 = inOrderLinker.linkEvent(child4);
        assertNotEquals(null, linkedEvent4);
        assertNull(linkedEvent4.getSelfParent(), "Self parent is ancient, and should be null");
        assertNull(linkedEvent4.getOtherParent(), "Other parent is ancient, and should be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Missing self parent should not be linked")
    void missingSelfParent(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setOtherParent(genesisOtherParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent is missing, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent is not missing, and should not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Missing other parent should not be linked")
    void missingOtherParent(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent is not missing, and should not be null");
        assertNull(linkedEvent.getOtherParent(), "Other parent is missing, and should be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Ancient events should not be linked")
    void ancientEvent(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        inOrderLinker.setEventWindow(EventWindowBuilder.builder()
                .setAncientMode(ancientMode)
                .setAncientThreshold(3)
                .build());

        final PlatformEvent child1 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(1)
                .setTimeCreated(time.now())
                .build();

        time.tick(Duration.ofSeconds(1));

        assertNull(inOrderLinker.linkEvent(child1));

        final PlatformEvent child2 = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(child1)
                .setOtherParent(genesisOtherParent)
                .setBirthRound(2)
                .setTimeCreated(time.now())
                .build();

        assertNull(inOrderLinker.linkEvent(child2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Self parent with mismatched generation should not be linked")
    void selfParentGenerationMismatch(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .overrideSelfParentGeneration(genesisSelfParent.getGeneration() + 1) // generation doesn't match actual
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent has mismatched generation, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Self parent with mismatched birth round should not be linked")
    void selfParentBirthRoundMismatch(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .overrideSelfParentBirthRound(genesisSelfParent.getBirthRound() + 1) // birth round doesn't match actual
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent has mismatched birth round, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Other parent with mismatched generation should not be linked")
    void otherParentGenerationMismatch(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .overrideOtherParentGeneration(
                        genesisOtherParent.getGeneration() + 1) // generation doesn't match actual
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent should not be null");
        assertNull(linkedEvent.getOtherParent(), "Other parent has mismatched generation, and should be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Other parent with mismatched birth round should not be linked")
    void otherParentBirthRoundMismatch(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);
        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .overrideOtherParentBirthRound(
                        genesisOtherParent.getBirthRound() + 1) // birth round doesn't match actual
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent should not be null");
        assertNull(linkedEvent.getOtherParent(), "Other parent has mismatched birth round, and should be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Self parent with mismatched time created should not be linked")
    void selfParentTimeCreatedMismatch(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);

        final PlatformEvent lateParent = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(genesisOtherParent)
                .setTimeCreated(time.now().plus(Duration.ofSeconds(10)))
                .build();

        inOrderLinker.linkEvent(lateParent);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(lateParent)
                .setOtherParent(genesisOtherParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNull(linkedEvent.getSelfParent(), "Self parent has mismatched time created, and should be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Other parent with mismatched time created SHOULD be linked")
    void otherParentTimeCreatedMismatch(final boolean useBirthRoundForAncient) {
        final AncientMode ancientMode =
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD;
        inOrderLinkerSetup(ancientMode);
        final PlatformEvent lateParent = new TestingEventBuilder(random)
                .setCreatorId(otherId)
                .setSelfParent(genesisOtherParent)
                .setOtherParent(genesisSelfParent)
                .setTimeCreated(time.now().plus(Duration.ofSeconds(10)))
                .build();

        inOrderLinker.linkEvent(lateParent);

        final PlatformEvent child = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setSelfParent(genesisSelfParent)
                .setOtherParent(lateParent)
                .setTimeCreated(time.now())
                .build();

        final EventImpl linkedEvent = inOrderLinker.linkEvent(child);
        assertNotEquals(null, linkedEvent);
        assertNotEquals(null, linkedEvent.getSelfParent(), "Self parent should not be null");
        assertNotEquals(null, linkedEvent.getOtherParent(), "Other parent should not be null");
    }
}
