// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
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
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.POST_SYNC_POINT_SETTLE;
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.awaitWrapsExtensible;
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.awaitWrapsSyncPoint;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CHANNEL_ID;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CHANNEL_SECRET_KEY;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CONNECTOR_SALT;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CONNECTOR_SECRET_KEY;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.buildSyntheticConfigProof;
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
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
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
 * (last message in this bundle + 1, not the channel total). Both {@code HieroTssVerifier}
 * and {@code ClprPassThroughVerifier} compute it as
 * {@code acked_message_id + 1 + msgCount}, enabling partial batching within
 * the {@code maxMessagesPerBundle} limit.
 *
 * <p>Network assignments:
 * <ul>
 *   <li><b>NET_A</b> (port 39400): native Java verifier ({@code 0x16f}). Hosts
 *       {@code SourceApplication} (sender; also serves as the connector contract on this network).
 *   <li><b>NET_B</b> (port 40400): {@code ClprPassThroughVerifier} (Solidity). Hosts
 *       {@code EchoApplication} (echo target + connector contract).
 * </ul>
 *
 * <p>Seed endpoints in each network's {@code ClprLedgerConfiguration} point to the peer's
 * gRPC port. When a channel is activated, {@code ClprChannelManager.syncTick()} seeds
 * its peer endpoint cache from the local ledger configuration and initiates syncs every ~1s.
 */
@Tag(TestTags.MULTINETWORK)
public class ClprMessagesSuite {

    private static final String NET_A_CHAIN_ID = "hiero:msgs-a";
    private static final String NET_B_CHAIN_ID = "hiero:msgs-b";

    /**
     * CLPR system contract (precompile) ID. Used as NET_A's verifier so the channel exercises
     * the precompile's native {@code verifyConfig(bytes)} method, which runs real TSS + Merkle
     * verification (see {@code VerifyConfigCall}).
     */
    private static final long CLPR_SYSTEM_CONTRACT_NUM = 0x16eL;

    /** Solidity echo application deployed on NET_B only. */
    public static final String ECHO_APP = "EchoApplication";

    /** Solidity source application deployed on NET_A; sends messages and tracks responses. */
    public static final String SOURCE_APP = "SourceApplication";

    /** Solidity pass-through verifier deployed on NET_B. */
    private static final String PASS_THROUGH_VERIFIER = "ClprPassThroughVerifier";

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

        // ── CLPR system contract (precompile) ContractID — used as NET_A's verifier
        final var clprSystemContract =
                ContractID.newBuilder().setContractNum(CLPR_SYSTEM_CONTRACT_NUM).build();

        // ── Resolve actual gRPC ports (may differ from firstGrpcPort if offset) ──────────────────
        final int portA = netA.nodes().getFirst().getGrpcPort();
        final int portB = netB.nodes().getFirst().getGrpcPort();

        // ── Captured at runtime after netBSetup: NET_B's real signed StateProof from
        //    clprGetLedgerConfiguration. Required by the native CLPR precompile verifier on NET_A
        final AtomicReference<ByteString> configProofForNetA = new AtomicReference<>(ByteString.EMPTY);

        // EchoApplication's ContractID on NET_B, captured during NET_B setup and used when
        // deploying SourceApplication on NET_A.
        final AtomicReference<ContractID> echoContractIdRef = new AtomicReference<>();

        // SourceApplication's ContractID on NET_A, captured during NET_A setup and injected into
        // each invocation spec (which has a fresh registry and cannot resolve SOURCE_APP by name).
        final AtomicReference<ContractID> sourceAppIdRef = new AtomicReference<>();

