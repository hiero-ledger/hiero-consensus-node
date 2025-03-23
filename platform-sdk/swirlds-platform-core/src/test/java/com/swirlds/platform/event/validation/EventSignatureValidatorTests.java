// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.test.fixtures.crypto.PreGeneratedX509Certs;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventSignatureValidatorTests {
    private Randotron random;
    private PlatformContext platformContext;
    private FakeTime time;
    private AtomicLong exitedIntakePipelineCount;
    private IntakeEventCounter intakeEventCounter;

    private Roster currentRoster;

    /**
     * A verifier that always returns true.
     */
    private final SignatureVerifier trueVerifier = (data, signature, publicKey) -> true;

    /**
     * A verifier that always returns false.
     */
    private final SignatureVerifier falseVerifier = (data, signature, publicKey) -> false;

    private EventSignatureValidator validatorWithTrueVerifier;
    private EventSignatureValidator validatorWithFalseVerifier;

    private SemanticVersion defaultVersion;

    /**
     * This address belongs to a node that is placed in the previous roster.
     */
    private RosterEntry previousNodeRosterEntry;

    /**
     * This address belongs to a node that is placed in the current roster.
     */
    private RosterEntry currentNodeRosterEntry;

    /**
     * Generate a mock RosterEntry, with enough elements mocked to support the signature validation.
     *
     * @param nodeId the node id to use for the address
     * @return a mock roster entry
     */
    private static RosterEntry generateMockRosterEntry(final long nodeId) {
        try {
            return new RosterEntry(
                    nodeId,
                    10,
                    Bytes.wrap(PreGeneratedX509Certs.getSigCert(nodeId)
                            .getCertificate()
                            .getEncoded()),
                    List.of());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setup() {
        random = Randotron.create();
        time = new FakeTime();
        platformContext = TestPlatformContextBuilder.create().withTime(time).build();

        exitedIntakePipelineCount = new AtomicLong(0);
        intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> {
                    exitedIntakePipelineCount.incrementAndGet();
                    return null;
                })
                .when(intakeEventCounter)
                .eventExitedIntakePipeline(any());

        // create two addresses, one for the previous address book and one for the current address book
        previousNodeRosterEntry = generateMockRosterEntry(66);
        currentNodeRosterEntry = generateMockRosterEntry(77);

        final Roster previousRoster = new Roster(List.of(previousNodeRosterEntry));
        currentRoster = new Roster(List.of(currentNodeRosterEntry));

        defaultVersion = SemanticVersion.newBuilder().major(2).build();

        validatorWithTrueVerifier = new DefaultEventSignatureValidator(
                platformContext, trueVerifier, defaultVersion, previousRoster, currentRoster, intakeEventCounter);

        validatorWithFalseVerifier = new DefaultEventSignatureValidator(
                platformContext, falseVerifier, defaultVersion, previousRoster, currentRoster, intakeEventCounter);
    }

    @Test
    @DisplayName("Events with higher version than the app should always fail validation")
    void irreconcilableVersions() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(currentNodeRosterEntry.nodeId()))
                .setSoftwareVersion(SemanticVersion.newBuilder().major(3).build())
                .build();

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Lower version event with missing previous address book")
    void versionMismatchWithNullPreviousAddressBook() {
        final EventSignatureValidator signatureValidator = new DefaultEventSignatureValidator(
                platformContext, trueVerifier, defaultVersion, null, currentRoster, intakeEventCounter);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(previousNodeRosterEntry.nodeId()))
                .setSoftwareVersion(SemanticVersion.newBuilder().major(3).build())
                .build();

        assertNull(signatureValidator.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Node is missing from the applicable address book")
    void applicableAddressBookMissingNode() {
        // this creator isn't in the current address book, so verification will fail
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(previousNodeRosterEntry.nodeId()))
                .setSoftwareVersion(defaultVersion)
                .build();

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Node has a null public key")
    void missingPublicKey() {
        final NodeId nodeId = NodeId.of(88);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(nodeId)
                .setSoftwareVersion(defaultVersion)
                .build();

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event passes validation if the signature verifies")
    void validSignature() {
        // both the event and the app have the same version, so the currentAddressBook will be selected
        final PlatformEvent event1 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(currentNodeRosterEntry.nodeId()))
                .setSoftwareVersion(defaultVersion)
                .build();

        assertNotEquals(null, validatorWithTrueVerifier.validateSignature(event1));
        assertEquals(0, exitedIntakePipelineCount.get());

        // event2 is from a previous version, so the previous address book will be selected
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(previousNodeRosterEntry.nodeId()))
                .setSoftwareVersion(SemanticVersion.newBuilder().major(1).build())
                .build();

        assertNotEquals(null, validatorWithTrueVerifier.validateSignature(event2));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event fails validation if the signature does not verify")
    void verificationFails() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(currentNodeRosterEntry.nodeId()))
                .setSoftwareVersion(defaultVersion)
                .build();

        assertNotEquals(null, validatorWithTrueVerifier.validateSignature(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        assertNull(validatorWithFalseVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Ancient events are discarded")
    void ancientEvent(final boolean useBirthRoundForAncientThreshold) {
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, useBirthRoundForAncientThreshold)
                        .getOrCreateConfig())
                .build();
        final Roster previousRoster = new Roster(List.of(previousNodeRosterEntry));

        final EventSignatureValidator validator = new DefaultEventSignatureValidator(
                platformContext, trueVerifier, defaultVersion, previousRoster, currentRoster, intakeEventCounter);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(currentNodeRosterEntry.nodeId()))
                .setBirthRound(EventConstants.MINIMUM_ROUND_CREATED)
                .setSoftwareVersion(defaultVersion)
                .build();

        assertNotEquals(null, validator.validateSignature(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        validatorWithTrueVerifier.setEventWindow(new EventWindow(
                ConsensusConstants.ROUND_FIRST,
                100L,
                ConsensusConstants.ROUND_FIRST /* ignored in this context */,
                platformContext
                        .getConfiguration()
                        .getConfigData(EventConfig.class)
                        .getAncientMode()));

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }
}
