// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRedactMessage;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.SOURCE_APP;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hederahashgraph.api.proto.java.ContractID;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Multi-network E2E coverage for CLPR message redaction. The source-side admin redacts
 * an outbound slot before the peer acknowledges it; the peer must observe a
 * {@code ClprRedactedMessage} payload in the next bundle and enqueue a {@code REDACTED}
 * reply, which advances the source's {@code ackedMessageId} via the standard reply path.
 *
 * <p>Timing note: the source-side sync timer ticks every
 * {@code ClprChannelManager.DEFAULT_SYNC_INTERVAL_MS} (1000 ms). This is a hardcoded
 * constant, not a configurable value. If the {@code clprRedactMessage} transaction
 * commits later than the next sync tick after {@code sendMessage}, the original payload
 * is shipped to the peer, the peer dispatches to the application, and the eventual
 * SUCCESS reply advances {@code ackedMessageId} first — at which point the redact would
 * fail with {@code CLPR_MESSAGE_ALREADY_ACKNOWLEDGED}. We assert SUCCESS on the redact
 * so the test fails loudly if it loses the race.
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroRedactionSuite extends HieroToHieroBase {

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Source admin redacts before peer ack → peer enqueues REDACTED reply, round-trip completes")
    Stream<DynamicTest> outboundRedactionBeforeAck(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        // Captured at deploy-time on A; re-registered into the final query block's spec registry
        // so contractCallLocalWithFunctionAbi(SOURCE_APP, ...) can resolve the ContractID. Each
        // networkHapiTest block gets a fresh spec/registry, so cross-block contract references
        // must be plumbed manually.
        final ContractID[] sourceAppIdOnA = new ContractID[1];
        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        networkHapiTest(
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        withOpContext((spec, opLog) -> {
                                            allRunFor(
                                                    spec,
                                                    contractCreate(
                                                            SOURCE_APP,
                                                            crypto.channelId,
                                                            crypto.connectorId,
                                                            new byte[20]));
                                            sourceAppIdOnA[0] = spec.registry().getContractId(SOURCE_APP);
                                        }),
                                        // SourceApplication stamps itself as the sender when calling the CLPR
                                        // precompile, so the source-side onClprResponse dispatch can find it
                                        // even when the outbound slot is redacted (see ClprRedactedMessage.sender).
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.ONE)
                                                .gas(GAS)
                                                .payingWith("callerA"),
                                        // payingWith(GENESIS) satisfies the
                                        // PrivilegesVerifier#checkClprAdmin gate (treasury / system-admin only).
                                        clprRedactMessage()
                                                .channelId(crypto.channelId)
                                                .messageId(1L)
                                                .payingWith(GENESIS)
                                                .hasKnownStatus(SUCCESS))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1),
                        // Post-fix, the REDACTED reply that arrives back on A dispatches
                        // onClprResponse to the preserved sender in ClprRedactedMessage — proven
                        // by SourceApplication.responseCount ticking up. Pre-fix (before the
                        // sender was preserved) the dispatch was skipped and responseCount
                        // stayed at 0.
                        assertRedactedCallbacksDelivered(ledgerA, sourceAppIdOnA, 1)));
    }

    /**
     * Pins the running-hash branch at {@code ClprSubmitBundleHandler} step 6: redacted-slot
     * adopt-then-fold must work when the redacted slot is in the MIDDLE of the bundle (stricter
     * than head/tail). Any error → CLPR_RUNNING_HASH_MISMATCH and B's receivedMessageId stalls.
     */
    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Multi-message bundle with redacted middle slot → all 3 slots ack, no hash mismatch")
    Stream<DynamicTest> multiMessageBundleWithRedactedMiddleSlot(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        final ContractID[] sourceAppIdOnA = new ContractID[1];
        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        networkHapiTest(
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(SOURCE_APP),
                                        withOpContext((spec, opLog) -> {
                                            allRunFor(
                                                    spec,
                                                    contractCreate(
                                                            SOURCE_APP,
                                                            crypto.channelId,
                                                            crypto.connectorId,
                                                            new byte[20]));
                                            sourceAppIdOnA[0] = spec.registry().getContractId(SOURCE_APP);
                                        }),
                                        // sendMessages(N) enqueues N slots in one consensus txn —
                                        // the redact lands before the first sync tick can ship the bundle.
                                        contractCall(SOURCE_APP, "sendMessages", BigInteger.valueOf(3))
                                                .gas(GAS)
                                                .payingWith("callerA"),
                                        clprRedactMessage()
                                                .channelId(crypto.channelId)
                                                .messageId(2L)
                                                .payingWith(GENESIS)
                                                .hasKnownStatus(SUCCESS))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 3),
                        awaitAckedMessage(ledgerA, crypto.channelId, 3),
                        // All 3 slots — including the redacted middle one — must round-trip a
                        // callback into SourceApplication. Pre-fix the redacted slot's callback
                        // was skipped, so responseCount stalled at 2. Post-fix it's 3.
                        assertRedactedCallbacksDelivered(ledgerA, sourceAppIdOnA, 3)));
    }

    /**
     * Asserts that {@code SourceApplication.responseCount()} on {@code network} equals
     * {@code expectedCount}. Used by the redaction tests to verify that the source-side
     * {@code onClprResponse} dispatch fires even when the originating slot was redacted — a
     * regression check for the {@code ClprRedactedMessage.sender} carryover that keeps the
     * callback path routable.
     */
    private static DynamicTest assertRedactedCallbacksDelivered(
            final SubProcessNetwork network, final ContractID[] sourceAppIdOnA, final int expectedCount) {
        return networkHapiTest(
                        "Assert onClprResponse callback delivered for every slot (incl. redacted) on " + network.name(),
                        network,
                        withOpContext((spec, opLog) -> {
                            spec.registry().saveContractId(SOURCE_APP, sourceAppIdOnA[0]);
                            final var abi = getABIFor(FUNCTION, "responseCount", SOURCE_APP);
                            allRunFor(
                                    spec,
                                    QueryVerbs.contractCallLocalWithFunctionAbi(SOURCE_APP, abi)
                                            .exposingTypedResultsTo(results -> {
                                                final var actual = (BigInteger) results[0];
                                                assertEquals(
                                                        BigInteger.valueOf(expectedCount),
                                                        actual,
                                                        "SourceApplication.responseCount=" + actual
                                                                + " but expected " + expectedCount
                                                                + " — the onClprResponse dispatch for at least one redacted slot never fired. "
                                                                + "Check ClprRedactedMessage.sender preservation in ClprRedactMessageHandler "
                                                                + "and the redacted-originating branch in ClprSubmitBundleHandler step 10.");
                                            }));
                        }))
                .findFirst()
                .orElseThrow();
    }
}
