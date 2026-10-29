// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.captureConfigProof;
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.captureManifestProof;
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.multiNetworkHapiTest;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CHANNEL_ID;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CHANNEL_SECRET_KEY;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CONNECTOR_SALT;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CONNECTOR_SECRET_KEY;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.computeCommitment;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.deriveConnectorId;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.deriveEcdsaPublicKey;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.signChannelId;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.signConnectorMessage;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.MultiNetworkLeakyHapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Two-network CLPR multi-message round-trip test.
 *
 * <p>Deploys {@code SourceApplication} on NET_A and {@code EchoApplication} on NET_B.
 * SourceApplication sends 7 messages per invocation (3 invocations). The bundle size limit
 * of 3 means up to 3 bundles are needed per invocation. EchoApplication on NET_B echoes
 * each message back as a response. All cross-network bundle exchange is fully automated
 * via {@code ClprChannelManager} auto-sync — no manual {@code clprSubmitBundle}.
 *
 * <p>Partial-bundle support: {@code ClprQueueMetadata.next_message_id} is batch-specific
 * (last message in this bundle + 1, not the channel total), enabling partial batching within
 * the {@code maxMessagesPerBundle} limit.
 *
 * <p>Network assignments:
 * <ul>
 *   <li><b>NET_A</b>: hosts {@code SourceApplication} (sender; also serves as the connector contract on
 *       this network).
 *   <li><b>NET_B</b>: hosts {@code EchoApplication} (echo target + connector contract).
 * </ul>
 *
 * <p>Both channels use the CLPR system contract's native verifier ({@code 0x16e}), which runs real TSS +
 * Merkle verification. Each channel is completed with the peer's TSS-signed config and endpoint-manifest
 * StateProofs, so both proofs are captured from each network before either channel is completed. Each
 * channel then dials the peer endpoints in its cached, proven {@code ClprEndpointManifest}.
 */
@Tag(TestTags.MULTINETWORK)
@MultiNetworkLeakyHapiTest
public class ClprMessagesSuite {

    private static final String NET_A_CHAIN_ID = "hiero:msgs-a";
    private static final String NET_B_CHAIN_ID = "hiero:msgs-b";

    /**
     * CLPR system contract (precompile) ID. Used as both networks' verifier so each channel exercises
     * the precompile's native {@code verifyConfig(bytes,bytes32,bytes)} method, which runs real TSS + Merkle
     * verification (see {@code VerifyConfigCall}).
     */
    private static final long CLPR_SYSTEM_CONTRACT_NUM = 0x16eL;

    /** Solidity echo application deployed on NET_B only. */
    public static final String ECHO_APP = "EchoApplication";

    /** Solidity source application deployed on NET_A; sends messages and tracks responses. */
    public static final String SOURCE_APP = "SourceApplication";

    /**
     * Solidity connector-auth contract deployed on NET_A. Implements
     * {@code authorizeOutboundMessage(bytes32,bytes,bytes,bytes)} returning {@code true}
     * — required because {@link #SOURCE_APP} doesn't implement that selector and the
     * precompile's {@code SendMessageCall} reverts with AUTHORIZATION_FAILED otherwise.
     */
    private static final String CONNECTOR_AUTH = "PassThroughAuth";

    /** Minimum locked stake for connector registration. */
    private static final long MIN_STAKE = 100L;

    /** Gas budget for each message-sending contract call. */
    private static final long GAS = 2_000_000L;

    /** Number of messages SourceApplication sends per invocation. */
    private static final int MESSAGES_PER_INVOCATION = 7;

    /** Number of invocation rounds. */
    private static final int INVOCATION_COUNT = 3;

    /** How long to poll for responses before failing. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);

    /** Polling interval between responseCount queries. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    /** Bundle size limit installed on both networks; also checked by the config-proof freshness gate. */
    private static final int MAX_MESSAGES_PER_BUNDLE = 3;

    /** Queue depth limit installed on both networks; also checked by the config-proof freshness gate. */
    private static final int MAX_QUEUE_DEPTH = 1000;

