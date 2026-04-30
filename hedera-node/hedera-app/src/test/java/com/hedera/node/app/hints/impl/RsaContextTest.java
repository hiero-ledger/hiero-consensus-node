// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterSignatures;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.function.Supplier;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RsaContextTest {
    private static final Bytes MESSAGE = Bytes.wrap("message");

    @Mock
    private Supplier<Configuration> configProvider;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    private RsaContext subject;

    @BeforeEach
    void setUp() {
        subject = new RsaContext(configProvider);
        lenient().when(configProvider.get()).thenReturn(configuration);
        lenient().when(configuration.getConfigData(TssConfig.class)).thenReturn(tssConfig);
        lenient().when(tssConfig.signingThresholdDivisor()).thenReturn(2);
    }

    @Test
    void notReadyUntilInitialized() {
        assertFalse(subject.isReady());

        assertThrows(IllegalStateException.class, () -> subject.newSigning(MESSAGE, () -> {}));
    }

    @Test
    void validatesRsaSignaturesFromRoster() throws Exception {
        final var keys = KeysAndCertsGenerator.generate(NodeId.of(1L));
        final var roster = new Roster(List.of(entryFor(1L, 10L, keys)));
        final var signature =
                new PlatformSigner(keys).sign(MESSAGE.toByteArray()).getBytes();

        subject.initialize(roster, nodeId -> 10L);

        assertTrue(subject.isReady());
        assertTrue(subject.validate(1L, MESSAGE, signature));
        assertTrue(subject.validate(
                1L, new HintsPartialSignatureTransactionBody(RsaContext.CONSTRUCTION_ID, MESSAGE, signature)));
        assertFalse(subject.validate(1L, Bytes.wrap("wrong-message"), signature));
        assertFalse(subject.validate(2L, MESSAGE, signature));
        assertFalse(subject.validate(1L, new HintsPartialSignatureTransactionBody(123L, MESSAGE, signature)));
    }

    @Test
    void ignoresRosterEntriesWithoutRsaCertificates() {
        final var roster = new Roster(
                List.of(RosterEntry.newBuilder().nodeId(1L).weight(10L).build()));

        subject.initialize(roster, nodeId -> 10L);

        assertFalse(subject.isReady());
        assertFalse(subject.validate(1L, MESSAGE, Bytes.wrap("signature")));
        assertThrows(IllegalStateException.class, () -> subject.newSigning(MESSAGE, () -> {}));
    }

    @Test
    void rejectsNegativeWeights() throws Exception {
        final var keys = KeysAndCertsGenerator.generate(NodeId.of(1L));
        final var roster = new Roster(List.of(entryFor(1L, 10L, keys)));

        assertThrows(IllegalArgumentException.class, () -> subject.initialize(roster, nodeId -> -1L));
    }

    @Test
    void signingUsesInitializedWeightsAndSerializesRosterSignatures() throws Exception {
        final var keys1 = KeysAndCertsGenerator.generate(NodeId.of(1L));
        final var keys2 = KeysAndCertsGenerator.generate(NodeId.of(2L));
        final var roster = new Roster(List.of(entryFor(1L, 6L, keys1), entryFor(2L, 4L, keys2)));
        final var sig1 = new PlatformSigner(keys1).sign(MESSAGE.toByteArray()).getBytes();

        subject.initialize(roster, nodeId -> nodeId == 1L ? 6L : 4L);
        final var signing = (RsaContext.Signing) subject.newSigning(MESSAGE, () -> {});

        signing.incorporateValid(Bytes.EMPTY, 1L, sig1);

        assertTrue(signing.future().isDone());
        final var rosterSignatures =
                RosterSignatures.PROTOBUF.parse(signing.future().join());
        assertEquals(RosterUtils.hash(roster).getBytes(), rosterSignatures.rosterHash());
        assertEquals(1, rosterSignatures.nodeSignatures().size());
        assertEquals(1L, rosterSignatures.nodeSignatures().getFirst().nodeId());
        assertEquals(sig1, rosterSignatures.nodeSignatures().getFirst().nodeSignature());
    }

    @Test
    void duplicateSignaturesDoNotAccumulateWeight() throws Exception {
        final var keys1 = KeysAndCertsGenerator.generate(NodeId.of(1L));
        final var keys2 = KeysAndCertsGenerator.generate(NodeId.of(2L));
        final var roster = new Roster(List.of(entryFor(1L, 4L, keys1), entryFor(2L, 4L, keys2)));
        final var sig1 = new PlatformSigner(keys1).sign(MESSAGE.toByteArray()).getBytes();
        final var sig2 = new PlatformSigner(keys2).sign(MESSAGE.toByteArray()).getBytes();

        subject.initialize(roster, nodeId -> 4L);
        final var signing = (RsaContext.Signing) subject.newSigning(MESSAGE, () -> {});

        signing.incorporateValid(Bytes.EMPTY, 2L, sig2);
        signing.incorporateValid(Bytes.EMPTY, 2L, sig2);
        assertFalse(signing.future().isDone());

        signing.incorporateValid(Bytes.EMPTY, 1L, sig1);

        final var rosterSignatures =
                RosterSignatures.PROTOBUF.parse(signing.future().join());
        assertEquals(1L, rosterSignatures.nodeSignatures().get(0).nodeId());
        assertEquals(2L, rosterSignatures.nodeSignatures().get(1).nodeId());
    }

    @Test
    void zeroAndUnknownWeightSignaturesDoNotContribute() throws Exception {
        final var keys1 = KeysAndCertsGenerator.generate(NodeId.of(1L));
        final var keys2 = KeysAndCertsGenerator.generate(NodeId.of(2L));
        final var roster = new Roster(List.of(entryFor(1L, 0L, keys1), entryFor(2L, 4L, keys2)));
        final var sig1 = new PlatformSigner(keys1).sign(MESSAGE.toByteArray()).getBytes();
        final var sig2 = new PlatformSigner(keys2).sign(MESSAGE.toByteArray()).getBytes();

        subject.initialize(roster, nodeId -> nodeId == 1L ? 0L : 4L);
        final var signing = (RsaContext.Signing) subject.newSigning(MESSAGE, () -> {});

        signing.incorporateValid(Bytes.EMPTY, 1L, sig1);
        signing.incorporateValid(Bytes.EMPTY, 3L, Bytes.wrap("unknown"));

        assertFalse(signing.future().isDone());

        signing.incorporateValid(Bytes.EMPTY, 2L, sig2);

        assertTrue(signing.future().isDone());
    }

    private static RosterEntry entryFor(final long nodeId, final long weight, final KeysAndCerts keysAndCerts)
            throws Exception {
        return RosterEntry.newBuilder()
                .nodeId(nodeId)
                .weight(weight)
                .gossipCaCertificate(Bytes.wrap(keysAndCerts.sigCert().getEncoded()))
                .build();
    }
}
