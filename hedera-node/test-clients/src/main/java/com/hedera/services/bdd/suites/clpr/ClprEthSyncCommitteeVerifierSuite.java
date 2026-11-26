// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.clpr.ethereum.SyncCommitteeProofs;
import com.hedera.services.bdd.suites.clpr.ethereum.SyncCommitteeProofs.Preset;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import com.swirlds.state.spi.ReadableKVState;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end HAPI coverage for the built-in Ethereum sync-committee verifier system contract
 * (EVM address {@code 0x171}): from opening a channel via a protobuf config payload through a
 * self-submitted bundle that the verifier proves with SSZ + Merkle-Patricia proofs.
 *
 * <p>The payloads are produced by {@link SyncCommitteeProofs}, which re-derives the wire bytes
 * independently of the production encoders, so these tests exercise the node's deserialization on
 * the receiving CLPR endpoint as a black box.
 *
 * <p>The tests do not run concurrently: every createChannel and submitBundle reserves {@code clpr.verifierGasLimit}
 * from the {@code contracts.maxGasPerSec} gas throttle, so a few of them at once exhaust it and ingest returns
 * {@code BUSY}.
 */
@Tag(CLPR)
public class ClprEthSyncCommitteeVerifierSuite {

    /** Built-in Ethereum sync-committee verifier system contract (contract num 0x171). */
    private static final ContractID ETH_VERIFIER =
            ContractID.newBuilder().setContractNum(0x171L).build();

    /** Connector application contract — deployed only so the connector registration has a target. */
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";

    private static final byte[] TARGET_APPLICATION = {40, 50};
    private static final byte[] SENDER = {60, 70};
    private static final byte[] MESSAGE_DATA = {1, 2, 3};
    private static final byte[] MESSAGE_DATA_2 = {4, 5, 6};

    /**
     * Members of the 512-member mainnet committee that do not sign: 480/512 participation (~94%). This is the minimum
     * participant that the 3M max verify gas limit allows to verify the ledger configuration, due to the data size
     * required by the non-signers proofs.
     **/
    private static final int LOWEST_DEFAULT_GAS_NON_SIGNERS = 32;

    private static final int MANIFEST_ENDPOINTS = 2;

    /**
     * Members of the 512-member mainnet committee that do not sign: 345/512 participation, the lowest at which a
     * mainnet createChannel fits the node's transaction size limits.
     */
    private static final int LOWEST_SIZE_LIMIT_NON_SIGNERS = 167;

    /**
     * Covers the ~5M gas {@code verifyConfig} needs at 345/512 participation, above the 3M default.
     */
    private static final String LOWEST_SIZE_LIMIT_VERIFIER_GAS_LIMIT = "6000000";

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: self-submitted single-message bundle")
    final Stream<DynamicTest> completesChannelAndDeliversBundle() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            final var payload = firstMessage(crypto);
            final var content = SyncCommitteeProofs.singleMessageBundleContent(payload);

