// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyConfig(bytes stateProofBytes) returns (bytes)} for the Besu QBFT
 * verifier system contract (EVM address {@code 0x16f}).
 *
 * <p>The {@code stateProofBytes} is a proto-encoded {@code QbftLedgerConfigurationPayload}
 * containing the genesis block header, current block header, ledger configuration, account proof,
 * and storage proofs needed to verify the CLPR service contract's on-chain configuration.
 *
 * <p>Proof verification is delegated to
 * {@link BesuQbftVerifier#verifyConfigPayload(byte[])}. On success the proven
 * {@link ClprLedgerConfiguration} bytes are returned unchanged so that
 * {@code ClprCompleteChannelHandler} can seed Channel state from them directly.
 */
public class BesuQBFTVerifyConfigCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(BesuQBFTVerifyConfigCall.class);
    private static final long GAS_REQUIREMENT = 50_000L;

    private final byte[] stateProofBytes;

    @Nullable
    private final byte[] channelId32;

    /** Non-null only on the V3 (manifest-aware) path; selects v3Success over v2Success. */
    @Nullable
    private final byte[] manifestProofBytes;

    public BesuQBFTVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] stateProofBytes) {
        super(gasCalculator, enhancement, true);
        this.stateProofBytes = requireNonNull(stateProofBytes);
        this.channelId32 = null;
        this.manifestProofBytes = null;
    }

    public BesuQBFTVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] stateProofBytes,
            @NonNull final byte[] channelId32) {
        super(gasCalculator, enhancement, true);
        this.stateProofBytes = requireNonNull(stateProofBytes);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestProofBytes = null;
    }

    public BesuQBFTVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] stateProofBytes,
            @NonNull final byte[] channelId32,
            @NonNull final byte[] manifestProofBytes) {
        super(gasCalculator, enhancement, true);
        this.stateProofBytes = requireNonNull(stateProofBytes);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestProofBytes = requireNonNull(manifestProofBytes);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.info("verifyConfig (QBFT) ENTER: stateProofBytes={} bytes", stateProofBytes.length);

        // The address/hash for the ledger config is derived from the payload
        final var verifier = new BesuQbftVerifier(new BesuQbftVerifier.Config(null, null, 30_000L));

        final BesuQbftVerifier.VerifiedConfig verified;
        try {
            verified = verifier.verifyConfigPayload(
                    stateProofBytes, manifestProofBytes != null ? manifestProofBytes : new byte[0]);
        } catch (final ProofException e) {
            log.warn("verifyConfig (QBFT): proof rejected: {}", e.getMessage());
            return fail();
        } catch (final Exception e) {
            log.warn("verifyConfig (QBFT): unexpected error", e);
            return fail();
        }

        final ClprLedgerConfiguration parsed = verified.ledgerConfiguration();

        final var trustAnchorBytes = parsed.initialTrustAnchor();
        if (trustAnchorBytes.length() == 0) {
            log.warn(
                    "verifyConfig (QBFT): proven config has empty initial_trust_anchor; rejecting chainId={}, serviceAddress={}",
                    parsed.chainId(),
                    parsed.serviceAddress());
            return fail();
        }

        if (channelId32 == null) {
            return v1Success(parsed);
        }
        if (manifestProofBytes == null) {
            return v2Success(parsed);
        }
        // V3: proven manifest verbatim when a real manifest proof was supplied; otherwise a bring-up
        // seed-fallback (version 1, bound to the proven service address, seeded with the config's
        // endpoints) so the channel bootstraps a dial target — the real manifest advances via Step 1b.
        final byte[] provenManifestBytes = verified.endpointManifestBytes();
        final ClprEndpointManifest manifest;
        if (provenManifestBytes.length > 0) {
            try {
                manifest = ClprEndpointManifest.PROTOBUF.parse(
                        Bytes.wrap(provenManifestBytes).toReadableSequentialData());
            } catch (final Exception e) {
                log.warn("verifyConfig (QBFT): proven manifest bytes are not a ClprEndpointManifest", e);
                return fail();
            }
        } else {
            manifest = ClprEndpointManifest.newBuilder()
                    .version(1L)
                    .serviceAddress(parsed.serviceAddress())
                    .endpoints(parsed.endpoints())
                    .build();
        }
        log.info(
                "verifyConfig (QBFT) V3 manifest: source={} version={} endpoints={}",
                provenManifestBytes.length > 0 ? "proven-proof" : "seed-fallback",
                manifest.version(),
                manifest.endpoints().size());
        return v3Success(parsed, manifest);
    }

    @NonNull
    private PricedResult v1Success(@NonNull final ClprLedgerConfiguration parsed) {
        log.info("verifyConfig (QBFT) EXIT: SUCCESS trustAnchor={}", parsed.initialTrustAnchor());
        return configSuccess(parsed);
    }

    @NonNull
    private PricedResult v2Success(@NonNull final ClprLedgerConfiguration parsed) {
        final byte[] id32 = requireNonNull(channelId32);
        final byte[] serviceAddressBytes = parsed.serviceAddress().toByteArray();
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(id32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);

        final ClprThrottles t = parsed.throttlesOrElse(ClprThrottles.DEFAULT);
        final Tuple throttlesTuple = Tuple.of(
                BigInteger.valueOf(t.maxMessagesPerBundle()),
                BigInteger.valueOf(t.maxMessagePayloadBytes()),
                BigInteger.valueOf(t.maxGasPerMessage()),
                BigInteger.valueOf(t.maxQueueDepth()),
                BigInteger.valueOf(t.maxSyncBytes()));

        final Tuple[] endpointTuples = parsed.endpoints().stream()
                .map(ep -> {
                    final ClprServiceEndpoint se = ep.serviceEndpointOrElse(ClprServiceEndpoint.DEFAULT);
                    return Tuple.of(
                            se.ipAddress(),
                            (long) se.port(),
                            ep.tlsCertificate().toByteArray(),
                            ep.accountId().toByteArray());
                })
                .toArray(Tuple[]::new);

        final var ts = parsed.timestamp();
        final long peerConfigNanos = ts != null ? ts.seconds() * 1_000_000_000L + ts.nanos() : 0L;

        log.info("verifyConfig V2 (QBFT) EXIT: SUCCESS chainId={}", parsed.chainId());
        return gasOnly(
                successResult(
                        BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2
                                .getOutputs()
                                .encode(Tuple.from(
                                        channelContextBytes,
                                        parsed.chainId(),
                                        serviceAddressBytes,
                                        BigInteger.valueOf(peerConfigNanos),
                                        throttlesTuple,
                                        parsed.initialTrustAnchor().toByteArray(),
                                        parsed.initialTrustAnchorId().toByteArray(),
                                        endpointTuples)),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    @NonNull
    private PricedResult v3Success(
            @NonNull final ClprLedgerConfiguration parsed, @NonNull final ClprEndpointManifest manifest) {
        final byte[] id32 = requireNonNull(channelId32);
        final byte[] serviceAddressBytes = parsed.serviceAddress().toByteArray();
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(id32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);

        final ClprThrottles t = parsed.throttlesOrElse(ClprThrottles.DEFAULT);
        final Tuple throttlesTuple = Tuple.from(
                Long.valueOf(t.maxMessagesPerBundle()),
                BigInteger.valueOf(t.maxMessagePayloadBytes()),
                BigInteger.valueOf(t.maxGasPerMessage()),
                Long.valueOf(t.maxQueueDepth()),
                BigInteger.valueOf(t.maxSyncBytes()),
                Long.valueOf(t.maxLocalEndpoints()),
                Long.valueOf(t.maxPeerEndpoints()));

        final var ts = parsed.timestamp();
        final long peerConfigNanos = ts != null ? ts.seconds() * 1_000_000_000L + ts.nanos() : 0L;

        log.info("verifyConfig V3 (QBFT) EXIT: SUCCESS chainId={}", parsed.chainId());
        return gasOnly(
                successResult(
                        BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V3
                                .getOutputs()
                                .encode(Tuple.from(
                                        channelContextBytes,
                                        parsed.chainId(),
                                        serviceAddressBytes,
                                        BigInteger.valueOf(peerConfigNanos),
                                        throttlesTuple,
                                        parsed.initialTrustAnchor().toByteArray(),
                                        parsed.initialTrustAnchorId().toByteArray(),
                                        manifestStructTuple(manifest))),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    @NonNull
    private PricedResult configSuccess(@NonNull final ClprLedgerConfiguration config) {
        return gasOnly(
                successResult(
                        BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG
                                .getOutputs()
                                .encode(Tuple.singleton(ClprLedgerConfiguration.PROTOBUF
                                        .toBytes(config)
                                        .toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_VERIFIER_CONFIG_FAILED, GAS_REQUIREMENT), CLPR_VERIFIER_CONFIG_FAILED, true);
    }
}
