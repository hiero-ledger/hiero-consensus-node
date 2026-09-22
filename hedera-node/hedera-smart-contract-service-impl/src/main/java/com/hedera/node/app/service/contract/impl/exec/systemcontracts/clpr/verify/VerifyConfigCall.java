// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
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
 * Implements {@code verifyConfig(bytes stateProofBytes) returns (bytes)}.
 *
 * <p>The trust anchor used to authenticate the proof is the {@code initial_trust_anchor}
 * carried inside the proven {@link ClprLedgerConfiguration} — for Hiero TSS the source ledger's
 * own ledger_id, populated at {@code ClprUpdateLedgerConfiguration} time. The TSS signature
 * check is delegated to the injected {@link TssVerifier} as
 * {@code verifyTss(ledgerId, signature, blockRootHash)}. The Merkle structure is validated by
 * computing the block root hash from the proof's paths (which throws on bad structure), the
 * inner {@code ClprLedgerConfiguration} bytes are extracted from the single state-item leaf,
 * and the parsed config is returned unchanged. A proof whose inner config has an empty
 * {@code initial_trust_anchor} is rejected — the source ledger must populate the field before
 * peers can register against it.
 */
public class VerifyConfigCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(VerifyConfigCall.class);
    private static final long GAS_REQUIREMENT = 50_000L;

    private final byte[] stateProofBytes;
    private final TssVerifier tssVerifier;

    /** Non-null on the V2/V3 (context) paths; null on V1. */
    @Nullable
    private final byte[] channelId32;

    /** Non-null only on the V3 (manifest-aware) path; selects v3Success over v2Success. */
    @Nullable
    private final byte[] manifestProofBytes;

    public VerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] stateProofBytes,
            @NonNull final TssVerifier tssVerifier) {
        super(gasCalculator, enhancement, true);
        this.stateProofBytes = requireNonNull(stateProofBytes);
        this.tssVerifier = requireNonNull(tssVerifier);
        this.channelId32 = null;
        this.manifestProofBytes = null;
    }

    public VerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] stateProofBytes,
            @NonNull final byte[] channelId32,
            @NonNull final TssVerifier tssVerifier) {
        super(gasCalculator, enhancement, true);
        this.stateProofBytes = requireNonNull(stateProofBytes);
        this.channelId32 = requireNonNull(channelId32);
        this.tssVerifier = requireNonNull(tssVerifier);
        this.manifestProofBytes = null;
    }

    public VerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] stateProofBytes,
            @NonNull final byte[] channelId32,
            @NonNull final byte[] manifestProofBytes,
            @NonNull final TssVerifier tssVerifier) {
        super(gasCalculator, enhancement, true);
        this.stateProofBytes = requireNonNull(stateProofBytes);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestProofBytes = requireNonNull(manifestProofBytes);
        this.tssVerifier = requireNonNull(tssVerifier);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.debug(
                "verifyConfig ENTER: stateProofBytes={} stateProofHash={} stateProofPrefix={}",
                stateProofBytes.length,
                shortHex(MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(stateProofBytes))),
                shortHex(Bytes.wrap(stateProofBytes)));

        // Catch-all guard: any unexpected throwable inside verification must surface as a logged
        // CLPR_VERIFIER_CONFIG_FAILED revert rather than escaping execute() silently (the dispatch
        // layer otherwise reports such an escape only as an opaque INVALID_TRANSACTION_BODY, with no
        // indication of where in verifyConfig it died).
        try {
            return doExecute();
        } catch (final RuntimeException e) {
            return failAsError("unexpected exception during verifyConfig: " + e, e);
        }
    }

    private @NonNull PricedResult doExecute() {
        final StateProof proof;
        try {
            proof = StateProof.PROTOBUF.parse(Bytes.wrap(stateProofBytes).toReadableSequentialData());
        } catch (final Exception e) {
            return fail("failed to parse StateProof", e);
        }
        log.debug(
                "verifyConfig PROOF parsed: paths={} signedBlockProof={}",
                proof.paths().size(),
                proof.hasSignedBlockProof());

        // 1. Extract the single state-item leaf and parse the inner ClprLedgerConfiguration —
        //    we need its initial_trust_anchor to know which TSS authority should have signed
        //    this proof.
        final var leafBytes = ClprProofExtraction.findFirstStateItemLeafBytes(proof);
        if (leafBytes == null) {
            return fail("no state-item leaf in proof; paths=" + proof.paths().size());
        }
        final var valueBytes = ClprProofExtraction.extractStateItemValue(leafBytes);
        if (valueBytes == null) {
            return fail("no value bytes in state-item leaf; leafBytes=" + leafBytes.length());
        }
        final var configBytes = ClprProofExtraction.unwrapStateValueField(valueBytes);
        if (configBytes == null) {
            return fail("state-item value did not wrap ClprLedgerConfiguration; valueBytes=" + valueBytes.length());
        }
        final ClprLedgerConfiguration parsed;
        try {
            parsed = ClprLedgerConfiguration.PROTOBUF.parse(configBytes.toReadableSequentialData());
            requireNonNull(parsed);
        } catch (final Exception e) {
            return fail(
                    "extracted bytes not a ClprLedgerConfiguration; configBytes="
                            + configBytes.length()
                            + " configHash="
                            + shortHex(MiscCryptoUtils.keccak256DigestOf(configBytes)),
                    e);
        }
        final var trustAnchorBytes = parsed.initialTrustAnchor();
        log.debug(
                "verifyConfig CONFIG extracted: leafBytes={} valueBytes={} configBytes={} configHash={} chainId={} "
                        + "serviceAddress={} endpoints={} throttlesPresent={} initialTrustAnchorBytes={} "
                        + "initialTrustAnchorHash={} initialTrustAnchorId={}",
                leafBytes.length(),
                valueBytes.length(),
                configBytes.length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(configBytes)),
                parsed.chainId(),
                shortHex(parsed.serviceAddress()),
                parsed.endpoints().size(),
                parsed.throttles() != null,
                trustAnchorBytes.length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(trustAnchorBytes)),
                shortHex(parsed.initialTrustAnchorId()));
        if (trustAnchorBytes.length() == 0) {
            return fail("proven config has empty initial_trust_anchor; chainId="
                    + parsed.chainId()
                    + " serviceAddress="
                    + shortHex(parsed.serviceAddress())
                    + " endpoints="
                    + parsed.endpoints().size()
                    + " throttlesPresent="
                    + (parsed.throttles() != null));
        }

        // 2. Structural Merkle validation + compute block root hash.
        final byte[] rootHash;
        try {
            rootHash = StateProofVerifier.computeBlockRootHash(proof);
        } catch (final Exception e) {
            return fail("structurally invalid proof; trustAnchor=" + shortHex(trustAnchorBytes), e);
        }
        log.debug(
                "verifyConfig MERKLE PASS: paths={} rootHash={}", proof.paths().size(), shortHex(Bytes.wrap(rootHash)));

        // 3. TSS verify: signature on rootHash, attributed to the trust anchor the proven
        //    config self-identifies with (peer ledger_id for Hiero TSS).
        if (!proof.hasSignedBlockProof()) {
            return fail("no signed block proof; proofType=" + proof.getClass().getSimpleName());
        }
        final var signature = proof.signedBlockProofOrThrow().blockSignature();
        if (signature.length() == 0) {
            return fail("no signature in signed block proof; proofType="
                    + proof.getClass().getSimpleName());
        }
        log.debug(
                "verifyConfig TSS START: trustAnchor={} signatureBytes={} rootHash={}",
                shortHex(trustAnchorBytes),
                signature.length(),
                shortHex(Bytes.wrap(rootHash)));
        final var tssVerified = tssVerifier.verifyTss(trustAnchorBytes, signature, Bytes.wrap(rootHash));
        if (!tssVerified) {
            return failAsError("TSS verification failed; trustAnchor="
                    + shortHex(trustAnchorBytes)
                    + " signatureBytes="
                    + signature.length()
                    + " rootHash="
                    + shortHex(Bytes.wrap(rootHash)));
        }
        log.debug(
                "verifyConfig TSS PASS: trustAnchor={} signatureBytes={} rootHash={}",
                shortHex(trustAnchorBytes),
                signature.length(),
                shortHex(Bytes.wrap(rootHash)));

        // 4. Return the proven configuration unchanged — initial_trust_anchor is already set
        //    by the source ledger; the CLPR Service uses it directly to seed Channel.trust_anchor.
        log.debug(
                "verifyConfig EXIT: SUCCESS trustAnchor={} rootHash={}",
                shortHex(trustAnchorBytes),
                shortHex(Bytes.wrap(rootHash)));
        if (channelId32 == null) {
            return v1Success(parsed);
        }
        return manifestProofBytes == null ? v2Success(parsed) : v3Success(parsed);
    }

    @NonNull
    private PricedResult v1Success(@NonNull final ClprLedgerConfiguration parsed) {
        final var configBytesOut = ClprLedgerConfiguration.PROTOBUF.toBytes(parsed);
        log.debug("verifyConfig V1 EXIT: SUCCESS configBytes={}", configBytesOut.length());
        return gasOnly(
                successResult(
                        VerifyConfigTranslator.VERIFY_CONFIG
                                .getOutputs()
                                .encode(Tuple.singleton(configBytesOut.toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
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

        log.debug("verifyConfig V2 EXIT: SUCCESS chainId={} endpoints={}", parsed.chainId(), endpointTuples.length);
        return gasOnly(
                successResult(
                        VerifyConfigTranslator.VERIFY_CONFIG_V2
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
    private PricedResult v3Success(@NonNull final ClprLedgerConfiguration parsed) {
        final ClprEndpointManifest manifest = verifyManifest(parsed);
        if (manifest == null) {
            return failureResult();
        }
        final byte[] id32 = requireNonNull(channelId32);
        final byte[] serviceAddressBytes = parsed.serviceAddress().toByteArray();
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(id32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);

        // Throttles struct (uint32,uint64,uint64,uint32,uint64,uint32,uint32): Long for uint32,
        // BigInteger for uint64.
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
                "verifyConfig V3 EXIT: SUCCESS chainId={} manifestVersion={} manifestEndpoints={}",
                parsed.chainId(),
                manifest.version(),
                manifest.endpoints().size());
        return gasOnly(
                successResult(
                        VerifyConfigTranslator.VERIFY_CONFIG_V3
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

    /**
     * Verifies the endpoint-manifest proof against the same trust anchor as the config proof and
     * enforces spec §4.8 invariants (version >= 1; service_address matches the config). The V3 path
     * requires a non-empty proof. Returns {@code null} on any failure (already logged via fail()).
     */
    @Nullable
    private ClprEndpointManifest verifyManifest(@NonNull final ClprLedgerConfiguration parsedConfig) {
        final byte[] proofBytes = requireNonNull(manifestProofBytes);
        if (proofBytes.length == 0) {
            fail("V3 verifyConfig requires a non-empty endpoint_manifest_proof_bytes");
            return null;
        }
        final StateProof proof;
        try {
            proof = StateProof.PROTOBUF.parse(Bytes.wrap(proofBytes).toReadableSequentialData());
        } catch (final Exception e) {
            fail("failed to parse manifest StateProof", e);
            return null;
        }
        final byte[] blockRootHash;
        try {
            blockRootHash = StateProofVerifier.computeBlockRootHash(proof);
        } catch (final Exception e) {
            fail("structurally invalid manifest proof", e);
            return null;
        }
        if (!proof.hasSignedBlockProof()) {
            fail("manifest proof has no signed block proof");
            return null;
        }
        final var blockSignature = proof.signedBlockProofOrThrow().blockSignature();
        if (blockSignature.length() == 0) {
            fail("manifest proof has empty block signature");
            return null;
        }
        if (!tssVerifier.verifyTss(parsedConfig.initialTrustAnchor(), blockSignature, Bytes.wrap(blockRootHash))) {
            failAsError("manifest TSS verification failed");
            return null;
        }
        final var leafBytes = ClprProofExtraction.findFirstStateItemLeafBytes(proof);
        if (leafBytes == null) {
            fail("no state-item leaf in manifest proof");
            return null;
        }
        final var valueBytes = ClprProofExtraction.extractStateItemValue(leafBytes);
        if (valueBytes == null) {
            fail("no value bytes in manifest state-item leaf");
            return null;
        }
        final int svTag = ClprProofExtraction.readFirstVarintTag(valueBytes);
        if (svTag != ClprProofExtraction.SV_ENDPOINT_MANIFEST_TAG) {
            fail("manifest state-item value tag is not ClprEndpointManifest; tag=" + svTag);
            return null;
        }
        final var manifestBytes = ClprProofExtraction.unwrapStateValueField(valueBytes);
        if (manifestBytes == null) {
            fail("manifest state-item value did not wrap ClprEndpointManifest");
            return null;
        }
        final ClprEndpointManifest manifest;
        try {
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(manifestBytes.toReadableSequentialData());
            requireNonNull(manifest);
        } catch (final Exception e) {
            fail("extracted bytes not a ClprEndpointManifest", e);
            return null;
        }
        if (manifest.version() == 0L) {
            fail("manifest.version is zero (must be >= 1)");
            return null;
        }
        if (!manifest.serviceAddress().equals(parsedConfig.serviceAddress())) {
            fail("manifest.service_address != config.service_address");
            return null;
        }
        return manifest;
    }

    @NonNull
    private PricedResult fail(@NonNull final String reason) {
        log.warn(
                "verifyConfig FAILED: reason={} stateProofBytes={} stateProofHash={} stateProofPrefix={} status={}",
                reason,
                stateProofBytes.length,
                shortHex(MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(stateProofBytes))),
                shortHex(Bytes.wrap(stateProofBytes)),
                CLPR_VERIFIER_CONFIG_FAILED);
        return failureResult();
    }

    @NonNull
    private PricedResult fail(@NonNull final String reason, @NonNull final Throwable cause) {
        log.warn(
                "verifyConfig FAILED: reason={} stateProofBytes={} stateProofHash={} stateProofPrefix={} status={}",
                reason,
                stateProofBytes.length,
                shortHex(MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(stateProofBytes))),
                shortHex(Bytes.wrap(stateProofBytes)),
                CLPR_VERIFIER_CONFIG_FAILED,
                cause);
        return failureResult();
    }

    @NonNull
    private PricedResult failAsError(@NonNull final String reason) {
        log.error(
                "verifyConfig FAILED: reason={} stateProofBytes={} stateProofHash={} stateProofPrefix={} status={}",
                reason,
                stateProofBytes.length,
                shortHex(MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(stateProofBytes))),
                shortHex(Bytes.wrap(stateProofBytes)),
                CLPR_VERIFIER_CONFIG_FAILED);
        return failureResult();
    }

    @NonNull
    private PricedResult failAsError(@NonNull final String reason, @NonNull final Throwable cause) {
        log.error(
                "verifyConfig FAILED: reason={} stateProofBytes={} stateProofHash={} stateProofPrefix={} status={}",
                reason,
                stateProofBytes.length,
                shortHex(MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(stateProofBytes))),
                shortHex(Bytes.wrap(stateProofBytes)),
                CLPR_VERIFIER_CONFIG_FAILED,
                cause);
        return failureResult();
    }

    @NonNull
    private PricedResult failureResult() {
        return gasOnly(
                ordinalRevertResult(CLPR_VERIFIER_CONFIG_FAILED, GAS_REQUIREMENT), CLPR_VERIFIER_CONFIG_FAILED, true);
    }

    private static String shortHex(@NonNull final Bytes bytes) {
        final var hex = bytes.toHex();
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
    }
}
