// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsContextTest {
    private static final Bytes BLOCK_HASH = Bytes.wrap("BH");
    private static final Bytes VERIFICATION_KEY = Bytes.wrap("VK");
    private static final Bytes AGGREGATION_KEY = Bytes.wrap("AK");
    private static final PreprocessedKeys PREPROCESSED_KEYS = new PreprocessedKeys(AGGREGATION_KEY, VERIFICATION_KEY);
    private static final NodePartyId A_NODE_PARTY_ID = new NodePartyId(1L, 2);
    private static final NodePartyId B_NODE_PARTY_ID = new NodePartyId(3L, 6);
    private static final NodePartyId C_NODE_PARTY_ID = new NodePartyId(7L, 14);
    private static final NodePartyId D_NODE_PARTY_ID = new NodePartyId(9L, 18);
    private static final HintsConstruction CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(1L)
            .hintsScheme(new HintsScheme(
                    PREPROCESSED_KEYS, List.of(A_NODE_PARTY_ID, B_NODE_PARTY_ID, C_NODE_PARTY_ID, D_NODE_PARTY_ID)))
            .build();

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsLibraryCodec codec;

    @Mock
    private Bytes signature;

    @Mock
    private Bytes badKey;

    @Mock
    private Bytes goodKey;

    private HintsContext subject;

    @BeforeEach
    void setUp() {
        subject = new HintsContext(library, codec);
    }

    @Test
    void becomesReadyOnceConstructionSet() {
        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, subject::constructionIdOrThrow);
        assertThrows(IllegalStateException.class, subject::verificationKeyOrThrow);

        subject.setConstruction(CONSTRUCTION);

        assertTrue(subject.isReady());

        assertEquals(CONSTRUCTION.constructionId(), subject.constructionIdOrThrow());
        assertEquals(VERIFICATION_KEY, subject.verificationKeyOrThrow());
    }

    @Test
    void signingWorksAsExpectedFor() {
        given(codec.extractPublicKey(AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(badKey);
        given(codec.extractPublicKey(AGGREGATION_KEY, B_NODE_PARTY_ID.partyId()))
                .willReturn(null);
        given(codec.extractPublicKey(AGGREGATION_KEY, C_NODE_PARTY_ID.partyId()))
                .willReturn(goodKey);
        given(codec.extractPublicKey(AGGREGATION_KEY, D_NODE_PARTY_ID.partyId()))
                .willReturn(goodKey);
        given(library.verifyBls(signature, BLOCK_HASH, badKey)).willReturn(false);
        given(library.verifyBls(signature, BLOCK_HASH, goodKey)).willReturn(true);
        final long cWeight = 1L;
        final long dWeight = 2L;
        given(codec.extractTotalWeight(VERIFICATION_KEY)).willReturn(3 * (cWeight + dWeight));
        given(codec.extractWeight(AGGREGATION_KEY, C_NODE_PARTY_ID.partyId())).willReturn(cWeight);
        given(codec.extractWeight(AGGREGATION_KEY, D_NODE_PARTY_ID.partyId())).willReturn(dWeight);
        final Map<Integer, Bytes> expectedSignatures = Map.of(
                C_NODE_PARTY_ID.partyId(), signature,
                D_NODE_PARTY_ID.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("AS");
        given(library.aggregateSignatures(AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);

        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigning(BLOCK_HASH);
        final var future = signing.future();

        signing.incorporate(CONSTRUCTION.constructionId() + 1, 0L, signature);
        assertFalse(future.isDone());
        signing.incorporate(CONSTRUCTION.constructionId(), Long.MAX_VALUE, signature);
        assertFalse(future.isDone());
        signing.incorporate(CONSTRUCTION.constructionId(), A_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporate(CONSTRUCTION.constructionId(), B_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporate(CONSTRUCTION.constructionId(), C_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporate(CONSTRUCTION.constructionId(), D_NODE_PARTY_ID.nodeId(), signature);
        assertTrue(future.isDone());
        assertEquals(aggregateSignature, future.join());
    }
}
