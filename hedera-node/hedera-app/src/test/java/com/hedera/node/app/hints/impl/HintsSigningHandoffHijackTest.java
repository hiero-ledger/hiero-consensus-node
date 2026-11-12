// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.blocks.BlockHashSigner.Request.SUCCINCT_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.history.impl.HistoryServiceComponent;
import com.hedera.node.app.history.impl.HistoryServiceImpl;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.tss.TssBlockHashSigner;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wires the real pre-handle, hinTS signing, history and block-signer classes together to check that a peer's
 * partial signature under the previous construction cannot decide how this node signs a post-handoff block.
 */
@ExtendWith(MockitoExtension.class)
class HintsSigningHandoffHijackTest {
    private static final long OLD_CONSTRUCTION_ID = 1L;
    private static final long NEW_CONSTRUCTION_ID = 2L;
    private static final long MINORITY_NODE_ID = 1L;
    private static final int MINORITY_PARTY_ID = 2;
    private static final long MAJORITY_NODE_ID = 9L;
    private static final int MAJORITY_PARTY_ID = 18;
    private static final Bytes OLD_VERIFICATION_KEY = Bytes.wrap("OLD_VK");
    private static final Bytes NEW_VERIFICATION_KEY = Bytes.wrap("NEW_VK");
    private static final Bytes OLD_AGGREGATION_KEY = Bytes.wrap(new byte[49]);
    private static final Bytes NEW_AGGREGATION_KEY = Bytes.wrap(aggregationKeyBytes());
    private static final List<NodePartyId> NODE_PARTY_IDS = List.of(
            new NodePartyId(MINORITY_NODE_ID, MINORITY_PARTY_ID, 1L),
            new NodePartyId(3L, 6, 1L),
            new NodePartyId(7L, 14, 1L),
            new NodePartyId(MAJORITY_NODE_ID, MAJORITY_PARTY_ID, 9L));
    private static final Bytes CRS = Bytes.wrap("CRS");
    private static final CRSState CRS_STATE = CRSState.newBuilder()
            .ceremonyId(1L)
            .numParties(32)
            .crs(CRS)
            .stage(CRSStage.COMPLETED)
            .build();
    private static final Bytes POST_HANDOFF_BLOCK_HASH = Bytes.wrap("POST_HANDOFF_BLOCK_HASH");
    private static final Bytes MINORITY_PARTIAL_SIGNATURE = Bytes.wrap("MINORITY_OLD_KEY_SIGNATURE");
    private static final Bytes MAJORITY_PARTIAL_SIGNATURE = Bytes.wrap("MAJORITY_NEW_KEY_SIGNATURE");
    private static final Bytes AGGREGATE_SIGNATURE = Bytes.wrap("AGGREGATE_SIGNATURE");
    private static final ChainOfTrustProof NEW_CHAIN_OF_TRUST_PROOF =
            ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("PROOF")).build();

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsSigningMetrics signingMetrics;

    @Mock
    private HintsServiceComponent hintsComponent;

    @Mock
    private HintsSubmissions submissions;

    @Mock
    private HistoryServiceComponent historyComponent;

    @Mock
    private RsaContext rsaContext;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private NodeInfo creatorInfo;

    private final Configuration config = HederaTestConfigBuilder.create()
            .withValue("tss.hintsEnabled", "true")
            .withValue("tss.historyEnabled", "true")
            .withValue("tss.forceMockSignatures", "false")
            .withValue("tss.useDeterministicHintsSignatures", "false")
            .withValue("blockStream.streamMode", "BLOCKS")
            .getOrCreateConfig();

    private final ConcurrentMap<SigningAttemptId, HintsContext.Signing> signings = new ConcurrentHashMap<>();

    private HintsContext hintsContext;
    private HistoryServiceImpl historyService;
    private HintsPartialSignatureHandler partialSignatureHandler;
    private TssBlockHashSigner blockHashSigner;

    @BeforeEach
    void setUp() {
        hintsContext = new HintsContext(library, () -> config, signingMetrics);
        partialSignatureHandler = new HintsPartialSignatureHandler(
                Duration.ofSeconds(2), signings, new ConcurrentHashMap<>(), hintsContext, rsaContext);

        lenient().when(hintsComponent.signingContext()).thenReturn(hintsContext);
        lenient().when(hintsComponent.signings()).thenReturn(signings);
        lenient().when(hintsComponent.submissions()).thenReturn(submissions);
        lenient()
                .when(submissions.submitPartialSignature(anyLong(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        final var hintsService = new HintsServiceImpl(hintsComponent, library);

        historyService = new HistoryServiceImpl(historyComponent);

        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        blockHashSigner = new TssBlockHashSigner(hintsService, historyService, configProvider);
    }

    @Test
    void peerPreviousConstructionSignatureCannotHijackPostHandoffBlockSigning() {
        givenHandoffInsideBlockTen();
        givenPreviousConstructionSignatureInPreHandle();

        // Now this node closes block 10 and signs its hash
        final var attempt = assertDoesNotThrow(() -> blockHashSigner.sign(POST_HANDOFF_BLOCK_HASH, SUCCINCT_SIGNATURE));

        // It must sign, prove, and submit under the active construction only
        assertEquals(NEW_VERIFICATION_KEY, attempt.verificationKey());
        assertEquals(NEW_CHAIN_OF_TRUST_PROOF, attempt.chainOfTrustProof());
        final var signing = signings.get(new SigningAttemptId(POST_HANDOFF_BLOCK_HASH, NEW_CONSTRUCTION_ID));
        assertSame(signing.future(), attempt.signatureFuture());
        verify(submissions).submitPartialSignature(NEW_CONSTRUCTION_ID, POST_HANDOFF_BLOCK_HASH);
        verify(submissions, never()).submitPartialSignature(OLD_CONSTRUCTION_ID, POST_HANDOFF_BLOCK_HASH);
    }

    @Test
    void activeConstructionSignaturesAreKeptAfterPeerPreviousConstructionSignature() {
        givenHandoffInsideBlockTen();
        givenPreviousConstructionSignatureInPreHandle();

        // Still before this node closes block 10, a majority-weight peer's active-construction partial
        // signature on the same block hash reaches pre-handle
        given(library.verifyBls(
                        CRS,
                        MAJORITY_PARTIAL_SIGNATURE,
                        POST_HANDOFF_BLOCK_HASH,
                        NEW_AGGREGATION_KEY,
                        MAJORITY_PARTY_ID))
                .willReturn(true);
        given(library.aggregateSignatures(
                        CRS,
                        NEW_AGGREGATION_KEY,
                        NEW_VERIFICATION_KEY,
                        Map.of(MAJORITY_PARTY_ID, MAJORITY_PARTIAL_SIGNATURE)))
                .willReturn(AGGREGATE_SIGNATURE);
        givenPreHandleOf(
                MAJORITY_NODE_ID,
                HintsPartialSignatureTransactionBody.newBuilder()
                        .constructionId(NEW_CONSTRUCTION_ID)
                        .message(POST_HANDOFF_BLOCK_HASH)
                        .partialSignature(MAJORITY_PARTIAL_SIGNATURE)
                        .build());
        assertDoesNotThrow(() -> partialSignatureHandler.preHandle(preHandleContext));

        final var attempt = assertDoesNotThrow(() -> blockHashSigner.sign(POST_HANDOFF_BLOCK_HASH, SUCCINCT_SIGNATURE));

        // The attempt this node signs with already holds the peer's signature
        assertEquals(NEW_VERIFICATION_KEY, attempt.verificationKey());
        assertEquals(AGGREGATE_SIGNATURE, attempt.signatureFuture().getNow(null));
    }

    private void givenHandoffInsideBlockTen() {
        // The node is signing under the old construction...
        hintsContext.setConstruction(
                construction(OLD_CONSTRUCTION_ID, OLD_AGGREGATION_KEY, OLD_VERIFICATION_KEY), CRS_STATE);
        hintsContext.onBlockStarted(10L);
        // ...and then, inside a round of block 10, hands off to the new construction and its history proof
        historyService.setLatestHistoryProof(HistoryProof.newBuilder()
                .targetHistory(History.newBuilder().metadata(NEW_VERIFICATION_KEY))
                .chainOfTrustProof(NEW_CHAIN_OF_TRUST_PROOF)
                .build());
        hintsContext.setConstruction(
                construction(NEW_CONSTRUCTION_ID, NEW_AGGREGATION_KEY, NEW_VERIFICATION_KEY), CRS_STATE);
        assertTrue(blockHashSigner.isReady());
    }

    private void givenPreviousConstructionSignatureInPreHandle() {
        // Before this node closes block 10, a peer's valid previous-construction partial signature on the
        // block hash reaches pre-handle
        given(library.verifyBls(
                        CRS,
                        MINORITY_PARTIAL_SIGNATURE,
                        POST_HANDOFF_BLOCK_HASH,
                        OLD_AGGREGATION_KEY,
                        MINORITY_PARTY_ID))
                .willReturn(true);
        givenPreHandleOf(
                MINORITY_NODE_ID,
                HintsPartialSignatureTransactionBody.newBuilder()
                        .constructionId(OLD_CONSTRUCTION_ID)
                        .message(POST_HANDOFF_BLOCK_HASH)
                        .partialSignature(MINORITY_PARTIAL_SIGNATURE)
                        .build());
        assertDoesNotThrow(() -> partialSignatureHandler.preHandle(preHandleContext));
    }

    private void givenPreHandleOf(final long creatorId, final HintsPartialSignatureTransactionBody op) {
        given(preHandleContext.body())
                .willReturn(
                        TransactionBody.newBuilder().hintsPartialSignature(op).build());
        given(preHandleContext.configuration()).willReturn(config);
        given(preHandleContext.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.nodeId()).willReturn(creatorId);
    }

    private static HintsConstruction construction(
            final long constructionId, final Bytes aggregationKey, final Bytes verificationKey) {
        return HintsConstruction.newBuilder()
                .constructionId(constructionId)
                .crsId(1L)
                .numParties(32)
                .hintsScheme(new HintsScheme(new PreprocessedKeys(aggregationKey, verificationKey), NODE_PARTY_IDS))
                .build();
    }

    private static byte[] aggregationKeyBytes() {
        final var bytes = new byte[49];
        bytes[0] = 1;
        return bytes;
    }
}
