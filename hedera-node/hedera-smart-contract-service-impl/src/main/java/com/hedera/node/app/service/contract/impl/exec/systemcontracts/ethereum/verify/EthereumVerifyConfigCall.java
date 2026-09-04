// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

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
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumSyncCommitteeProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.VerifiedConfig;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the Ethereum verifier system contract's {@code verifyConfig} selectors (EVM address
 * {@code 0x171}), mirroring the Hiero TSS verifier (spec §4.8):
 * <ul>
 *   <li><b>V1</b> {@code verifyConfig(bytes) -> (bytes)} — legacy, returns the proven config bytes only.</li>
 *   <li><b>V2</b> {@code verifyConfig(bytes,bytes32)} — config fields bound to a channel context.</li>
 *   <li><b>V3</b> {@code verifyConfig(bytes,bytes32,bytes)} — V2 plus a {@link ClprEndpointManifest}. For
 *       Ethereum the third argument is the manifest <b>raw bytes</b> (self-described at bootstrap), not a
 *       state proof, so it is strict-parsed and checked against the §4.8 invariants directly rather than
 *       re-derived from a proven state root.</li>
 * </ul>
 *
 * <p>The {@code configPayload} is the RLP-encoded sync-committee config payload carrying the initial sync
 * committee, the chain-pinning {@code genesisValidatorsRoot}/{@code forkVersion}, and the advertised
 * {@link ClprLedgerConfiguration}. Decoding is delegated to
 * {@link EthereumSyncCommitteeProofVerifier#verifyConfigPayload(byte[])}, which derives the initial trust
 * anchor and attaches it as {@code initial_trust_anchor} / {@code initial_trust_anchor_id}.
 */
public class EthereumVerifyConfigCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(EthereumVerifyConfigCall.class);
    private static final long GAS_REQUIREMENT = 50_000L;

    private final byte[] configPayload;

    /** Non-null on the V2 and V3 (context-bound) paths. */
    @Nullable
    private final byte[] channelId32;

    /** Non-null only on the V3 (manifest-aware) path; selects v3Success over v2Success. */
    @Nullable
    private final byte[] manifestBytes;

    public EthereumVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configPayload) {
        super(gasCalculator, enhancement, true);
        this.configPayload = requireNonNull(configPayload);
        this.channelId32 = null;
        this.manifestBytes = null;
    }

    public EthereumVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configPayload,
            @NonNull final byte[] channelId32) {
        super(gasCalculator, enhancement, true);
        this.configPayload = requireNonNull(configPayload);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestBytes = null;
    }

    public EthereumVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configPayload,
            @NonNull final byte[] channelId32,
            @NonNull final byte[] manifestBytes) {
        super(gasCalculator, enhancement, true);
        this.configPayload = requireNonNull(configPayload);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestBytes = requireNonNull(manifestBytes);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.info("[EthereumVerifier] verifyConfig ENTER: configPayload={} bytes", configPayload.length);

        final VerifiedConfig verified = runVerifier();
        if (verified == null) {
            return fail();
        }

        final ClprLedgerConfiguration parsed = verified.ledgerConfiguration();
        if (parsed.initialTrustAnchor().length() == 0) {
            log.warn(
                    "[EthereumVerifier] verifyConfig: proven config has empty initial_trust_anchor; rejecting chainId={}",
                    parsed.chainId());
            return fail();
        }

        if (channelId32 == null) {
            return v1Success(parsed);
        }
        if (manifestBytes == null) {
            return v2Success(parsed);
        }
        final ClprEndpointManifest manifest = manifestFor(parsed);
        if (manifest == null) {
            return fail();
        }
        return v3Success(parsed, manifest);
    }

    /**
     * Runs the config-payload verifier. Returns the verified config, or {@code null} when the payload is rejected or
     * the verifier errors unexpectedly.
     */
    @Nullable
    private VerifiedConfig runVerifier() {
        final var verifier = new EthereumSyncCommitteeProofVerifier(FakeBlsSignatureVerifier.INSTANCE);
        final VerifiedConfig verified;
        try {
            verified = verifier.verifyConfigPayload(configPayload);
        } catch (final ProofException e) {
            log.warn("[EthereumVerifier] verifyConfig: payload rejected: {}", e.getMessage());
            return null;
        } catch (final Exception e) {
            log.warn("[EthereumVerifier] verifyConfig: unexpected error", e);
            return null;
        }
        final ClprLedgerConfiguration parsed = verified.ledgerConfiguration();
        log.info(
                "[EthereumVerifier] verifyConfig: verifier returned chainId={}, serviceAddress={}, "
                        + "initialTrustAnchor={} bytes, endpoints={}, slot={}",
                parsed.chainId(),
                parsed.serviceAddress(),
                parsed.initialTrustAnchor().length(),
                parsed.endpoints().size(),
                verified.slot());
        return verified;
    }

    @NonNull
    private PricedResult v1Success(@NonNull final ClprLedgerConfiguration parsed) {
        log.debug("[EthereumVerifier] verifyConfig EXIT: SUCCESS");
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

        log.debug("[EthereumVerifier] verifyConfig V2 EXIT: SUCCESS chainId={}", parsed.chainId());
        return gasOnly(
                successResult(
                        EthereumVerifyConfigTranslator.VERIFY_CONFIG_V2
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
                false);
    }

    @NonNull
    private PricedResult v3Success(
            @NonNull final ClprLedgerConfiguration parsed, @NonNull final ClprEndpointManifest manifest) {
        final byte[] id32 = requireNonNull(channelId32);
        final byte[] serviceAddressBytes = parsed.serviceAddress().toByteArray();
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(id32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);

        // headlong requires a Long for each uint32 throttle field and a BigInteger for each uint64 field.
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

        log.debug(
                "[EthereumVerifier] verifyConfig V3 EXIT: SUCCESS chainId={} manifestVersion={} manifestEndpoints={}",
                parsed.chainId(),
                manifest.version(),
                manifest.endpoints().size());
        return gasOnly(
                successResult(
                        EthereumVerifyConfigTranslator.VERIFY_CONFIG_V3
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
                false);
    }

    /**
     * Resolves the endpoint manifest for the V3 return. For Ethereum the manifest travels as <b>raw bytes</b> — trusted
     * like the self-described config at bootstrap (there is no state root to verify it against at config time). When
     * present the bytes are strict-parsed and checked for the spec §4.8 invariants; when empty (bring-up) an empty
     * manifest is synthesized, bound to the config's service address and seeded from its endpoints so the channel
     * opens with dial targets. Returns {@code null} on an invalid or §4.8-violating manifest (caller reverts).
     */
    @Nullable
    private ClprEndpointManifest manifestFor(@NonNull final ClprLedgerConfiguration parsed) {
        final byte[] bytes = requireNonNull(manifestBytes);
        if (bytes.length == 0) {
            return ClprEndpointManifest.newBuilder()
                    .version(1L)
                    .serviceAddress(parsed.serviceAddress())
                    .endpoints(parsed.endpoints())
                    .build();
        }
        final ClprEndpointManifest manifest;
        try {
            // Spec §1: reject a manifest carrying unrecognized fields.
            manifest =
                    ClprEndpointManifest.PROTOBUF.parseStrict(Bytes.wrap(bytes).toReadableSequentialData());
        } catch (final ParseException | RuntimeException e) {
            log.error("[EthereumVerifier] verifyConfig: manifest bytes are not a valid ClprEndpointManifest", e);
            return null;
        }
        if (manifest.version() == 0L) {
            log.warn("[EthereumVerifier] verifyConfig: manifest version is 0 (must be >= 1)");
            return null;
        }
        if (!manifest.serviceAddress().equals(parsed.serviceAddress())) {
            log.warn("[EthereumVerifier] verifyConfig: manifest service_address does not match config service_address");
            return null;
        }
        return manifest;
    }

    @NonNull
    private PricedResult configSuccess(@NonNull final ClprLedgerConfiguration config) {
        return gasOnly(
                successResult(
                        EthereumVerifyConfigTranslator.VERIFY_CONFIG
                                .getOutputs()
                                .encode(Tuple.singleton(ClprLedgerConfiguration.PROTOBUF
                                        .toBytes(config)
                                        .toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_VERIFIER_CONFIG_FAILED, GAS_REQUIREMENT), CLPR_VERIFIER_CONFIG_FAILED, false);
    }
}
