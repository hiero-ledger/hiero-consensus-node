// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.impl.HintsKeyAccessor;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeyProvenanceSignerTest {
    private static final long SOURCE_ID = 7;
    private static final long CRS_ID = 3;
    private static final Bytes SOURCE_VK = bytesOfLength(VK_LENGTH, 1);
    private static final Bytes NEXT_VK = bytesOfLength(VK_LENGTH, 2);
    private static final Bytes AK = bytesOfLength(49, 3);
    private static final Bytes SHARE = bytesOfLength(KeyProvenanceSigner.PARTIAL_SIGNATURE_LENGTH, 4);
    private static final Bytes AGGREGATE = bytesOfLength(SIGNATURE_LENGTH, 5);
    private static final CRSState CRS = CRSState.newBuilder()
            .ceremonyId(CRS_ID)
            .numParties(4)
            .stage(CRSStage.COMPLETED)
            .crs(bytesOfLength(304 + 4 * 288, 0))
            .build();
    private static final List<NodePartyId> PARTIES =
            List.of(new NodePartyId(10, 2, 1), new NodePartyId(20, 0, 1), new NodePartyId(30, 1, 1));

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsKeyAccessor keyAccessor;

    private KeyProvenanceSigner subject;

    @BeforeEach
    void setUp() {
        subject = new KeyProvenanceSigner(library, keyAccessor, construction(PARTIES), CRS);
    }

    @Test
    void signsExactSuccessorKeyWithPersistedSourceConstructionAndChecksLocalShare() {
        given(keyAccessor.signWithBlsPrivateKey(SOURCE_ID, NEXT_VK)).willReturn(SHARE);
        given(library.verifyBls(CRS.crs(), SHARE, NEXT_VK, AK, 2)).willReturn(true);

        assertEquals(SHARE, subject.sign(10, NEXT_VK));
        assertEquals(SOURCE_ID, subject.constructionId());
        assertEquals(SOURCE_VK, subject.verificationKey());
        verify(keyAccessor).signWithBlsPrivateKey(SOURCE_ID, NEXT_VK);
    }

    @Test
    void refusesToGossipMismatchedOrMissingLocalKey() {
        given(keyAccessor.signWithBlsPrivateKey(SOURCE_ID, NEXT_VK)).willReturn(SHARE, (Bytes) null);
        assertNull(subject.sign(10, NEXT_VK));
        assertNull(subject.sign(10, NEXT_VK));
    }

    @Test
    void rejectsWrongGenerationUnknownCreatorAndMalformedBytesBeforeCallingNative() {
        assertFalse(subject.verify(10, SOURCE_ID + 1, NEXT_VK, SHARE));
        assertFalse(subject.verify(999, SOURCE_ID, NEXT_VK, SHARE));
        assertFalse(subject.verify(10, SOURCE_ID, Bytes.EMPTY, SHARE));
        assertFalse(subject.verify(10, SOURCE_ID, NEXT_VK, Bytes.EMPTY));
        assertFalse(subject.verifyAggregate(NEXT_VK, SHARE));
        assertFalse(subject.verifyAggregate(Bytes.EMPTY, AGGREGATE));
        assertNull(subject.sign(999, NEXT_VK));
        assertNull(subject.sign(10, Bytes.EMPTY));
        verifyNoInteractions(library, keyAccessor);
    }

    @Test
    void requiresStrictlyMoreThanOneThirdOfFrozenSourceWeight() {
        given(library.verifyBls(CRS.crs(), SHARE, NEXT_VK, AK, 2)).willReturn(true);
        assertTrue(subject.aggregate(NEXT_VK, Map.of(10L, SHARE)).isEmpty());
        verify(library, never()).aggregateSignatures(any(), any(), any(), any());
    }

    @Test
    void aggregatesByPartyAndAlwaysChecksTheFixedAbftThreshold() {
        given(library.verifyBls(eq(CRS.crs()), eq(SHARE), eq(NEXT_VK), eq(AK), anyInt()))
                .willReturn(true);
        given(library.aggregateSignatures(eq(CRS.crs()), eq(AK), eq(SOURCE_VK), any()))
                .willReturn(AGGREGATE);
        given(library.verifyAggregate(AGGREGATE, NEXT_VK, SOURCE_VK, 1, 3)).willReturn(true);

        assertEquals(
                AGGREGATE,
                subject.aggregate(NEXT_VK, Map.of(10L, SHARE, 20L, SHARE)).orElseThrow());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<Integer, Bytes>> captor = ArgumentCaptor.forClass(Map.class);
        verify(library).aggregateSignatures(eq(CRS.crs()), eq(AK), eq(SOURCE_VK), captor.capture());
        assertEquals(List.of(0, 2), List.copyOf(captor.getValue().keySet()));
        verify(library).verifyAggregate(AGGREGATE, NEXT_VK, SOURCE_VK, 1, 3);
    }

    @Test
    void invalidCompanionShareCannotContributeWeight() {
        assertTrue(subject.aggregate(NEXT_VK, Map.of(10L, SHARE, 20L, SHARE)).isEmpty());
        verify(library, never()).aggregateSignatures(any(), any(), any(), any());
    }

    @Test
    void invalidAggregateCannotEscapeEvenWithEnoughValidShares() {
        given(library.verifyBls(eq(CRS.crs()), eq(SHARE), eq(NEXT_VK), eq(AK), anyInt()))
                .willReturn(true);
        given(library.aggregateSignatures(eq(CRS.crs()), eq(AK), eq(SOURCE_VK), any()))
                .willReturn(AGGREGATE);
        assertTrue(subject.aggregate(NEXT_VK, Map.of(10L, SHARE, 20L, SHARE)).isEmpty());
    }

    @Test
    void eligibilityDependsOnlyOnMembershipAndWeightInTheSourceScheme() {
        subject = new KeyProvenanceSigner(
                library, keyAccessor, construction(List.of(new NodePartyId(10, 0, 0), new NodePartyId(20, 1, 3))), CRS);

        assertFalse(subject.isEligibleSigner(10));
        assertTrue(subject.isEligibleSigner(20));
        assertFalse(subject.isEligibleSigner(30));
        verifyNoInteractions(library, keyAccessor);
    }

    @Test
    void zeroWeightPartiesCannotSignOrContribute() {
        subject = new KeyProvenanceSigner(
                library, keyAccessor, construction(List.of(new NodePartyId(10, 0, 0), new NodePartyId(20, 1, 3))), CRS);
        assertNull(subject.sign(10, NEXT_VK));
        assertFalse(subject.verify(10, SOURCE_ID, NEXT_VK, SHARE));
        verifyNoInteractions(library, keyAccessor);
    }

    @Test
    void rejectsUnboundIncompleteAndInvalidSourceSchemes() {
        final var valid = construction(PARTIES);
        assertThrows(
                IllegalArgumentException.class,
                () -> new KeyProvenanceSigner(
                        library, keyAccessor, valid.copyBuilder().crsId(99).build(), CRS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KeyProvenanceSigner(
                        library,
                        keyAccessor,
                        valid,
                        CRS.copyBuilder().numParties(8).build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KeyProvenanceSigner(
                        library,
                        keyAccessor,
                        valid,
                        CRS.copyBuilder()
                                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                                .build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KeyProvenanceSigner(library, keyAccessor, HintsConstruction.DEFAULT, CRS));
        for (final var parties : List.of(
                List.of(new NodePartyId(10, 0, 1), new NodePartyId(10, 1, 1)),
                List.of(new NodePartyId(10, 0, 1), new NodePartyId(20, 0, 1)),
                List.of(new NodePartyId(10, 3, 1)),
                List.of(new NodePartyId(10, 0, -1)),
                List.of(new NodePartyId(10, 0, 0)))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new KeyProvenanceSigner(library, keyAccessor, construction(parties), CRS));
        }
        assertThrows(
                ArithmeticException.class,
                () -> new KeyProvenanceSigner(
                        library,
                        keyAccessor,
                        construction(List.of(new NodePartyId(10, 0, Long.MAX_VALUE), new NodePartyId(20, 1, 1))),
                        CRS));
    }

    @Test
    void nativeSourceEpochCertifiesOnlyExactSuccessorKeyAtStrictAbftThreshold() {
        final var nativeLibrary = new HintsLibraryImpl();
        final var initialCrs = nativeLibrary.newCrs((short) 8);
        final var crs = decodeCrsUpdate(initialCrs.length(), nativeLibrary.updateCrs(initialCrs, bytesOfLength(32, 42)))
                .crs();
        final var privateKeys = new TreeMap<Integer, Bytes>();
        final var hints = new TreeMap<Integer, Bytes>();
        final var weights = new TreeMap<Integer, Long>();
        for (int party = 1; party <= 3; party++) {
            final var privateKey = nativeLibrary.newBlsPrivateKey();
            privateKeys.put(party, privateKey);
            hints.put(party, nativeLibrary.computeHints(crs, privateKey, party, 8));
            weights.put(party, 1L);
        }
        final var oldKeys = nativeLibrary.preprocess(crs, hints, weights, 8);
        final var sourceVk = Bytes.wrap(oldKeys.verificationKey());
        final var targetKey = nativeLibrary.newBlsPrivateKey();
        final var nextKeys = nativeLibrary.preprocess(
                crs,
                new TreeMap<>(Map.of(1, nativeLibrary.computeHints(crs, targetKey, 1, 8))),
                new TreeMap<>(Map.of(1, 1L)),
                8);
        final var nextVk = Bytes.wrap(nextKeys.verificationKey());
        final var sourceConstruction = construction(
                        List.of(new NodePartyId(10, 1, 1), new NodePartyId(20, 2, 1), new NodePartyId(30, 3, 1)))
                .copyBuilder()
                .numParties(8)
                .hintsScheme(HintsScheme.newBuilder()
                        .preprocessedKeys(new PreprocessedKeys(Bytes.wrap(oldKeys.aggregationKey()), sourceVk))
                        .nodePartyIds(new NodePartyId(10, 1, 1), new NodePartyId(20, 2, 1), new NodePartyId(30, 3, 1)))
                .build();
        final var unusedKeyAccessor = mock(HintsKeyAccessor.class);
        final var nativeSigner = new KeyProvenanceSigner(
                nativeLibrary,
                unusedKeyAccessor,
                sourceConstruction,
                CRS.copyBuilder().numParties(8).crs(crs).build());
        final var first = nativeLibrary.signBls(nextVk, privateKeys.get(1));
        final var second = nativeLibrary.signBls(nextVk, privateKeys.get(2));
        assertEquals(KeyProvenanceSigner.PARTIAL_SIGNATURE_LENGTH, first.length());
        assertTrue(nativeSigner.verify(10, SOURCE_ID, nextVk, first));
        assertFalse(nativeSigner.verify(20, SOURCE_ID, nextVk, first));
        assertFalse(nativeSigner.verify(10, SOURCE_ID, sourceVk, first));
        assertTrue(nativeSigner.aggregate(nextVk, Map.of(10L, first)).isEmpty());
        final var exactlyOneThird = nativeLibrary.aggregateSignatures(
                crs, Bytes.wrap(oldKeys.aggregationKey()), sourceVk, Map.of(1, first));
        assertFalse(nativeSigner.verifyAggregate(nextVk, exactlyOneThird));
        final var signature =
                nativeSigner.aggregate(nextVk, Map.of(10L, first, 20L, second)).orElseThrow();
        assertTrue(nativeSigner.verifyAggregate(nextVk, signature));
        assertFalse(nativeSigner.verifyAggregate(sourceVk, signature));
        assertFalse(nativeLibrary.verifyAggregate(signature, nextVk, nextVk, 1, 3));
        assertEquals(
                signature,
                nativeSigner.aggregate(nextVk, Map.of(20L, second, 10L, first)).orElseThrow());
        verifyNoInteractions(unusedKeyAccessor);
    }

    private static HintsConstruction construction(final List<NodePartyId> parties) {
        return HintsConstruction.newBuilder()
                .constructionId(SOURCE_ID)
                .crsId(CRS_ID)
                .numParties(4)
                .hintsScheme(HintsScheme.newBuilder()
                        .preprocessedKeys(new PreprocessedKeys(AK, SOURCE_VK))
                        .nodePartyIds(parties))
                .build();
    }

    private static Bytes bytesOfLength(final int length, final int firstByte) {
        final byte[] bytes = new byte[length];
        bytes[0] = (byte) firstByte;
        return Bytes.wrap(bytes);
    }
}
