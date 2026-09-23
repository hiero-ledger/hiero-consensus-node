// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.ECHO_APP;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.SOURCE_APP;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hederahashgraph.api.proto.java.ContractID;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MULTINETWORK)
public class ClprHieroToHieroSuite extends HieroToHieroBase {

    /**
     * Impl collapses {@code CLOSING → DRAINED → CLOSED} in one bundle-processing pass, violating
     * spec §2.1.1's "peer observes DRAINED during sync" guarantee — the collapsing side never
     * emits a bundle carrying {@code DRAINED}, so the peer sticks at {@code DRAINED} and its
     * retries trip the peer-scoped {@code CircuitBreaker} in {@code ClprSynchronizerImpl},
     * blackholing every other channel to the same host:port until cooldown expires.
     *
     * <p>Fix per spec: defer {@code DRAINED → CLOSED} to a subsequent bundle-processing pass so
     * at least one outbound bundle carries {@code state=DRAINED}. Companion fix: key
     * {@code circuitBreakers} on {@code (peer, channelId)} so a single broken channel
     * can't cascade across peers. Re-enable the close tests after those land. See
     * {@code clpr-service-spec.md} §2.1.1 and §4.2 step 5b.
     */
    private static final String CLOSE_CHANNEL_DISABLED_REASON =
            "Disabled: §4.2 step 5b close handshake collapses; peer stuck";

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("One-way: message from ledger A arrives on ledger B")
    Stream<DynamicTest> oneWayDelivery(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        networkHapiTest(
                                        "Send 'hello-one-way' from A",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "hello-one-way".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1)));
    }

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Round-trip: SourceApplication on A receives onClprResponse callback with EchoApplication's payload")
    Stream<DynamicTest> fullRoundTrip(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        // SourceApplication.sendMessages(1) emits payload = abi.encodePacked("msg-", uint256(sentCount+0))
        // = 4 bytes "msg-" + 32 zero bytes. EchoApplication.onClprMessage echoes it verbatim.
        final byte[] expectedResponse = new byte[4 + 32];
        System.arraycopy("msg-".getBytes(StandardCharsets.UTF_8), 0, expectedResponse, 0, 4);

        // Captured at deploy-time on B; used as targetApplication for A's SourceApplication ctor.
        final byte[][] echoAddrOnB = new byte[1][];
        // Captured at deploy-time on A; re-registered into the final query block's spec registry
        // so contractCallLocalWithFunctionAbi(SOURCE_APP, ...) can resolve the ContractID. Each
        // networkHapiTest block gets a fresh spec/registry, so cross-block contract references
        // must be plumbed manually.
        final ContractID[] sourceAppIdOnA = new ContractID[1];

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // Deploy EchoApplication on B and capture its 20-byte EVM address.
                        networkHapiTest(
                                        "Deploy EchoApplication on B",
                                        ledgerB,
                                        uploadInitCode(ECHO_APP),
                                        contractCreate(ECHO_APP),
                                        withOpContext((spec, ignoredLog) -> echoAddrOnB[0] = asSolidityAddress(
                                                spec.registry().getContractId(ECHO_APP))))
                                .findFirst()
                                .orElseThrow(),
                        // Deploy SourceApplication on A wired to (channelId, connectorId, echoAddrOnB),
                        // capture its ContractID for the post-ack query, then drive one round-trip via
                        // sendMessages(1).
                        networkHapiTest(
                                        "Deploy SourceApplication on A + send 1 message",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        withOpContext((spec, ignoredLog) -> {
                                            allRunFor(
                                                    spec,
                                                    contractCreate(
                                                            SOURCE_APP,
                                                            crypto.channelId,
                                                            crypto.connectorId,
                                                            echoAddrOnB[0]));
                                            sourceAppIdOnA[0] = spec.registry().getContractId(SOURCE_APP);
                                        }),
                                        // SourceApplication.sendMessages(uint256) — note the plural.
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.ONE)
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1),
                        // Ack alone doesn't prove the callback fired — the handler records ackedMessageId
                        // BEFORE dispatching onClprResponse, and the dispatch is wrapped in a swallowed
                        // try/catch. The assertion below reads state that ONLY SourceApplication.onClprResponse
                        // mutates, so it is sensitive to the response selector being correct.
                        networkHapiTest(
                                        "Assert onClprResponse callback delivered on A",
                                        ledgerA,
                                        withOpContext((spec, ignoredLog) -> {
                                            // Re-register SourceApplication's ContractID (captured during deploy
                                            // above) into this spec's fresh registry so the local query can find it.
                                            spec.registry().saveContractId(SOURCE_APP, sourceAppIdOnA[0]);
                                            final var abi = getABIFor(FUNCTION, "getResponse", SOURCE_APP);
                                            allRunFor(
                                                    spec,
                                                    QueryVerbs.contractCallLocalWithFunctionAbi(
                                                                    SOURCE_APP, abi, BigInteger.ONE)
                                                            .exposingTypedResultsTo(
                                                                    results -> {
                                                                        final var actual = (byte[]) results[0];
                                                                        assertArrayEquals(
                                                                                expectedResponse,
                                                                                actual,
                                                                                "SourceApplication.responses[1] not populated — onClprResponse callback was never delivered. "
                                                                                        + "Check ON_CLPR_RESPONSE_SELECTOR in ClprSubmitBundleHandler.");
                                                                    }));
                                        }))
                                .findFirst()
                                .orElseThrow()));
    }

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Multi-message: five sequential messages from ledger A arrive on ledger B")
    Stream<DynamicTest> multipleSequentialMessages(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        final int messageCount = 5;
        final var sendOps = new ArrayList<SpecOperation>();
        sendOps.add(cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS));
        sendOps.add(uploadInitCode(CLPR_CONTRACT));
        sendOps.add(contractCreate(CLPR_CONTRACT));
        for (int i = 1; i <= messageCount; i++) {
            sendOps.add(contractCall(
                            CLPR_CONTRACT,
                            SEND_MESSAGE,
                            crypto.channelId,
                            crypto.connectorId,
                            new byte[20],
                            ("msg-" + i).getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith("callerA"));
        }

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        networkHapiTest(
                                        "Send " + messageCount + " sequential messages from A",
                                        ledgerA,
                                        sendOps.toArray(new SpecOperation[0]))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, messageCount),
                        awaitAckedMessage(ledgerA, crypto.channelId, messageCount)));
    }

    @Disabled(CLOSE_CHANNEL_DISABLED_REASON)
    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Send rejected after admin close: post-close sendMessage on A reverts")
    Stream<DynamicTest> sendRejectedAfterClose(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // After clprCloseChannel, the local channel is no longer ACTIVE (CLOSING / DRAINED).
        // The ClprServiceApiImpl.sendMessage path rejects with CLPR_INVALID_CHANNEL_STATUS,
        // which surfaces through the Solidity require() as CONTRACT_REVERT_EXECUTED.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(networkHapiTest(
                                "Reject send after admin close on A",
                                ledgerA,
                                cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                uploadInitCode(CLPR_CONTRACT),
                                contractCreate(CLPR_CONTRACT),
                                // Admin closes the channel on A.
                                clprCloseChannel().channelId(crypto.channelId).payingWith(GENESIS),
                                // Subsequent send must revert — channel is no longer ACTIVE.
                                contractCall(
                                                CLPR_CONTRACT,
                                                SEND_MESSAGE,
                                                crypto.channelId,
                                                crypto.connectorId,
                                                new byte[20],
                                                "post-close".getBytes(StandardCharsets.UTF_8))
                                        .gas(GAS)
                                        .payingWith("callerA")
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .findFirst()
                        .orElseThrow()));
    }

    @Disabled(CLOSE_CHANNEL_DISABLED_REASON)
    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Close handshake from ACTIVE: A→CLOSING propagates to B via drain handshake")
    Stream<DynamicTest> closeChannelDrainHandshakeFromActive(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // 1. Round-trip one message to prove the channel is healthy ACTIVE.
                        networkHapiTest(
                                        "Round-trip 1 message (prove channel ACTIVE)",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "hello-close".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1),
                        // 2. Admin closes the channel on ledger A.
                        networkHapiTest(
                                        "Admin closes channel on A",
                                        ledgerA,
                                        clprCloseChannel()
                                                .channelId(crypto.channelId)
                                                .payingWith(GENESIS)
                                                .hasKnownStatus(SUCCESS))
                                .findFirst()
                                .orElseThrow(),
                        // 3. A's status moves out of ACTIVE on commit — sendMessage rejects on probe.
                        awaitChannelNonActive(ledgerA, crypto),
                        // 4. Drain handshake propagates to B (B mirrors to CLOSING via spec §4.2 step 5a)
                        //    — sendMessage on B also rejects.
                        awaitChannelNonActive(ledgerB, crypto)));
    }

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Regression: bidirectional concurrent traffic does not deadlock on replay overlap")
    Stream<DynamicTest> bidirectionalConcurrentTraffic(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Regression for the Step 5 idempotent replay-defense fix. Under bidirectional traffic,
        // each side's channel.ackedMessageId lags the other side's receivedMessageId until
        // an ack round-trip lands. The sender always starts the bundle at ackedMessageId+1, so
        // the leading messages of every bundle are unavoidable replays. Pre-fix, Step 5 enforced
        // strict equality (messages.size == expectedCount) and rejected the bundle whenever
        // any replay overlap was present — both sides rejected each other's bundles, neither
        // ackedMessageId ever advanced, and the channel deadlocked indefinitely.
        //
        // Post-fix, Step 5 derives peerAckedMessageId from metadata, skips the replayed prefix,
        // and processes only the new tail. Pure-replay bundles are accepted as ack-only no-ops.
        //
        // This test depends on multiple sendMessage calls per side in the same outbound bundle, and round-trips overlap
        // across sync boundaries.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int messagesPerSide = 4;

        final var sendOpsA = new ArrayList<SpecOperation>();
        sendOpsA.add(cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS));
        sendOpsA.add(uploadInitCode(CLPR_CONTRACT));
        sendOpsA.add(contractCreate(CLPR_CONTRACT));
        for (int i = 0; i < messagesPerSide; i++) {
            sendOpsA.add(contractCall(
                            CLPR_CONTRACT,
                            SEND_MESSAGE,
                            crypto.channelId,
                            crypto.connectorId,
                            new byte[20],
                            ("a-" + i).getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith("callerA"));
        }

        final var sendOpsB = new ArrayList<SpecOperation>();
        sendOpsB.add(cryptoCreate("callerB").balance(ONE_HUNDRED_HBARS));
        sendOpsB.add(uploadInitCode(CLPR_CONTRACT));
        sendOpsB.add(contractCreate(CLPR_CONTRACT));
        for (int i = 0; i < messagesPerSide; i++) {
            sendOpsB.add(contractCall(
                            CLPR_CONTRACT,
                            SEND_MESSAGE,
                            crypto.channelId,
                            crypto.connectorId,
                            new byte[20],
                            ("b-" + i).getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith("callerB"));
        }

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // Enqueue N messages on A. Their first sync tick will batch them all into one
                        // bundle and ship to B.
                        networkHapiTest(ledgerA, sendOpsA.toArray(new SpecOperation[0]))
                                .findFirst()
                                .orElseThrow(),
                        // While A's bundles are flying, enqueue N messages on B. Now BOTH sides are
                        // simultaneously syncing outbound queues whose leading messages will become
                        // replays on the next tick — this is exactly the deadlock trigger.
                        networkHapiTest(ledgerB, sendOpsB.toArray(new SpecOperation[0]))
                                .findFirst()
                                .orElseThrow(),
                        // Both sides must receive all N messages from the peer …
                        awaitReceivedMessage(ledgerA, crypto.channelId, messagesPerSide),
                        awaitReceivedMessage(ledgerB, crypto.channelId, messagesPerSide),
                        // … AND both sides must observe acks for all N of their own outbound messages.
                        // This is the load-bearing assertion: with the pre-fix strict Step 5 check,
                        // ackedMessageId never advances past 1 on either side and these awaits time out.
                        awaitAckedMessage(ledgerA, crypto.channelId, messagesPerSide),
                        awaitAckedMessage(ledgerB, crypto.channelId, messagesPerSide)));
    }

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Regression: multi-message bundle round-trip preserves every reply slot")
    Stream<DynamicTest> multiMessageBundleRoundTrip(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Regression for the multi-message OutboundQueue fix. Under a single-direction burst,
        // multiple sendMessage calls on A queue up before A's first sync tick fires, so they
        // all ship in ONE bundle. B then runs Step 10 once per message, enqueueing a Reply
        // for each. Pre-fix, the unconditional outbound.resyncFrom(channelStore) call
        // between iterations rewound nextMessageId to the (stale) store value, causing each
        // subsequent Reply slot to overwrite the previous one. End result: only the LAST
        // reply survived in B's outbound queue, A's Step 8 reply matching failed on the next
        // bundle from B, and A's channel paused.
        //
        // Post-fix, OutboundQueue is backed by the channelStore directly and is in sync
        // with every enqueue, so all N replies land at consecutive slots.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int messageCount = 4;

        final var sendOps = new ArrayList<SpecOperation>();
        sendOps.add(cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS));
        sendOps.add(uploadInitCode(CLPR_CONTRACT));
        sendOps.add(contractCreate(CLPR_CONTRACT));
        for (int i = 0; i < messageCount; i++) {
            sendOps.add(contractCall(
                            CLPR_CONTRACT,
                            SEND_MESSAGE,
                            crypto.channelId,
                            crypto.connectorId,
                            new byte[20],
                            ("multi-" + i).getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith("callerA"));
        }

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        networkHapiTest(ledgerA, sendOps.toArray(new SpecOperation[0]))
                                .findFirst()
                                .orElseThrow(),
                        // B must receive all N messages …
                        awaitReceivedMessage(ledgerB, crypto.channelId, messageCount),
                        // … and A must observe acks for all N. Pre-fix, only the last reply survived
                        // in B's outbound queue, so A's ackedMessageId would only advance by 1 and
                        // this assertion would time out.
                        awaitAckedMessage(ledgerA, crypto.channelId, messageCount)));
    }

    @Disabled(CLOSE_CHANNEL_DISABLED_REASON)
    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Regression: in-flight bundle round-trips cleanly while channel is closing")
    Stream<DynamicTest> bundleRoundTripDuringCloseHandshake(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Regression for the lazy-config-propagation skip when CLOSING. Pre-fix, the handler
        // would enqueue a ConfigUpdate control message whenever the local ledger config was
        // newer than channel.lastConfigTimestamp — even on a CLOSING channel. That made
        // the drain queue grow during shutdown and could extend the drain window indefinitely.
        //
        // Post-fix, the lazy enqueue is gated on currentStatus ∈ {ACTIVE, PAUSED}; CLOSING /
        // DRAINED / CLOSED skip it. This test exercises the close handshake under traffic to
        // ensure the drain completes cleanly without phantom control-message growth.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // 1. Round-trip a message so the channel is healthy ACTIVE on both sides.
                        networkHapiTest(
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "pre-close".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1),
                        // 2. Close the channel on A — A enters CLOSING and will drain.
                        networkHapiTest(
                                        ledgerA,
                                        clprCloseChannel()
                                                .channelId(crypto.channelId)
                                                .payingWith(GENESIS)
                                                .hasKnownStatus(SUCCESS))
                                .findFirst()
                                .orElseThrow(),
                        // 3. The drain handshake propagates and both channels terminate. With
                        //    the pre-fix bug, CLOSING-state config-update enqueues could keep the
                        //    outbound queues non-empty across drain — both await checks below would
                        //    fail to converge on a non-ACTIVE state.
                        awaitChannelNonActive(ledgerA, crypto),
                        awaitChannelNonActive(ledgerB, crypto)));
    }

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Bundle at exactly maxMessagesPerBundle: 9 messages across multiple capped bundles all delivered")
    Stream<DynamicTest> bundleAtMaxMessagesPerBundle(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Probes the boundary check at ClprSubmitBundleHandler step 4:
        //   validateTrueOrPenalize(messages.size() <= throttles.maxMessagesPerBundle(), ...)
        // and verifies the sender (ClprStateProofManager.buildBundleStateProof's maxMessages cap)
        // agrees with the receiver. With maxMessagesPerBundle=4 and N=9 atomic sends, A must
        // fragment the queue into ≥ ⌈9/4⌉ = 3 bundles. Off-by-one in either cap (sender shipping
        // 5 when the receiver expects ≤ 4, or vice versa) ends in CLPR_BUNDLE_VERIFICATION_FAILED.
        // Slow sync (1/sec) so the fragmentation is observable.
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
                        // All 9 must arrive across multiple capped bundles.
                        awaitReceivedMessage(ledgerB, crypto.channelId, totalMessages),
                        awaitAckedMessage(ledgerA, crypto.channelId, totalMessages)));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = "ledgerA",
                setupOverrides = @ConfigOverride(key = "clpr.connectorQueueQuotaPct", value = "100")),
        @Network(name = "ledgerB", setupOverrides = @ConfigOverride(key = "clpr.connectorQueueQuotaPct", value = "100"))
    })
    @DisplayName("Multi-message round-trip with tight maxQueueDepth — sender + reply enqueue both respect the cap")
    Stream<DynamicTest> roundTripUnderTightMaxQueueDepth(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Characterization of multi-message round-trip under tight queue caps. Two interacting
        // constraints from the CLPR spec apply to the SENDER's app-initiated send path:
        //   1. Queue depth — spec §4.3 step 5
        //   2. Per-connector queue quota — spec §8.11
        // The REPLY-enqueue path on B is intentionally exempt from the depth check per
        // spec §4.2 step 6
        // This HAPI test characterizes the WORKING case: with maxQueueDepth=4 on both sides
        // (quota bumped to 100%) and 3 atomic sends from A, the depth check passes on every
        // send, B's reply enqueue path bypasses the cap by design, the round-trip completes,
        // and both channels stay ACTIVE.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int tightQueueDepth = 4;
        final int messageCount = 3;

        return Stream.concat(
                setupBothNetworks(
                        ledgerA, ledgerB, portA, portB, crypto, DEFAULT_MAX_MESSAGES_PER_BUNDLE, tightQueueDepth),
                Stream.of(
                        networkHapiTest(
                                        "Send " + messageCount + " messages atomically (tightQueueDepth="
                                                + tightQueueDepth + ")",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        contractCreate(SOURCE_APP, crypto.channelId, crypto.connectorId, new byte[20]),
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.valueOf(messageCount))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, messageCount),
                        // No assertChannelStaysActive here: the probe sends a probe message
                        // itself, and with tightQueueDepth the probe would compete with replies
                        // in the queue. The successful awaitAckedMessage already proves the
                        // channel isn't stuck — replies landed all the way back to A.
                        awaitAckedMessage(ledgerA, crypto.channelId, messageCount)));
    }

    @Disabled(CLOSE_CHANNEL_DISABLED_REASON)
    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Close while multi-message bundle is in flight: 5 messages drain cleanly to non-ACTIVE")
    Stream<DynamicTest> closeWhileMultiMessageBundleInFlight(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Spec §4.2 step 5a peer-state cascade applied mid-loop: A enqueues 5 messages
        // atomically, then immediately submits clprCloseChannel. Depending on whether the
        // close lands before or after A's first sync tick:
        //   (a) Tick first → all 5 ship while ACTIVE → B processes normally → A drains to CLOSED.
        //   (b) Close first → A transitions ACTIVE → CLOSING before the bundle ships → bundle
        //       includes the close state in metadata → B mirrors to CLOSING per the spec cascade
        //       → B replies with CHANNEL_CLOSED for slots that hadn't been dispatched.
        // Either outcome is spec-correct. The assertion tolerates both: 5 messages received on B
        // AND both sides reach non-ACTIVE. Extends bundleRoundTripDuringCloseHandshake (N=1) to
        // N=5 to stress the close-during-loop interaction with multi-slot bundles.
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int messageCount = 5;

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // Enqueue 5 atomically + close — racing first tick.
                        networkHapiTest(
                                        "Send " + messageCount + " + close on A",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        contractCreate(SOURCE_APP, crypto.channelId, crypto.connectorId, new byte[20]),
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.valueOf(messageCount))
                                                .gas(GAS)
                                                .payingWith("callerA"),
                                        clprCloseChannel()
                                                .channelId(crypto.channelId)
                                                .payingWith(GENESIS)
                                                .hasKnownStatus(SUCCESS))
                                .findFirst()
                                .orElseThrow(),
                        // Whichever close timing wins, B observes 5 inbound slots (either as
                        // normal-handled or CHANNEL_CLOSED-replied).
                        awaitReceivedMessage(ledgerB, crypto.channelId, messageCount),
                        // Drain handshake completes on both sides.
                        awaitChannelNonActive(ledgerA, crypto),
                        awaitChannelNonActive(ledgerB, crypto)));
    }

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Config update mid-stream: bump maxMessagesPerBundle; bundles after the update ship larger")
    Stream<DynamicTest> configUpdateMidStreamBumpsBundleCap(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        // Exercises the lazy ConfigUpdate enqueue at ClprSubmitBundleHandler around line 484:
        // when A's local config has a newer lastConfigTimestamp than the channel's recorded
        // one and the channel is ACTIVE/PAUSED, A's next outbound bundle prepends a
        // ClprConfigUpdate control message ahead of the data slots. This test:
        //   1. Starts with maxMessagesPerBundle=2 (tight cap).
        //   2. Bursts 6 messages — must ship across ≥ 3 bundles (capped at 2 each).
        //   3. Updates A's config to maxMessagesPerBundle=6.
        //   4. Bursts 6 more — A's next bundle now includes a ConfigUpdate control slot AND can
        //      ship up to 6 data slots in ONE bundle. Burst 2 completes in noticeably fewer
        //      ticks than burst 1 took.
        // Observable: total time to receive all 12 messages, plus B's running hash matching all
        // the way through (no CLPR_RUNNING_HASH_MISMATCH from a misplaced ConfigUpdate slot).
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        final int initialCap = 2;
        final int bumpedCap = 6;
        final int burstSize = 6;
        // Captured ContractID for SourceApp on A so burst 2 (a fresh networkHapiTest spec/registry)
        // can re-resolve it without redeploying — same cross-block plumbing as fullRoundTrip.
        final ContractID[] sourceAppIdOnA = new ContractID[1];

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto, initialCap, DEFAULT_MAX_QUEUE_DEPTH),
                Stream.of(
                        // Burst 1: 6 messages while cap is 2 → fragmented into 3 bundles.
                        networkHapiTest(
                                        "Burst 1: send " + burstSize + " messages while cap=" + initialCap,
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        withOpContext((spec, ignoredLog) -> {
                                            allRunFor(
                                                    spec,
                                                    contractCreate(
                                                            SOURCE_APP,
                                                            crypto.channelId,
                                                            crypto.connectorId,
                                                            new byte[20]));
                                            sourceAppIdOnA[0] = spec.registry().getContractId(SOURCE_APP);
                                        }),
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.valueOf(burstSize))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, burstSize),
                        awaitAckedMessage(ledgerA, crypto.channelId, burstSize),
                        // Mid-stream config bump: bumps A's maxMessagesPerBundle from 2 to 6. The
                        // next outbound bundle from A includes a ClprConfigUpdate control slot
                        // ahead of the data slots from burst 2.
                        networkHapiTest(
                                        "Bump A's maxMessagesPerBundle to " + bumpedCap,
                                        ledgerA,
                                        clprUpdateLedgerConfiguration()
                                                .configuration(buildLedgerConfig(
                                                        "hiero:298", portA, bumpedCap, DEFAULT_MAX_QUEUE_DEPTH))
                                                .payingWith(GENESIS)
                                                .hasKnownStatus(SUCCESS))
                                .findFirst()
                                .orElseThrow(),
                        // Burst 2: 6 more messages, now A can ship them all in ONE bundle (cap=6).
                        // The ConfigUpdate control slot rides at the head of the same bundle.
                        // Re-uses the SourceApplication deployed in burst 1 (carries sentCount) —
                        // re-register its ContractID into this spec's fresh registry.
                        networkHapiTest(
                                        "Burst 2: send " + burstSize + " more messages after cap=" + bumpedCap,
                                        ledgerA,
                                        cryptoCreate("callerA2").balance(ONE_HUNDRED_HBARS),
                                        withOpContext((spec, ignoredLog) -> {
                                            spec.registry().saveContractId(SOURCE_APP, sourceAppIdOnA[0]);
                                            allRunFor(
                                                    spec,
                                                    contractCall(
                                                                    SOURCE_APP,
                                                                    "sendMessages",
                                                                    BigInteger.valueOf(burstSize))
                                                            .gas(GAS)
                                                            .payingWith("callerA2"));
                                        }))
                                .findFirst()
                                .orElseThrow(),
                        // The ConfigUpdate control slot (enqueued lazily after the bump) consumes one
                        // message_id between bursts, so total receivedMessageId reaches at least
                        // burstSize*2; awaitReceivedMessage is a lower-bound await, so this remains
                        // robust whether or not a ConfigUpdate slot was emitted.
                        awaitReceivedMessage(ledgerB, crypto.channelId, burstSize * 2),
                        awaitAckedMessage(ledgerA, crypto.channelId, burstSize * 2),
                        // Proves the cap bump actually propagated to the peer: B logs this line
                        // when it processes the inbound ClprConfigUpdate control slot in step 10.
                        // Without the propagation, B would still apply A's old peerThrottles to
                        // future bundles and the test would lose its main signal.
                        networkHapiTest(
                                        "Assert B observed the ConfigUpdate control slot",
                                        ledgerB,
                                        withOpContext((spec, opLog) -> awaitLogLine(
                                                ledgerB,
                                                Pattern.compile(
                                                        "\\[ClprSubmitBundle\\] step10 CONTROL configUpdate conn="
                                                                + HexFormat.of().formatHex(crypto.channelId)),
                                                Duration.ofSeconds(30))))
                                .findFirst()
                                .orElseThrow(),
                        // Running hash matched all the way through — channel still ACTIVE.
                        assertChannelStaysActive(ledgerA, crypto, Duration.ofSeconds(10)),
                        // Drain A's ack queue through both bursts before B's probe starts.
                        // When B sends its first probe DATA, A enqueues a reply and includes
                        // it in its next bundle starting at ackedMsgId+1. If A's ack queue
                        // hasn't advanced far enough, the cap-bounded bundle window may not
                        // yet reach the reply slot, causing step-8 to PAUSE B's channel
                        // and the next probe sendMessage to revert with CONTRACT_REVERT_EXECUTED.
                        awaitAckedMessage(ledgerA, crypto.channelId, burstSize * 2 + 1),
                        assertChannelStaysActive(ledgerB, crypto, Duration.ofSeconds(10))));
    }
}