            return blockingOrder(flattened(
                    setupChannelWithEthereumVerifier(crypto, SyncCommitteeProofs.configPayload()),
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(SyncCommitteeProofs.bundlePayload(crypto.channelId(), content))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    // The proven bundle delivered message #1, so receivedMessageId advances 0 -> 1.
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(1L, conn.receivedMessageId(), "receivedMessageId should advance to 1");
                    })));
        }));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: minimal-preset (32-member) channel delivers a self-submitted bundle")
    final Stream<DynamicTest> completesMinimalPresetChannelAndDeliversBundle() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            final var content = SyncCommitteeProofs.singleMessageBundleContent(firstMessage(crypto));

            return blockingOrder(flattened(
                    setupChannelWithEthereumVerifier(crypto, SyncCommitteeProofs.configPayload(Preset.MINIMAL)),
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(
                                    SyncCommitteeProofs.bundlePayload(Preset.MINIMAL, crypto.channelId(), content))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(1L, conn.receivedMessageId(), "receivedMessageId should advance to 1");
                    })));
        }));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: peer serviceAddress not 20 bytes → CLPR_VERIFIER_CONFIG_FAILED")
    final Stream<DynamicTest> rejectsConfigWithBadServiceAddress() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            return blockingOrder(
                    clprUpdateLedgerConfiguration()
                            .configuration(localLedgerConfig())
                            .payingWith(GENESIS),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .verifierContractId(ETH_VERIFIER)
                            .configProofBytes(SyncCommitteeProofs.configPayloadBadServiceAddress())
                            .payingWith(GENESIS)
                            .hasKnownStatus(CLPR_VERIFIER_CONFIG_FAILED));
        }));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: tampered bundle account proof → CLPR_BUNDLE_VERIFICATION_FAILED")
    final Stream<DynamicTest> rejectsTamperedBundle() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            final var content = SyncCommitteeProofs.singleMessageBundleContent(firstMessage(crypto));

            return blockingOrder(flattened(
                    setupChannelWithEthereumVerifier(crypto, SyncCommitteeProofs.configPayload()),
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(SyncCommitteeProofs.tamperedBundlePayload(crypto.channelId(), content))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS)
                            .hasKnownStatus(CLPR_BUNDLE_VERIFICATION_FAILED)));
        }));
    }

    /**
     * A bundle whose SSZ and Merkle-Patricia proofs all verify, but whose sync-aggregate signature is over the signing
     * root of another beacon block, so only the node's BLS signature verifier can reject it. This guards the production
     * wiring against an accept-all signature verifier.
     *
     * <p>{@code CLPR_BUNDLE_VERIFICATION_FAILED} is returned by many checks, so the same bundle signed over the correct
     * root is then submitted on the same channel and must be accepted. The two payloads differ only in the signed root,
     * which pins the first rejection to the BLS check.
     */
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: bundle signed over another signing root → CLPR_BUNDLE_VERIFICATION_FAILED")
    final Stream<DynamicTest> rejectsBundleSignedOverWrongSigningRoot() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            final var content = SyncCommitteeProofs.singleMessageBundleContent(firstMessage(crypto));

            return blockingOrder(flattened(
                    setupChannelWithEthereumVerifier(crypto, SyncCommitteeProofs.configPayload()),
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(
                                    SyncCommitteeProofs.wrongSigningRootBundlePayload(crypto.channelId(), content))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS)
                            .hasKnownStatus(CLPR_BUNDLE_VERIFICATION_FAILED),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertThat(conn.receivedMessageId())
                                .as("the rejected bundle must not deliver message #1")
                                .isZero();
                    }),
                    // The same bundle signed over the correct root passes, so only the signature caused the rejection.
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(SyncCommitteeProofs.bundlePayload(crypto.channelId(), content))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertThat(conn.receivedMessageId())
                                .as("the correctly signed bundle must deliver message #1")
                                .isEqualTo(1L);
                    })));
        }));
    }

    /**
     * Verifies the full committee-rotation lifecycle end-to-end:
     * <ol>
     *   <li>The configured anchor trusts the initial committee for its period and the next committee for the period
     *       after. A rotation bundle carries a rotation of that next period, signed by the next committee and proving
     *       the committee after it, and a delivery signed by that committee two periods after the configured one; the
     *       verifier slides the channel's trust anchor by one period and delivers message #1.</li>
     *   <li>A follow-up bundle signed by the new committee is accepted against the updated trust anchor
     *       and delivers message #2.</li>
     * </ol>
     */
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: rotation bundle updates trust anchor; new-committee bundle succeeds")
    final Stream<DynamicTest> completesChannelAfterCommitteeRotation() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            final var payload1 = firstMessage(crypto);
            final var payload2 = secondMessage(crypto);
            final var rotationContent = SyncCommitteeProofs.singleMessageBundleContent(payload1);
            final var postRotationContent = SyncCommitteeProofs.twoMessageBundleContent(payload1, payload2);

            return blockingOrder(flattened(
                    setupChannelWithEthereumVerifier(crypto, SyncCommitteeProofs.configPayload()),
                    // Step 1: rotation bundle — delivers message #1 and proves nextCommittee.
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(
                                    SyncCommitteeProofs.rotationBundlePayload(crypto.channelId(), rotationContent))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(1L, conn.receivedMessageId(), "receivedMessageId should advance to 1");
                        assertEquals(
                                Bytes.wrap(SyncCommitteeProofs.trustAnchorIdAfterRotation()),
                                conn.trustAnchorId(),
                                "trust anchor id should be the period after the configured one");
                    }),
                    // Step 2: post-rotation bundle — delivers message #2 using the updated trust anchor.
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(SyncCommitteeProofs.postRotationBundlePayload(
                                    crypto.channelId(), postRotationContent))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(
                                2L,
                                conn.receivedMessageId(),
                                "receivedMessageId should advance to 2 after post-rotation bundle");
                    })));
        }));
    }

    /**
     * createChannel with a proof of the peer endpoint manifest supplied as the config path's
     * {@code endpoint_manifest_proof_bytes} (a sync-committee proof of the manifest commitment, spec §4.8).
     * Exercises 0, 1, and N (3) manifest endpoints — each on a fresh channel — and asserts the resulting
     * Channel's {@code endpoint_manifest_version >= 1} and that its stored endpoints match the proven manifest.
     */
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: createChannel installs the config endpoint manifest (0, 1, N endpoints)")
    final Stream<DynamicTest> createChannelInstallsConfigManifest() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final int[] endpointCounts = {0, 1, 3};
            final List<SpecOperation> ops = new ArrayList<>();
            ops.add(clprUpdateLedgerConfiguration()
                    .configuration(localLedgerConfig())
                    .payingWith(GENESIS));
            for (final int count : endpointCounts) {
                final var crypto = new ClprChannelCrypto(
                        localChain,
                        asSolidityAddress(ETH_VERIFIER),
                        SyncCommitteeProofs.CHAIN_ID,
                        SyncCommitteeProofs.serviceAddress());
                ops.add(clprCreateChannel()
                        .channelId(crypto.channelId())
                        .salt(crypto.salt())
                        .peerVerifier(crypto.peerVerifier())
                        .verifierContractId(ETH_VERIFIER)
                        .configProofBytes(SyncCommitteeProofs.configPayload())
                        .endpointManifestProofBytes(
                                SyncCommitteeProofs.configManifestProof(SyncCommitteeProofs.manifestBytes(1L, count)))
                        .payingWith(GENESIS));
                ops.add(withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(ClprChannelStatus.ACTIVE, conn.status(), "channel should be ACTIVE");
                    assertEquals(
                            1L, conn.endpointManifestVersion(), "endpoint_manifest_version should be the proven 1");
                    assertTrue(conn.endpointManifestVersion() >= 1L, "endpoint_manifest_version should be >= 1");
                    assertManifestEndpoints(conn, count);
                }));
            }
            return blockingOrder(ops.toArray(SpecOperation[]::new));
        }));
    }

    /**
     * A mainnet createChannel at the lowest sync-committee participation the shipped {@code clpr.verifierGasLimit} (3M)
     * supports, with no override. The config payload carries both committees' full key sets, so its size does not
     * depend on participation; the endpoint-manifest proof carries one non-signer proof (~419 bytes, ~15.8K gas at the
     * EIP-7623 calldata floor) per member that did not sign its header. The proof has mainnet sizes: its account and
     * storage proofs pass through full branch nodes and its execution header has every mainnet field.
     *
     * <p>At 480/512 signers the {@code verifyConfig} calldata floor is 2,908,330 gas, leaving a 91,670 gas margin
     * (~3%, ~5 non-signers) for real proofs larger than these. The floor passes 3M at 474/512 (3,003,060 gas). Mainnet
     * participation normally exceeds 96% (2,719,490 gas at 492/512); below 480/512.
     */
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: mainnet createChannel at 480/512 participation fits the default verifier gas")
    final Stream<DynamicTest> createChannelAtLowestParticipationOfDefaultGas() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            return blockingOrder(
                    clprUpdateLedgerConfiguration()
                            .configuration(localLedgerConfig())
                            .payingWith(GENESIS),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .verifierContractId(ETH_VERIFIER)
                            .configProofBytes(SyncCommitteeProofs.configPayload())
                            .endpointManifestProofBytes(SyncCommitteeProofs.mainnetConfigManifestProof(
                                    SyncCommitteeProofs.manifestBytes(1L, MANIFEST_ENDPOINTS),
                                    LOWEST_DEFAULT_GAS_NON_SIGNERS))
                            .payingWith(GENESIS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(ClprChannelStatus.ACTIVE, conn.status(), "channel should be ACTIVE");
                        assertEquals(1L, conn.endpointManifestVersion(), "endpoint_manifest_version should be 1");
                        assertManifestEndpoints(conn, MANIFEST_ENDPOINTS);
                    }));
        }));
    }

    /**
     * Tests the transaction size limits, not gas: a mainnet createChannel at the lowest participation that fits them
     * opens its channel under the production size limits. Unlike the rest of this suite, the test runs on a subprocess
     * network, because an embedded network does not go through the node's gRPC server.
     *
     * <p>The endpoint-manifest proof carries one non-signer proof (~419 bytes) per member that did not sign its
     * header,
     * so the lower the participation, the larger the transaction. Two size limits apply:
     * <ul>
     *   <li>the gRPC server accepts at most {@code NettyGrpcServerManager.MAX_TRANSACTION_SIZE} (133,120) bytes per
     *       transaction, a constant no configuration raises;</li>
     *   <li>every EVM call, the verifier's {@code verifyConfig} included, carries at most
     *       {@code jumboTransactions.ethereumMaxCallDataSize} (131,072) bytes of calldata.</li>
     * </ul>
     * The calldata limit binds first. At 345/512 the {@code verifyConfig} calldata is 130,948 bytes, 124 below it; at
     * 344/512 it exceeds it, although the transaction still passes gRPC. The verifier accepts participation down to the
     * 2/3 supermajority (342/512), so a mainnet channel can't be opened at 342-344/512.
     *
     * <p>{@code verifyConfig} needs ~5M gas here, so the test raises {@code clpr.verifierGasLimit} only to let the
     * channel open. With the default 3M, gas limits participation to 480/512 long before size does (see
     * {@link #createChannelAtLowestParticipationOfDefaultGas}). The manifest has no endpoints, so the channel never
     * dials peers on the shared network.
     */
    @LeakyHapiTest(overrides = {"clpr.verifierGasLimit"})
    @DisplayName("Eth verifier: mainnet createChannel at 345/512 participation fits the transaction size limits")
    final Stream<DynamicTest> createChannelAtLowestParticipationOfSizeLimits() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            return blockingOrder(
                    overriding("clpr.verifierGasLimit", LOWEST_SIZE_LIMIT_VERIFIER_GAS_LIMIT),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .verifierContractId(ETH_VERIFIER)
                            .configProofBytes(SyncCommitteeProofs.configPayload())
                            .endpointManifestProofBytes(SyncCommitteeProofs.mainnetConfigManifestProof(
                                    SyncCommitteeProofs.manifestBytes(1L, 0), LOWEST_SIZE_LIMIT_NON_SIGNERS))
                            .payingWith(GENESIS));
        }));
    }

    /**
     * A bundle carrying a higher-version endpoint manifest (spec §4.9) advances the Channel's cached manifest via
     * Step-1b. The channel opens at version 0 (the uninitialized manifest); the bundle proves a version-2
     * manifest at the commitment slot (18) and, when applied, updates {@code endpoint_manifest} / {@code
     * endpoint_manifest_version} while also delivering message #1.
     */
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: bundle carrying a higher-version manifest updates the Channel via Step-1b")
    final Stream<DynamicTest> bundleAdvancesEndpointManifest() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            final var content = SyncCommitteeProofs.singleMessageBundleContent(firstMessage(crypto));
            final byte[] advanceManifest = SyncCommitteeProofs.manifestBytes(2L, 2);

            return blockingOrder(flattened(
                    // createChannel with an empty endpoint_manifest_proof_bytes returns the uninitialized manifest
                    // (version 0, no endpoints), so the channel opens at manifest version 0.
                    setupChannelWithEthereumVerifier(crypto, SyncCommitteeProofs.configPayload()),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(0L, conn.endpointManifestVersion(), "channel opens at uninitialized manifest v0");
                        assertManifestEndpoints(conn, 0);
                    }),
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .bundlePayload(SyncCommitteeProofs.bundlePayloadWithManifest(
                                    crypto.channelId(), content, advanceManifest))
                            .endpointNodeId(0L)
                            .payingWith(GENESIS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(1L, conn.receivedMessageId(), "bundle delivered message #1");
                        assertEquals(2L, conn.endpointManifestVersion(), "manifest advanced to v2 via Step-1b");
                        assertManifestEndpoints(conn, 2);
                    })));
        }));
    }

    /**
     * A proven manifest whose {@code service_address} does not match the config's service address violates spec §4.8,
     * so the Ethereum verifier rejects it inside {@code verifyConfig} and createChannel reverts with
     * {@code CLPR_VERIFIER_CONFIG_FAILED}.
     */
    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("Eth verifier: manifest service_address mismatch → CLPR_VERIFIER_CONFIG_FAILED")
    final Stream<DynamicTest> rejectsManifestWithMismatchedServiceAddress() {
        return hapiTest(sourcingContextual(networkSpec -> {
            final var localChain = "eip155:" + networkSpec.startupProperties().getLong("contracts.chainId");
            final var crypto = new ClprChannelCrypto(
                    localChain,
                    asSolidityAddress(ETH_VERIFIER),
                    SyncCommitteeProofs.CHAIN_ID,
                    SyncCommitteeProofs.serviceAddress());
            return blockingOrder(
                    clprUpdateLedgerConfiguration()
                            .configuration(localLedgerConfig())
                            .payingWith(GENESIS),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .verifierContractId(ETH_VERIFIER)
                            .configProofBytes(SyncCommitteeProofs.configPayload())
                            .endpointManifestProofBytes(SyncCommitteeProofs.configManifestProof(
                                    SyncCommitteeProofs.manifestBytesBadServiceAddress()))
                            .payingWith(GENESIS)
                            .hasKnownStatus(CLPR_VERIFIER_CONFIG_FAILED));
        }));
    }

    // ── Helpers ──

    /**
     * Asserts the Channel's cached endpoint manifest has exactly {@code expectedCount} endpoints matching the
     * deterministic IP/port produced by {@link SyncCommitteeProofs#manifestEndpointIp}/{@code manifestEndpointPort}.
     */
    private static void assertManifestEndpoints(final ClprChannel conn, final int expectedCount) {
        final var manifest = conn.endpointManifest();
        assertTrue(manifest != null, "endpoint manifest should be present");
        final var endpoints = manifest.endpoints();
        assertEquals(expectedCount, endpoints.size(), "manifest endpoint count");
        for (int i = 0; i < expectedCount; i++) {
            final var svc = endpoints.get(i).serviceEndpoint();
            assertEquals(SyncCommitteeProofs.manifestEndpointIp(i), svc.ipAddress(), "endpoint[" + i + "] ip");
            assertEquals(SyncCommitteeProofs.manifestEndpointPort(i), svc.port(), "endpoint[" + i + "] port");
        }
    }

    private static SpecOperation[] setupChannelWithEthereumVerifier(
            ClprChannelCrypto crypto, byte[] ledgerConfigPayload) {
        return new SpecOperation[] {
            clprUpdateLedgerConfiguration().configuration(localLedgerConfig()).payingWith(GENESIS),
            uploadInitCode(CONNECTOR_CONTRACT),
            contractCreate(CONNECTOR_CONTRACT),
            clprCreateChannel()
                    .channelId(crypto.channelId())
                    .salt(crypto.salt())
                    .peerVerifier(crypto.peerVerifier())
                    .verifierContractId(ETH_VERIFIER)
                    .configProofBytes(ledgerConfigPayload)
                    .payingWith(GENESIS),
            // createChannel seeds an ACTIVE channel with the verifier-derived trust anchor.
            withOpContext((spec, opLog) -> {
                final var conn = readChannelFromState(spec, crypto);
                assertEquals(ClprChannelStatus.ACTIVE, conn.status(), "channel should be ACTIVE");
                assertTrue(conn.trustAnchor().length() > 0, "trust anchor should be seeded");
                assertEquals(0L, conn.receivedMessageId(), "no inbound messages yet");
            }),
            clprCreateConnector()
                    .connectorId(crypto.connectorId())
                    .ed25519Identity(crypto.seed())
                    .salt(crypto.connectorSalt())
                    .channelId(crypto.channelId())
                    .connectorContract(CONNECTOR_CONTRACT)
                    .adminKeyName(GENESIS)
                    .lockedStake(100_000_000L)
                    .payingWith(GENESIS)
        };
    }

    private static ClprMessagePayload firstMessage(final ClprChannelCrypto crypto) {
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                        .setTargetApplication(ByteString.copyFrom(TARGET_APPLICATION))
                        .setSender(ByteString.copyFrom(SENDER))
                        .setMessageData(ByteString.copyFrom(MESSAGE_DATA))
                        .build())
                .build();
    }

    private static ClprMessagePayload secondMessage(final ClprChannelCrypto crypto) {
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                        .setTargetApplication(ByteString.copyFrom(TARGET_APPLICATION))
                        .setSender(ByteString.copyFrom(SENDER))
                        .setMessageData(ByteString.copyFrom(MESSAGE_DATA_2))
                        .build())
                .build();
    }

    private static ClprChannel readChannelFromState(final HapiSpec spec, final ClprChannelCrypto crypto) {
        final ReadableKVState<ProtoBytes, ClprChannel> channels =
                spec.embeddedStateOrThrow().getReadableStates(ClprService.NAME).get(CHANNELS_STATE_ID);
        final var conn = channels.get(new ProtoBytes(Bytes.wrap(crypto.channelId())));
        if (conn == null) {
            throw new IllegalStateException("Channel not found in embedded state");
        }
        return conn;
    }

    /**
     * The local ledger's own configuration (unrelated to the peer config carried in the proof). The
     * Ethereum verifier reads the peer's service address from the config payload; this local one
     * must be the CLPR system contract address, since ClprUpdateLedgerConfiguration rejects others.
     */
    private static ClprLedgerConfiguration localLedgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:testing")
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build();
    }
}