    @MultiNetworkHapiTest({@Network("ledgerA"), @Network("ledgerB")})
    @DisplayName("Multi-message CLPR: 3×7 messages with EchoApplication auto-sync round-trip")
    Stream<DynamicTest> multiMessageRoundTrip(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        // ── Channel keypair ────────────────────────────────────────────────────────────────────
        final var connPubKey64 = deriveEcdsaPublicKey(CHANNEL_SECRET_KEY);
        final var connPubKeyBytes = Bytes.wrap(connPubKey64);
        final var channelCommitment = computeCommitment(CHANNEL_ID, connPubKeyBytes);
        final var channelSig = signChannelId(CHANNEL_SECRET_KEY, CHANNEL_ID);

        // ── Connector keypair ─────────────────────────────────────────────────────────────────────
        final var connectorPubKey64 = deriveEcdsaPublicKey(CONNECTOR_SECRET_KEY);
        final var connectorPubKeyBytes = Bytes.wrap(connectorPubKey64);
        final var connectorId = deriveConnectorId(CHANNEL_ID, connectorPubKeyBytes, CONNECTOR_SALT);
        final var connectorCommitment = computeCommitment(connectorId, connectorPubKeyBytes);
        final var connectorSig = signConnectorMessage(CONNECTOR_SECRET_KEY, connectorId);

        // ── CLPR system contract (precompile) ContractID — used as both networks' verifier
        final var clprSystemContract =
                ContractID.newBuilder().setContractNum(CLPR_SYSTEM_CONTRACT_NUM).build();

        // ── Resolve actual gRPC ports (may differ from firstGrpcPort if offset) ──────────────────
        final int portA = netA.nodes().getFirst().getGrpcPort();
        final int portB = netB.nodes().getFirst().getGrpcPort();

        // ── Each network's real signed config and endpoint-manifest StateProofs, captured via
        //    clprGetLedgerConfiguration / clprGetEndpointManifest. The native verifier on the peer requires
        //    both to complete its channel (spec §4.8).
        final AtomicReference<ByteString> configProofForNetA = new AtomicReference<>(ByteString.EMPTY);
        final AtomicReference<ByteString> manifestProofForNetA = new AtomicReference<>(ByteString.EMPTY);
        final AtomicReference<ByteString> configProofForNetB = new AtomicReference<>(ByteString.EMPTY);
        final AtomicReference<ByteString> manifestProofForNetB = new AtomicReference<>(ByteString.EMPTY);

        // EchoApplication's ContractID on NET_B, captured during NET_B setup and used when
        // deploying SourceApplication on NET_A.
        final AtomicReference<ContractID> echoContractIdRef = new AtomicReference<>();

        // SourceApplication's ContractID on NET_A, captured during NET_A setup and injected into
        // each invocation spec (which has a fresh registry and cannot resolve SOURCE_APP by name).
        final AtomicReference<ContractID> sourceAppIdRef = new AtomicReference<>();

        // ── Step 0: Install NET_A's ledger config so its config StateProof can be captured for NET_B ──
        final var netAConfigInstall = networkHapiTest(
                        netA,
                        // Fund the node account so it can pay for verifier gas dispatches when
                        // ClprSubmitBundleHandler submits bundles internally.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                        // Install NET_A's local ledger config (chain id, service address, throttles).
                        // Peers reach A via its endpoint manifest, not via the config's endpoints.
                        clprUpdateLedgerConfiguration()
                                .configuration(buildLedgerConfig(NET_A_CHAIN_ID, portA))
                                .payingWith(GENESIS))
                .findFirst()
                .orElseThrow();
        // NET_A's config and finalized endpoint-manifest StateProofs, threaded into NET_B's completeChannel.
        final var netAConfigCapture =
                captureConfigProof(netA, configProofForNetB, MAX_MESSAGES_PER_BUNDLE, MAX_QUEUE_DEPTH);
        final var netAManifestCapture = captureManifestProof(netA, manifestProofForNetB);

        // ── Step 1: Setup NET_B ───────────────────────────────────────────────────────────────────
        // Deploy EchoApplication, complete channel/connector, capture EchoApplication address for
        // use in SourceApplication's constructor.
        final var netBSetup = networkHapiTest(
                        netB,
                        // Fund the node account so it can pay for verifier gas dispatches when
                        // ClprSubmitBundleHandler submits bundles internally.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                        // Install NET_B's local ledger config (chain id, service address, throttles).
                        // Peers reach B via its endpoint manifest, not via the config's endpoints.
                        clprUpdateLedgerConfiguration()
                                .configuration(buildLedgerConfig(NET_B_CHAIN_ID, portB))
                                .payingWith(GENESIS),
                        uploadInitCode(ECHO_APP),
                        contractCreate(ECHO_APP),
                        // Fund EchoApplication as the connector contract: must cover
                        // messageExecutionCost + endpointMarginPercent per DATA message.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, ECHO_APP, ONE_HUNDRED_HBARS)),
                        clprRegisterChannel()
                                .ownershipCommitment(channelCommitment.toByteArray())
                                .payingWith(GENESIS)
                                .via("registerChannelB"),
                        // NET_B uses the CLPR precompile's native verifyConfig — requires real
                        // TSS-signed config and endpoint-manifest StateProofs captured from NET_A above.
                        // sourcing(...) defers both .get() calls until runtime so the captured values are
                        // visible.
                        sourcing(() -> clprCompleteChannel()
                                .channelId(CHANNEL_ID.toByteArray())
                                .publicKey(connPubKey64)
                                .signature(channelSig)
                                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                                .verifierContractId(clprSystemContract)
                                .configProofBytes(configProofForNetB.get().toByteArray())
                                .endpointManifestProofBytes(
                                        manifestProofForNetB.get().toByteArray())
                                .payingWith(GENESIS)
                                .via("completeChannelB")),
                        clprRegisterConnector()
                                .commitment(connectorCommitment.toByteArray())
                                .payingWith(GENESIS)
                                .via("registerConnectorB"),
                        clprCompleteConnector()
                                .connectorId(connectorId.toByteArray())
                                .publicKey(connectorPubKey64)
                                .signature(connectorSig)
                                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                                .salt(CONNECTOR_SALT.toByteArray())
                                .channelId(CHANNEL_ID.toByteArray())
                                .connectorContract(ECHO_APP)
                                .adminKeyName(GENESIS)
                                .lockedStake(MIN_STAKE)
                                .payingWith(GENESIS)
                                .via("completeConnectorB"),
                        withOpContext((spec, opLog) ->
                                echoContractIdRef.set(spec.registry().getContractId(ECHO_APP))))
                .findFirst()
                .orElseThrow();
        // NET_B's config and finalized endpoint-manifest StateProofs, threaded into NET_A's completeChannel.
        final var netBConfigCapture =
                captureConfigProof(netB, configProofForNetA, MAX_MESSAGES_PER_BUNDLE, MAX_QUEUE_DEPTH);
        final var netBManifestCapture = captureManifestProof(netB, manifestProofForNetA);

        // ── Step 2: Setup NET_A ───────────────────────────────────────────────────────────────────
        // Deploy SourceApplication and complete channel/connector. SourceApplication is
        // deployed inside withOpContext so its constructor can receive EchoApplication's EVM
        // address from NET_B. SourceApplication itself serves as the connector contract on
        // NET_A — it holds HBAR for slashing and is the correct entity to vouch for outbound
        // messages. No EchoApplication is deployed on NET_A; echoing is done only on NET_B.
        // NET_A's ledger config was installed in Step 0.
        final var netASetup = networkHapiTest(
                        netA,
                        // Deploy the connector-auth contract — implements authorizeOutboundMessage
                        uploadInitCode(CONNECTOR_AUTH),
                        contractCreate(CONNECTOR_AUTH),
                        uploadInitCode(SOURCE_APP),
                        // Deploy SourceApplication now that we have EchoApplication's address.
                        withOpContext((spec, opLog) -> {
                            final byte[] echoAddr = asAddress(echoContractIdRef.get());
                            allRunFor(
                                    spec,
                                    contractCreate(
                                            SOURCE_APP, CHANNEL_ID.toByteArray(), connectorId.toByteArray(), echoAddr));
                        }),
                        // Fund SourceApplication.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, SOURCE_APP, ONE_HUNDRED_HBARS)),
                        // Fund CONNECTOR_AUTH.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, CONNECTOR_AUTH, ONE_HUNDRED_HBARS)),
                        clprRegisterChannel()
                                .ownershipCommitment(channelCommitment.toByteArray())
                                .payingWith(GENESIS)
                                .via("registerChannelA"),
                        // NET_A uses the CLPR precompile's native verifyConfig — requires real
                        // TSS-signed config and endpoint-manifest StateProofs captured from NET_B above.
                        // sourcing(...) defers both .get() calls until runtime so the captured values are
                        // visible.
                        sourcing(() -> clprCompleteChannel()
                                .channelId(CHANNEL_ID.toByteArray())
                                .publicKey(connPubKey64)
                                .signature(channelSig)
                                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                                .verifierContractId(clprSystemContract)
                                .configProofBytes(configProofForNetA.get().toByteArray())
                                .endpointManifestProofBytes(
                                        manifestProofForNetA.get().toByteArray())
                                .payingWith(GENESIS)
                                .via("completeChannelA")),
                        clprRegisterConnector()
                                .commitment(connectorCommitment.toByteArray())
                                .payingWith(GENESIS)
                                .via("registerConnectorA"),
                        clprCompleteConnector()
                                .connectorId(connectorId.toByteArray())
                                .publicKey(connectorPubKey64)
                                .signature(connectorSig)
                                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                                .salt(CONNECTOR_SALT.toByteArray())
                                .channelId(CHANNEL_ID.toByteArray())
                                .connectorContract(CONNECTOR_AUTH)
                                .adminKeyName(GENESIS)
                                .lockedStake(MIN_STAKE)
                                .payingWith(GENESIS)
                                .via("completeConnectorA"),
                        withOpContext((spec, opLog) ->
                                sourceAppIdRef.set(spec.registry().getContractId(SOURCE_APP))))
                .findFirst()
                .orElseThrow();

        // ── Steps 3–5: 3 invocations of sendMessages(7) with 60s polling ─────────────────────────
        // For each invocation: trigger SourceApplication.sendMessages(7) on NET_A, then poll
        // SourceApplication.responseCount() until it reaches (invocation+1)*7 or 60s elapses.
        // Auto-sync handles all bundle delivery in both directions (up to 3 bundles of 3 per invocation).
        final var responseCountAbi = getABIFor(FUNCTION, "responseCount", SOURCE_APP);
        final DynamicTest[] invocationTests = new DynamicTest[INVOCATION_COUNT * 2];
        for (int inv = 0; inv < INVOCATION_COUNT; inv++) {
            final long expectedResponses = (long) (inv + 1) * MESSAGES_PER_INVOCATION;
            invocationTests[inv * 2] = networkHapiTest(
                            netA,
                            withOpContext(
                                    (spec, opLog) -> spec.registry().saveContractId(SOURCE_APP, sourceAppIdRef.get())),
                            contractCall(SOURCE_APP, "sendMessages", BigInteger.valueOf(MESSAGES_PER_INVOCATION))
                                    .gas(GAS)
                                    .payingWith(GENESIS)
                                    .hasKnownStatus(SUCCESS)
                                    .via("sendMessages" + inv))
                    .findFirst()
                    .orElseThrow();

            invocationTests[inv * 2 + 1] = networkHapiTest(netA, withOpContext((spec, opLog) -> {
                        spec.registry().saveContractId(SOURCE_APP, sourceAppIdRef.get());
                        final var deadline = Instant.now().plus(POLL_TIMEOUT);
                        final long[] countHolder = {0L};
                        while (Instant.now().isBefore(deadline)) {
                            try {
                                allRunFor(
                                        spec,
                                        QueryVerbs.contractCallLocalWithFunctionAbi(SOURCE_APP, responseCountAbi)
                                                .exposingTypedResultsTo(results -> {
                                                    if (results.length > 0) {
                                                        countHolder[0] = ((BigInteger) results[0]).longValue();
                                                    }
                                                }));
                            } catch (final Exception ignored) {
                                // contract may not be ready yet; retry
                            }
                            if (countHolder[0] >= expectedResponses) return;
                            Thread.sleep(POLL_INTERVAL.toMillis());
                        }
                        assertTrue(
                                countHolder[0] >= expectedResponses,
                                "Expected " + expectedResponses + " responses on NET_A after "
                                        + POLL_TIMEOUT.getSeconds()
                                        + "s, got " + countHolder[0]);
                    }))
                    .findFirst()
                    .orElseThrow();
        }
        return multiNetworkHapiTest(
                "multiMessageRoundTrip",
                Stream.concat(
                        Stream.of(
                                netAConfigInstall,
                                netAConfigCapture,
                                netAManifestCapture,
                                netBSetup,
                                netBConfigCapture,
                                netBManifestCapture,
                                netASetup),
                        Stream.of(invocationTests)));
    }

    /**
     * Builds a {@link ClprLedgerConfiguration} (proto-java) suitable for
     * {@link com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration}.
     *
     * <p>Sets {@code chainId}, the 20-byte CLPR service address, a single endpoint at
     * {@code 127.0.0.1:peerPort}, and non-zero throttles (all fields required by
     * {@code ClprUpdateLedgerConfigurationHandler} validation). The service address must equal the
     * one in this ledger's endpoint manifest, which the native verifier enforces (spec §4.8). The
     * endpoint is not a dial target — peers dial from the manifest — and its {@code tlsCertificate}
     * is a single-byte dummy, not used for actual TLS in test contexts.
     */
    private static ClprLedgerConfiguration buildLedgerConfig(final String chainId, final int peerPort) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(chainId)
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(MAX_MESSAGES_PER_BUNDLE)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(MAX_QUEUE_DEPTH)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build();
    }
}