        // ── Step 1: Setup NET_B ───────────────────────────────────────────────────────────────────
        // Deploy PassThroughVerifier + EchoApplication, complete channel/connector, capture
        // EchoApplication address for use in SourceApplication's constructor.
        final var netBSetup = networkHapiTest(
                        netB,
                        // Fund the node account so it can pay for verifier gas dispatches when
                        // ClprSubmitBundleHandler submits bundles internally.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                        // Update NET_B's local ledger config with NET_B's OWN gRPC port — peers
                        // read this from the StateProof-attested config to know where to reach B.
                        clprUpdateLedgerConfiguration()
                                .configuration(buildLedgerConfig(NET_B_CHAIN_ID, portB))
                                .payingWith(GENESIS),
                        uploadInitCode(PASS_THROUGH_VERIFIER),
                        contractCreate(PASS_THROUGH_VERIFIER),
                        uploadInitCode(ECHO_APP),
                        contractCreate(ECHO_APP),
                        // Fund EchoApplication as the connector contract: must cover
                        // messageExecutionCost + endpointMarginPercent per DATA message.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, ECHO_APP, ONE_HUNDRED_HBARS)),
                        clprRegisterChannel()
                                .ownershipCommitment(channelCommitment.toByteArray())
                                .payingWith(GENESIS)
                                .via("registerChannelB"),
                        // NET_B uses ClprPassThroughVerifier. The Solidity verifier walks the
                        // payload as a StateProof (paths → state_item_leaf → StateValue) and
                        // unwraps the LedgerConfiguration from the first StateValue field —
                        // raw protobuf bytes would revert. {@link #buildSyntheticConfigProof}
                        // produces the required wire shape.
                        clprCompleteChannel()
                                .channelId(CHANNEL_ID.toByteArray())
                                .publicKey(connPubKey64)
                                .signature(channelSig)
                                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                                .verifierContract(PASS_THROUGH_VERIFIER)
                                // Verifier-returned config must carry throttles + non-empty endpoints
                                // (ClprCompleteChannelHandler step 5 / spec §5.1.3).
                                .configProofBytes(buildSyntheticConfigProof(protoToPbj(
                                                buildLedgerConfig(NET_A_CHAIN_ID, portA),
                                                com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.class))
                                        .toByteArray())
                                .payingWith(GENESIS)
                                .via("completeChannelB"),
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
                                echoContractIdRef.set(spec.registry().getContractId(ECHO_APP))),
                        withOpContext((spec, opLog) -> {
                            awaitWrapsExtensible(netB);
                            awaitWrapsSyncPoint(netB);
                            Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                        }),
                        // capture net B state proof
                        clprGetLedgerConfiguration().payingWith(GENESIS).exposingProofTo(configProofForNetA::set))
                .findFirst()
                .orElseThrow();

        // ── Step 2: Setup NET_A ───────────────────────────────────────────────────────────────────
        // Deploy SourceApplication and complete channel/connector. SourceApplication is
        // deployed inside withOpContext so its constructor can receive EchoApplication's EVM
        // address from NET_B. SourceApplication itself serves as the connector contract on
        // NET_A — it holds HBAR for slashing and is the correct entity to vouch for outbound
        // messages. No EchoApplication is deployed on NET_A; echoing is done only on NET_B.
        final var netASetup = networkHapiTest(
                        netA,
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                        // Update NET_A's local ledger config with NET_A's OWN gRPC port — peers
                        // read this from the StateProof-attested config to know where to reach A.
                        clprUpdateLedgerConfiguration()
                                .configuration(buildLedgerConfig(NET_A_CHAIN_ID, portA))
                                .payingWith(GENESIS),
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
                        // NET_A uses the CLPR precompile's native verifyConfig — requires a real
                        // TSS-signed StateProof captured from NET_B above. sourcing(...) defers
                        // configProofForNetA.get() until runtime so the captured value is visible.
                        sourcing(() -> clprCompleteChannel()
                                .channelId(CHANNEL_ID.toByteArray())
                                .publicKey(connPubKey64)
                                .signature(channelSig)
                                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                                .verifierContractId(clprSystemContract)
                                .configProofBytes(configProofForNetA.get().toByteArray())
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
        return Stream.concat(Stream.of(netBSetup, netASetup), Stream.of(invocationTests));
    }

    /**
     * Builds a {@link ClprLedgerConfiguration} (proto-java) suitable for
     * {@link com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration}
     * or as raw {@code configProofBytes} for {@code ClprPassThroughVerifier}.
     *
     * <p>Sets {@code chainId}, a single seed endpoint at {@code 127.0.0.1:peerPort}, and
     * non-zero throttles (all fields required by {@code ClprUpdateLedgerConfigurationHandler}
     * validation). The seed endpoint's {@code tlsCertificate} and {@code ecdsaSigningKey} are
     * single-byte dummies — sufficient to pass field-presence checks, but not used for actual
     * mTLS or endpoint authentication in test contexts.
     */
    private static ClprLedgerConfiguration buildLedgerConfig(final String chainId, final int peerPort) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(chainId)
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 0x01, 0x6e}))
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(peerPort)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {0x01}))
                        .build())
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(3)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build();
    }

    static {
        // Eager symbol reference so any missing imports surface at class-init rather than mid-test.
        final var ignored = CLPR_SERVICE_ADDRESS_20.length;
    }
}
