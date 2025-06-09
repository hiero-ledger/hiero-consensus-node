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
    private static final NodePartyId A_NODE_PARTY_ID = new NodePartyId(1L, 2, 1L);
    private static final NodePartyId B_NODE_PARTY_ID = new NodePartyId(3L, 6, 1L);
    private static final NodePartyId C_NODE_PARTY_ID = new NodePartyId(7L, 14, 1L);
    private static final NodePartyId D_NODE_PARTY_ID = new NodePartyId(9L, 18, 9L);
    private static final HintsConstruction CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(1L)
            .hintsScheme(new HintsScheme(
                    PREPROCESSED_KEYS, List.of(A_NODE_PARTY_ID, B_NODE_PARTY_ID, C_NODE_PARTY_ID, D_NODE_PARTY_ID)))
            .build();
    private static final Bytes CRS = Bytes.wrap("CRS");

    @Mock
    private HintsLibrary library;

    @Mock
    private Bytes signature;

    private HintsContext subject;

    @BeforeEach
    void setUp() {
        subject = new HintsContext(library);
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
    void incorporatingValidWorksAsExpected() {
        final Map<Integer, Bytes> expectedSignatures = Map.of(
                A_NODE_PARTY_ID.partyId(), signature,
                B_NODE_PARTY_ID.partyId(), signature,
                C_NODE_PARTY_ID.partyId(), signature,
                D_NODE_PARTY_ID.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("AS");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);

        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigning(BLOCK_HASH, () -> {});
        final var future = signing.future();

        signing.incorporateValid(CRS, A_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporateValid(CRS, B_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporateValid(CRS, C_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);
        assertTrue(future.isDone());
        assertEquals(aggregateSignature, future.join());
    }
}
