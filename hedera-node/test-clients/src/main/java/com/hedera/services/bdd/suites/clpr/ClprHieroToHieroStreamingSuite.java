// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.SOURCE_APP;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Hiero-to-Hiero delivery over the streaming sync protocol ({@code clpr.streamingSyncEnabled=true}).
 *
 * <p>{@link ClprSyncRouterTest} already covers routing on the {@code streamingSyncEnabled} flag in
 * isolation, so these suites don't re-verify the switch — the disabled-flag smoke test below is enough to show the flag
 * is actually read end-to-end.
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroStreamingSuite extends HieroToHieroBase {

    private static final String STREAMING_SYNC_ENABLED = "clpr.streamingSyncEnabled";

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA", setupOverrides = @ConfigOverride(key = STREAMING_SYNC_ENABLED, value = "true")),
        @Network(name = "ledgerB", setupOverrides = @ConfigOverride(key = STREAMING_SYNC_ENABLED, value = "true"))
    })
    @DisplayName("given streaming sync on both ledgers, then messages sent both ways are delivered and acked")
    Stream<DynamicTest> givenStreamingSyncOnBothLedgers_thenBidirectionalMessagesAreDeliveredAndAcked(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // The worked example of the two-phase design: 3 messages queued on A and 2 on B, all in one bundle per side.
        // Each side's ack of the other lags a cycle, so every cycle after the first builds from the peer's live
        // received_message_id, which is above the local acked_message_id.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int messagesFromA = 3;
        final int messagesFromB = 2;
        final int maxMessagesPerBundle = 5;

        return Stream.concat(
                setupBothNetworks(
                        ledgerA, ledgerB, portA, portB, crypto, maxMessagesPerBundle, DEFAULT_MAX_QUEUE_DEPTH),
                Stream.of(
                        networkHapiTest(ledgerA, sendMessages("callerA", "a-", messagesFromA, crypto))
                                .findFirst()
                                .orElseThrow(),
                        networkHapiTest(ledgerB, sendMessages("callerB", "b-", messagesFromB, crypto))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, messagesFromA),
                        awaitReceivedMessage(ledgerA, crypto.channelId, messagesFromB),
                        awaitAckedMessage(ledgerA, crypto.channelId, messagesFromA),
                        awaitAckedMessage(ledgerB, crypto.channelId, messagesFromB)));
    }

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA", setupOverrides = @ConfigOverride(key = STREAMING_SYNC_ENABLED, value = "true")),
        @Network(name = "ledgerB", setupOverrides = @ConfigOverride(key = STREAMING_SYNC_ENABLED, value = "true"))
    })
    @DisplayName("given a backlog larger than one bundle, then it drains over several streaming cycles")
    Stream<DynamicTest> givenBacklogLargerThanOneBundle_thenItDrainsOverSeveralStreamingCycles(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // With maxMessagesPerBundle=4 and 9 queued messages, each cycle carries at most 4 (one bundle per side per
        // cycle). Every bundle after the first starts where the peer's received_message_id stands, not at A's
        // acked_message_id + 1.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int capPerBundle = 4;
        final int totalMessages = 9;

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto, capPerBundle, DEFAULT_MAX_QUEUE_DEPTH),
                Stream.of(
                        networkHapiTest(
                                        "Send " + totalMessages + " messages atomically",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        contractCreate(SOURCE_APP, crypto.channelId, crypto.connectorId, new byte[20]),
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.valueOf(totalMessages))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, totalMessages),
                        awaitAckedMessage(ledgerA, crypto.channelId, totalMessages)));
    }

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA", setupOverrides = @ConfigOverride(key = STREAMING_SYNC_ENABLED, value = "false")),
        @Network(name = "ledgerB", setupOverrides = @ConfigOverride(key = STREAMING_SYNC_ENABLED, value = "false"))
    })
    @DisplayName("given streaming sync disabled, then a message is still delivered and acked over unary sync")
    Stream<DynamicTest> givenStreamingSyncDisabled_thenMessageIsDeliveredAndAckedOverUnarySync(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Smoke test: proves ClprSyncRouter reads the flag correctly end-to-end when it is off.
        // ClprSyncRouterTest already covers the routing logic itself in isolation.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int messagesFromA = 1;

        return Stream.concat(
                setupBothNetworks(
                        ledgerA,
                        ledgerB,
                        portA,
                        portB,
                        crypto,
                        DEFAULT_MAX_MESSAGES_PER_BUNDLE,
                        DEFAULT_MAX_QUEUE_DEPTH),
                Stream.of(
                        networkHapiTest(ledgerA, sendMessages("callerA", "a-", messagesFromA, crypto))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, messagesFromA),
                        awaitAckedMessage(ledgerA, crypto.channelId, messagesFromA)));
    }

    private static SpecOperation[] sendMessages(
            final String caller, final String payloadPrefix, final int count, final ClprCrypto crypto) {
        final var ops = new ArrayList<SpecOperation>();
        ops.add(cryptoCreate(caller).balance(ONE_HUNDRED_HBARS));
        ops.add(uploadInitCode(CLPR_CONTRACT));
        ops.add(contractCreate(CLPR_CONTRACT));
        for (int i = 0; i < count; i++) {
            ops.add(contractCall(
                            CLPR_CONTRACT,
                            SEND_MESSAGE,
                            crypto.channelId,
                            crypto.connectorId,
                            new byte[20],
                            (payloadPrefix + i).getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith(caller));
        }
        return ops.toArray(new SpecOperation[0]);
    }
}
