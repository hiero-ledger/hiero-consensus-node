// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks that each network's upgrade throttles leave room in one node's share of the ingest
 * {@code CreationLimits} bucket for both operations an HTS transfer to a new alias implies: a
 * {@code CryptoCreate} for the implicit creation and a {@code TokenAssociateToAccount} for the auto-association.
 */
class HtsAutoCreationIngestThrottleTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L);
    private static final AccountID PAYER =
            AccountID.newBuilder().accountNum(1_234L).build();
    // A 20-byte EVM address no account uses yet, so a transfer to it implicitly creates the account
    private static final AccountID NEW_ALIAS =
            AccountID.newBuilder().alias(Bytes.wrap("abcdeabcdeabcdeabcde")).build();

    /**
     * {@code CreationLimits} runs at 300 logical ops/s (the LCM of its groups' 3, 5 and 100 ops/s), so a
     * {@code CryptoCreate} takes 100 units and an association 3. A node's share is {@code floor(300_000 / N)}
     * milli-ops/s over the 15s burst, which holds the 103 units both need only while {@code N <= 43}.
     */
    private static final int MAX_NODES_WITH_ROOM = 43;

    private static final String BOUNDARY_MOVED =
            "CreationLimits changed; recompute MAX_NODES_WITH_ROOM as its Javadoc describes";

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void thirtyNodesHaveRoomForCreationAndAssociation(final String network) throws IOException, ParseException {
        final var subject = ingestThrottleFor(throttlesFile(network), 30);

        assertFalse(subject.shouldThrottleNOfUnscaled(1, CRYPTO_CREATE, NOW));
        assertFalse(
                subject.shouldThrottleNOfUnscaled(1, TOKEN_ASSOCIATE_TO_ACCOUNT, NOW),
                "With 30 nodes, a node's CreationLimits share must hold a CryptoCreate plus an association");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void largestNetworkStillHasRoomForCreationAndAssociation(final String network) throws IOException, ParseException {
        final var subject = ingestThrottleFor(throttlesFile(network), MAX_NODES_WITH_ROOM);

        assertFalse(subject.shouldThrottleNOfUnscaled(1, CRYPTO_CREATE, NOW));
        assertFalse(subject.shouldThrottleNOfUnscaled(1, TOKEN_ASSOCIATE_TO_ACCOUNT, NOW), BOUNDARY_MOVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void oneNodeMoreThrottlesAssociationAfterCreation(final String network) throws IOException, ParseException {
        final var subject = ingestThrottleFor(throttlesFile(network), MAX_NODES_WITH_ROOM + 1);

        assertFalse(subject.shouldThrottleNOfUnscaled(1, CRYPTO_CREATE, NOW));
        // Ingest rejects the transaction with BUSY
        assertTrue(subject.shouldThrottleNOfUnscaled(1, TOKEN_ASSOCIATE_TO_ACCOUNT, NOW), BOUNDARY_MOVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void htsTransferToNewAliasPassesIngestRightAfterAnAssociation(final String network)
            throws IOException, ParseException {
        final var subject = ingestThrottleFor(throttlesFile(network), 30);

        // Any association submitted to this node, e.g. by another user, uses CreationLimits capacity first
        assertFalse(subject.shouldThrottleNOfUnscaled(1, TOKEN_ASSOCIATE_TO_ACCOUNT, NOW));
        assertFalse(subject.checkAndEnforceThrottle(
                htsTransferToNewAlias(), NOW, stateWithNoAliasesOrTokenRels(), null, false));
    }

    private static TransactionInfo htsTransferToNewAlias() {
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER).build())
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(TokenID.newBuilder().tokenNum(5_678L).build())
                                .transfers(List.of(
                                        AccountAmount.newBuilder()
                                                .accountID(PAYER)
                                                .amount(-1)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(NEW_ALIAS)
                                                .amount(1)
                                                .build()))
                                .build())
                        .build())
                .build();
        return new TransactionInfo(
                SignedTransaction.DEFAULT, body, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER, null);
    }

    @SuppressWarnings("unchecked")
    private static State stateWithNoAliasesOrTokenRels() {
        // Every lookup misses, so the alias is unused and the new account has no token relation
        final ReadableKVState<Object, Object> misses = mock(ReadableKVState.class);
        final var readableStates = mock(ReadableStates.class);
        given(readableStates.get(anyInt())).willReturn(misses);
        final var state = mock(State.class);
        given(state.getReadableStates(any())).willReturn(readableStates);
        return state;
    }

    private static ThrottleAccumulator ingestThrottleFor(final Path throttlesFile, final int numNodes)
            throws IOException, ParseException {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        // Like the ingest throttle, give this node 1/N of each bucket's capacity
        final var subject = new ThrottleAccumulator(() -> config, () -> numNodes, FRONTEND_THROTTLE);
        subject.applyGasConfig();
        final var defs = new ObjectMapper()
                .readValue(
                        throttlesFile.toFile(),
                        com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions.class);
        subject.rebuildFor(ThrottleDefinitions.PROTOBUF.parse(
                Bytes.wrap(defs.toProto().toByteArray()).toReadableSequentialData()));
        return subject;
    }

    private static Path throttlesFile(final String network) {
        // build.gradle.kts declares these files as test inputs
        return findUpward(Path.of("hedera-node", "configuration", network, "upgrade", "throttles.json"));
    }

    private static Path findUpward(final Path relative) {
        // Search from the working dir up, so the files resolve however the test is launched
        final var workingDir = Path.of("").toAbsolutePath();
        for (var dir = workingDir; dir != null; dir = dir.getParent()) {
            final var candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No " + relative + " in or above " + workingDir);
    }
}
