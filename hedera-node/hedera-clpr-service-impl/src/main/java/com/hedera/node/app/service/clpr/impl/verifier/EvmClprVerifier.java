// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.DISPATCH_ONLY_NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REMOVABLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ClprVerifier} that dispatches verification to a user-deployed EVM verifier
 * contract — the {@code verifier_contract} the Channel was registered with. The contract
 * is responsible for performing (or delegating) the actual cryptographic verification; the
 * CLPR Service simply marshals the proof bytes in and the verified data out.
 *
 * <p>Verifier contracts typically delegate heavy lifting (TSS aggregate signature checks,
 * Merkle path walking, state-value decoding) to the CLPR system contract precompile at
 * {@code 0x16e} — see {@code com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify}.
 *
 * <p>Always calls V2 ABI selectors:
 * <pre>
 *   function verifyConfig(bytes calldata proofBytes, bytes32 channelId)
 *       external returns (...);
 *   function verifyBundle(bytes calldata bundlePayload, bytes calldata trustAnchor, bytes calldata channelContext)
 *       external returns (...);
 * </pre>
 * Return values are headlong-decoded tuples rather than protobuf-encoded bytes.
 */
public class EvmClprVerifier implements ClprVerifier {

    private static final Logger log = LoggerFactory.getLogger(EvmClprVerifier.class);

    private static final DispatchMetadata CLPR_DISPATCH_METADATA = new DispatchMetadata(
            CLPR_DISPATCH, new ClprDispatchMetadata(CLPR_SERVICE_ACCOUNT_ID, CLPR_EVM_ADDRESS_BYTES));

    // V2 (context) config ABI — mainline: verifyConfig(bytes,bytes32) → config fields + seedEndpoints.
    // Dispatched when clpr.endpointManifestEnabled=false.
    private static final byte[] VERIFY_CONFIG_V2_SELECTOR;
    private static final TupleType<Tuple> VERIFY_CONFIG_V2_RETURN;
    // V3 (context + manifest) config ABI — SC-189: verifyConfig(bytes,bytes32,bytes) → config fields +
    // ClprEndpointManifest. Dispatched when clpr.endpointManifestEnabled=true. The V3 return type is
    // shared with all EVM verifiers — see ClprVerifierAbi.VERIFY_CONFIG_V3_RETURN.
    private static final byte[] VERIFY_CONFIG_V3_SELECTOR;
    // Bundle ABI — single selector verifyBundle(bytes,bytes,bytes). The return is flag-gated on the
    // decode side: V2 (4-member, mainline) when endpointManifestEnabled=false, V3 (5-member, with a
    // trailing newEndpointManifest; version==0 = absent) when true (ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN).
    // §4.2 Step 1b.
    private static final byte[] VERIFY_BUNDLE_V2_SELECTOR;
    private static final TupleType<Tuple> VERIFY_BUNDLE_V2_RETURN;

    static {
        VERIFY_CONFIG_V2_SELECTOR = Arrays.copyOf(
                MiscCryptoUtils.keccak256DigestOf("verifyConfig(bytes,bytes32)".getBytes(StandardCharsets.UTF_8)), 4);
        VERIFY_CONFIG_V3_SELECTOR = Arrays.copyOf(
                MiscCryptoUtils.keccak256DigestOf("verifyConfig(bytes,bytes32,bytes)".getBytes(StandardCharsets.UTF_8)),
                4);
        VERIFY_BUNDLE_V2_SELECTOR = Arrays.copyOf(
                MiscCryptoUtils.keccak256DigestOf("verifyBundle(bytes,bytes,bytes)".getBytes(StandardCharsets.UTF_8)),
                4);
        // V2 return (mainline): (channelContext, chainId, serviceAddress, peerConfigNanos,
        //   Throttles(uint64 x5), initialTrustAnchor, initialTrustAnchorId, Endpoint[] seedEndpoints)
        VERIFY_CONFIG_V2_RETURN = TupleType.parse(
                "(bytes,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64),bytes,bytes,(string,uint32,bytes,bytes)[])");
        // Bundle return V2 (mainline): metadata, messagePayloads, newTrustAnchor, newTrustAnchorId.
        VERIFY_BUNDLE_V2_RETURN = TupleType.parse("((uint64,bytes32,uint64,bytes32,uint8),bytes[],bytes,bytes)");
        // The V3 config and bundle return types are shared across all EVM verifiers — see ClprVerifierAbi.
    }

    private final ContractID verifierContract;

    public EvmClprVerifier(@NonNull final ContractID verifierContract) {
        this.verifierContract = requireNonNull(verifierContract);
    }

    @Override
    @NonNull
    public VerifiedConfig verifyConfig(
            @NonNull final Bytes configProofBytes,
            @NonNull final Bytes channelId,
            @NonNull final Bytes endpointManifestProofBytes,
            @NonNull final HandleContext context)
            throws HandleException {
        requireNonNull(configProofBytes);
        requireNonNull(channelId);
        requireNonNull(endpointManifestProofBytes);
        requireNonNull(context);
        final boolean manifestEnabled =
                context.configuration().getConfigData(ClprConfig.class).endpointManifestEnabled();
        return manifestEnabled
                ? verifyConfigV3(configProofBytes, channelId, endpointManifestProofBytes, context)
                : verifyConfigV2(configProofBytes, channelId, context);
    }

    /**
     * Manifest-aware (V3) dispatch: {@code verifyConfig(bytes,bytes32,bytes) → (…, ClprEndpointManifest)}.
     * Returns the proven config plus the state-proven peer manifest.
     */
    @NonNull
    private VerifiedConfig verifyConfigV3(
            @NonNull final Bytes configProofBytes,
            @NonNull final Bytes channelId,
            @NonNull final Bytes endpointManifestProofBytes,
            @NonNull final HandleContext context) {
        log.info(
                "[EvmClprVerifier] verifyConfig V3 ENTER: verifierContract={} proofBytes={} proofHash={} channelId={} manifestProofBytes={}",
                verifierContract,
                configProofBytes.length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(configProofBytes)),
                shortHex(channelId),
                endpointManifestProofBytes.length());
        final var callData = encodeVerifyConfigV3Call(
                VERIFY_CONFIG_V3_SELECTOR, configProofBytes, channelId, endpointManifestProofBytes);
        final var rawReturn = dispatchRaw(callData, CLPR_VERIFIER_CONFIG_FAILED, context);
        final var verified = decodeVerifyConfigV3Return(rawReturn, CLPR_VERIFIER_CONFIG_FAILED);
        log.info(
                "[EvmClprVerifier] verifyConfig V3 EXIT: chainId={} serviceAddress={} manifestVersion={} manifestEndpoints={} trustAnchorBytes={}",
                verified.config().chainId(),
                shortHex(verified.config().serviceAddress()),
                verified.manifest().version(),
                verified.manifest().endpoints().size(),
                verified.config().initialTrustAnchor().length());
        return verified;
    }

    /**
     * Legacy/context (V2) dispatch: {@code verifyConfig(bytes,bytes32) → (…, Endpoint[] seedEndpoints)}.
     * The manifest is not part of the V2 wire; a bring-up manifest (version 1, bound to the proven
     * service address, seeded with the config's endpoints) is synthesized so the returned
     * {@link VerifiedConfig} is well-formed.
     */
    @NonNull
    private VerifiedConfig verifyConfigV2(
            @NonNull final Bytes configProofBytes,
            @NonNull final Bytes channelId,
            @NonNull final HandleContext context) {
        log.info(
                "[EvmClprVerifier] verifyConfig V2 ENTER: verifierContract={} proofBytes={} proofHash={} channelId={}",
                verifierContract,
                configProofBytes.length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(configProofBytes)),
                shortHex(channelId));
        final var callData = encodeVerifyConfigV2Call(VERIFY_CONFIG_V2_SELECTOR, configProofBytes, channelId);
        final var rawReturn = dispatchRaw(callData, CLPR_VERIFIER_CONFIG_FAILED, context);
        final var config = decodeVerifyConfigV2Return(rawReturn, CLPR_VERIFIER_CONFIG_FAILED);
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(1L)
                .serviceAddress(config.serviceAddress())
                .endpoints(config.endpoints())
                .build();
        log.info(
                "[EvmClprVerifier] verifyConfig V2 EXIT: chainId={} serviceAddress={} endpoints={} trustAnchorBytes={}",
                config.chainId(),
                shortHex(config.serviceAddress()),
                config.endpoints().size(),
                config.initialTrustAnchor().length());
        return new VerifiedConfig(config, manifest);
    }

    @Override
    @NonNull
    public ClprBundleContent verifyBundle(
            @NonNull final Bytes bundlePayload,
            @NonNull final Bytes trustAnchor,
            @NonNull final Bytes channelContext,
            @NonNull final HandleContext context)
            throws HandleException {
        requireNonNull(bundlePayload);
        requireNonNull(trustAnchor);
        requireNonNull(channelContext);
        requireNonNull(context);
        log.debug(
                "[EvmClprVerifier] verifyBundle V2 ENTER: verifierContract={} bundlePayload={} bytes trustAnchor={} bytes channelContext={} bytes",
                verifierContract,
                bundlePayload.length(),
                trustAnchor.length(),
                channelContext.length());
        final var callData =
                encodeThreeBytesCall(VERIFY_BUNDLE_V2_SELECTOR, bundlePayload, trustAnchor, channelContext);
        final var rawReturn = dispatchRaw(callData, CLPR_BUNDLE_VERIFICATION_FAILED, context);
        final boolean manifestEnabled =
                context.configuration().getConfigData(ClprConfig.class).endpointManifestEnabled();
        final var content = manifestEnabled
                ? decodeVerifyBundleV3Return(rawReturn, CLPR_BUNDLE_VERIFICATION_FAILED)
                : decodeVerifyBundleV2Return(rawReturn, CLPR_BUNDLE_VERIFICATION_FAILED);
        log.debug(
                "[EvmClprVerifier] verifyBundle V2 EXIT: messages={} newTrustAnchorBytes={} newTrustAnchorId={}",
                content.messages().size(),
                content.newTrustAnchor().length(),
                shortHex(content.newTrustAnchorId()));
        return content;
    }

    /**
     * ABI-encodes verifyConfig(bytes,bytes32): head = [offset_to_proof=64][channelId32],
     * tail = [proof_length][proof_data_padded].
     */
    @NonNull
    private static byte[] encodeVerifyConfigV2Call(
            @NonNull final byte[] selector, @NonNull final Bytes proofBytes, @NonNull final Bytes channelId) {
        final byte[] proof = proofBytes.toByteArray();
        final byte[] connId32 = new byte[32];
        final byte[] connIdRaw = channelId.toByteArray();
        System.arraycopy(connIdRaw, 0, connId32, 0, Math.min(connIdRaw.length, 32));
        final int proofPadded = ((proof.length + 31) / 32) * 32;
        final var buf = ByteBuffer.allocate(4 + 32 + 32 + 32 + proofPadded);
        buf.put(selector);
        putUint256(buf, 64); // offset to proofBytes (two head words = 64)
        buf.put(connId32); // bytes32 channelId (static, inline in head)
        putUint256(buf, proof.length);
        buf.put(proof);
        if (proofPadded > proof.length) buf.put(new byte[proofPadded - proof.length]);
        return buf.array();
    }

    /**
     * ABI-encodes verifyConfig(bytes,bytes32,bytes): head = [offset_to_proof=96][channelId32]
     * [offset_to_manifestProof]; tails = [proof_len][proof_padded] then [manifest_len][manifest_padded].
     */
    @NonNull
    private static byte[] encodeVerifyConfigV3Call(
            @NonNull final byte[] selector,
            @NonNull final Bytes proofBytes,
            @NonNull final Bytes channelId,
            @NonNull final Bytes manifestProofBytes) {
        final byte[] proof = proofBytes.toByteArray();
        final byte[] manifestProof = manifestProofBytes.toByteArray();
        final byte[] connId32 = new byte[32];
        final byte[] connIdRaw = channelId.toByteArray();
        System.arraycopy(connIdRaw, 0, connId32, 0, Math.min(connIdRaw.length, 32));
        final int proofPadded = ((proof.length + 31) / 32) * 32;
        final int manifestPadded = ((manifestProof.length + 31) / 32) * 32;
        // Three head words: offset(proof), channelId (static), offset(manifestProof).
        final int offsetProof = 96;
        final int offsetManifest = offsetProof + 32 + proofPadded;
        final var buf = ByteBuffer.allocate(4 + 96 + 32 + proofPadded + 32 + manifestPadded);
        buf.put(selector);
        putUint256(buf, offsetProof);
        buf.put(connId32); // bytes32 channelId (static, inline in head)
        putUint256(buf, offsetManifest);
        putUint256(buf, proof.length);
        buf.put(proof);
        if (proofPadded > proof.length) buf.put(new byte[proofPadded - proof.length]);
        putUint256(buf, manifestProof.length);
        buf.put(manifestProof);
        if (manifestPadded > manifestProof.length) buf.put(new byte[manifestPadded - manifestProof.length]);
        return buf.array();
    }

    /**
     * ABI-encodes verifyBundle(bytes,bytes,bytes): three dynamic args.
     */
    @NonNull
    private static byte[] encodeThreeBytesCall(
            @NonNull final byte[] selector, @NonNull final Bytes a, @NonNull final Bytes b, @NonNull final Bytes c) {
        final byte[] aBytes = a.toByteArray(), bBytes = b.toByteArray(), cBytes = c.toByteArray();
        final int aPadded = ((aBytes.length + 31) / 32) * 32;
        final int bPadded = ((bBytes.length + 31) / 32) * 32;
        final int cPadded = ((cBytes.length + 31) / 32) * 32;
        final int offsetA = 96;
        final int offsetB = offsetA + 32 + aPadded;
        final int offsetC = offsetB + 32 + bPadded;
        final var buf = ByteBuffer.allocate(4 + 96 + 32 + aPadded + 32 + bPadded + 32 + cPadded);
        buf.put(selector);
        putUint256(buf, offsetA);
        putUint256(buf, offsetB);
        putUint256(buf, offsetC);
        putUint256(buf, aBytes.length);
        buf.put(aBytes);
        if (aPadded > aBytes.length) buf.put(new byte[aPadded - aBytes.length]);
        putUint256(buf, bBytes.length);
        buf.put(bBytes);
        if (bPadded > bBytes.length) buf.put(new byte[bPadded - bBytes.length]);
        putUint256(buf, cBytes.length);
        buf.put(cBytes);
        if (cPadded > cBytes.length) buf.put(new byte[cPadded - cBytes.length]);
        return buf.array();
    }

    /**
     * Dispatches call data to the Channel's verifier contract and returns the raw EVM result bytes.
     */
    @NonNull
    private Bytes dispatchRaw(
            @NonNull final byte[] callData,
            @NonNull final ResponseCodeEnum failureCode,
            @NonNull final HandleContext context)
            throws HandleException {
        final var gasLimit =
                context.configuration().getConfigData(ClprConfig.class).verifierGasLimit();
        final var functionParameters = Bytes.wrap(callData);
        final var syntheticBody = TransactionBody.newBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(verifierContract)
                        .gas(gasLimit)
                        .functionParameters(functionParameters)
                        .build())
                .build();
        final var syntheticBodyBytes = TransactionBody.PROTOBUF.toBytes(syntheticBody);
        log.debug(
                "[EvmClprVerifier] dispatchRaw ENTER: contract={} gasLimit={} selector={} callDataBytes={} syntheticBodyBytes={} failureCode={}",
                verifierContract,
                gasLimit,
                Bytes.wrap(Arrays.copyOf(callData, Math.min(4, callData.length))),
                callData.length,
                syntheticBodyBytes.length(),
                failureCode);
        final var result = context.dispatch(stepDispatch(
                context.payer(),
                syntheticBody,
                HookDispatchStreamBuilder.class,
                NOOP_SIGNED_TX_CUSTOMIZER,
                REMOVABLE,
                CLPR_DISPATCH_METADATA,
                DISPATCH_ONLY_NOOP_FEE_CHARGING));
        log.debug(
                "[EvmClprVerifier] dispatchRaw RESULT: contract={} status={} evmResult={}",
                verifierContract,
                result.status(),
                evmResultSummary(result));
        if (result.status() != SUCCESS) {
            log.warn(
                    "Verifier dispatchRaw failed: contract={} status={} (translated to {}) payer={} gasLimit={}",
                    verifierContract,
                    result.status(),
                    failureCode,
                    context.payer(),
                    gasLimit);
            throw new HandleException(failureCode);
        }
        final var evmResult = result.getEvmCallResult();
        if (evmResult == null || evmResult.length() == 0) {
            log.warn("[EvmClprVerifier] dispatchRaw returned empty EVM result (throwing {})", failureCode);
            throw new HandleException(failureCode);
        }
        log.debug(
                "[EvmClprVerifier] dispatchRaw EXIT: evmResultBytes={} evmResultHash={}",
                evmResult.length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(evmResult)));
        return evmResult;
    }

    /**
     * Decodes the mainline V2 (context) return into a {@link ClprLedgerConfiguration}, endpoints included
     * (member 7 is {@code Endpoint[] seedEndpoints}, 5-field Throttles).
     */
    @NonNull
    private static ClprLedgerConfiguration decodeVerifyConfigV2Return(
            @NonNull final Bytes rawEvmBytes, @NonNull final ResponseCodeEnum failureCode) throws HandleException {
        try {
            final var decoded = VERIFY_CONFIG_V2_RETURN.decode(ByteBuffer.wrap(rawEvmBytes.toByteArray()));
            final String chainId = decoded.get(1);
            final byte[] serviceAddressBytes = decoded.get(2);
            final BigInteger peerConfigNanos96 = decoded.get(3);
            final Tuple throttlesTuple = decoded.get(4);
            final byte[] initialTrustAnchorBytes = decoded.get(5);
            final byte[] initialTrustAnchorIdBytes = decoded.get(6);
            final Tuple[] endpointTuples = decoded.get(7);

            final long nanos = peerConfigNanos96.longValueExact();
            final var timestamp = Timestamp.newBuilder()
                    .seconds(nanos / 1_000_000_000L)
                    .nanos((int) (nanos % 1_000_000_000L))
                    .build();
            final var throttles = ClprThrottles.newBuilder()
                    .maxMessagesPerBundle(((BigInteger) throttlesTuple.get(0)).intValue())
                    .maxMessagePayloadBytes(((BigInteger) throttlesTuple.get(1)).intValue())
                    .maxGasPerMessage(((BigInteger) throttlesTuple.get(2)).longValue())
                    .maxQueueDepth(((BigInteger) throttlesTuple.get(3)).intValue())
                    .maxSyncBytes(((BigInteger) throttlesTuple.get(4)).longValue())
                    .build();
            final List<ClprEndpoint> endpointList = new ArrayList<>(endpointTuples.length);
            for (final Tuple ep : endpointTuples) {
                endpointList.add(ClprEndpoint.newBuilder()
                        .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                                .ipAddress(ep.get(0))
                                .port(((Long) ep.get(1)).intValue())
                                .build())
                        .tlsCertificate(Bytes.wrap((byte[]) ep.get(2)))
                        .accountId(Bytes.wrap((byte[]) ep.get(3)))
                        .build());
            }
            return ClprLedgerConfiguration.newBuilder()
                    .chainId(chainId)
                    .serviceAddress(Bytes.wrap(serviceAddressBytes))
                    .timestamp(timestamp)
                    .throttles(throttles)
                    .endpoints(endpointList)
                    .initialTrustAnchor(Bytes.wrap(initialTrustAnchorBytes))
                    .initialTrustAnchorId(Bytes.wrap(initialTrustAnchorIdBytes))
                    .build();
        } catch (final Exception e) {
            log.warn(
                    "[EvmClprVerifier] decodeVerifyConfigV2Return failed: rawBytes={} ({})",
                    rawEvmBytes.length(),
                    e.getMessage());
            throw new HandleException(failureCode);
        }
    }

    /**
     * Decodes the V3 (context + manifest) return into a {@link VerifiedConfig}: config fields (7-field
     * Throttles, no endpoints) plus the manifest as the final tuple member.
     */
    @NonNull
    private static VerifiedConfig decodeVerifyConfigV3Return(
            @NonNull final Bytes rawEvmBytes, @NonNull final ResponseCodeEnum failureCode) throws HandleException {
        try {
            final var decoded =
                    ClprVerifierAbi.VERIFY_CONFIG_V3_RETURN.decode(ByteBuffer.wrap(rawEvmBytes.toByteArray()));
            // index 0: channelContext bytes → IGNORED; Java builds its own from known channelId
            final String chainId = decoded.get(1);
            final byte[] serviceAddressBytes = decoded.get(2);
            final BigInteger peerConfigNanos96 = decoded.get(3);
            final Tuple throttlesTuple = decoded.get(4);
            final byte[] initialTrustAnchorBytes = decoded.get(5);
            final byte[] initialTrustAnchorIdBytes = decoded.get(6);
            final Tuple manifestTuple = decoded.get(7);

            final long nanos = peerConfigNanos96.longValueExact();
            final var timestamp = Timestamp.newBuilder()
                    .seconds(nanos / 1_000_000_000L)
                    .nanos((int) (nanos % 1_000_000_000L))
                    .build();

            // Throttles tuple (uint32,uint64,uint64,uint32,uint64,uint32,uint32); headlong maps
            // uint32 -> Long and uint64 -> BigInteger.
            final var throttles = ClprThrottles.newBuilder()
                    .maxMessagesPerBundle(((Long) throttlesTuple.get(0)).intValue())
                    .maxMessagePayloadBytes(((BigInteger) throttlesTuple.get(1)).intValue())
                    .maxGasPerMessage(((BigInteger) throttlesTuple.get(2)).longValue())
                    .maxQueueDepth(((Long) throttlesTuple.get(3)).intValue())
                    .maxSyncBytes(((BigInteger) throttlesTuple.get(4)).longValue())
                    .maxLocalEndpoints(((Long) throttlesTuple.get(5)).intValue())
                    .maxPeerEndpoints(((Long) throttlesTuple.get(6)).intValue())
                    .build();

            // Endpoints now live in the ClprEndpointManifest (config.endpoints is deprecated and left empty).
            final var config = ClprLedgerConfiguration.newBuilder()
                    .chainId(chainId)
                    .serviceAddress(Bytes.wrap(serviceAddressBytes))
                    .timestamp(timestamp)
                    .throttles(throttles)
                    .initialTrustAnchor(Bytes.wrap(initialTrustAnchorBytes))
                    .initialTrustAnchorId(Bytes.wrap(initialTrustAnchorIdBytes))
                    .build();

            final var manifest = decodeManifestTuple(manifestTuple);
            return new VerifiedConfig(config, manifest);
        } catch (final Exception e) {
            log.warn(
                    "[EvmClprVerifier] decodeVerifyConfigV2Return failed: rawBytes={} ({})",
                    rawEvmBytes.length(),
                    e.getMessage());
            throw new HandleException(failureCode);
        }
    }

    /**
     * Decodes an ABI ClprEndpointManifest struct tuple {@code (uint64 version, bytes serviceAddress,
     * (string,uint32,bytes,bytes)[] endpoints)} into a {@link ClprEndpointManifest}.
     */
    @NonNull
    private static ClprEndpointManifest decodeManifestTuple(@NonNull final Tuple manifestTuple) {
        final long version = ((BigInteger) manifestTuple.get(0)).longValue();
        final byte[] manifestServiceAddress = manifestTuple.get(1);
        final Tuple[] endpointTuples = manifestTuple.get(2);
        final List<ClprEndpoint> endpointList = new ArrayList<>(endpointTuples.length);
        for (final Tuple ep : endpointTuples) {
            endpointList.add(ClprEndpoint.newBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress(ep.get(0))
                            .port(((Long) ep.get(1)).intValue())
                            .build())
                    .tlsCertificate(Bytes.wrap((byte[]) ep.get(2)))
                    .accountId(Bytes.wrap((byte[]) ep.get(3)))
                    .build());
        }
        return ClprEndpointManifest.newBuilder()
                .version(version)
                .serviceAddress(Bytes.wrap(manifestServiceAddress))
                .endpoints(endpointList)
                .build();
    }

    @NonNull
    private static ClprBundleContent decodeVerifyBundleV2Return(
            @NonNull final Bytes rawEvmBytes, @NonNull final ResponseCodeEnum failureCode) throws HandleException {
        try {
            final var decoded = VERIFY_BUNDLE_V2_RETURN.decode(ByteBuffer.wrap(rawEvmBytes.toByteArray()));
            final Tuple metaTuple = decoded.get(0);
            final byte[][] messagePayloadArrays = decoded.get(1);
            final byte[] newTrustAnchorBytes = decoded.get(2);
            final byte[] newTrustAnchorIdBytes = decoded.get(3);

            final long nextMessageId = ((BigInteger) metaTuple.get(0)).longValue();
            final byte[] sentRunningHash = metaTuple.get(1); // bytes32
            final long receivedMessageId = ((BigInteger) metaTuple.get(2)).longValue();
            final byte[] receivedRunningHash = metaTuple.get(3); // bytes32
            final int statusOrdinal = metaTuple.get(4); // uint8

            final List<ClprMessagePayload> messages = new ArrayList<>(messagePayloadArrays.length);
            for (final byte[] msgBytes : messagePayloadArrays) {
                messages.add(ClprMessagePayload.PROTOBUF.parseStrict(
                        Bytes.wrap(msgBytes).toReadableSequentialData()));
            }

            final var builder = ClprBundleContent.newBuilder()
                    .messages(messages)
                    .newTrustAnchor(Bytes.wrap(newTrustAnchorBytes))
                    .newTrustAnchorId(Bytes.wrap(newTrustAnchorIdBytes));
            // Metadata-absent sentinel (nextMessageId == 0): a trust-anchor rotation relayed as a V2 tuple
            // carries no queue metadata. Leave it unset so the handler takes its state-update-only path —
            // mirrors decodeVerifyBundleV3Return (§8.1.4).
            if (!ClprVerifierAbi.isMetadataAbsent(metaTuple)) {
                builder.metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(nextMessageId)
                        .sentRunningHash(Bytes.wrap(sentRunningHash))
                        .receivedMessageId(receivedMessageId)
                        .receivedRunningHash(Bytes.wrap(receivedRunningHash))
                        .status(ClprChannelStatus.fromProtobufOrdinal(statusOrdinal))
                        .build());
            }
            return builder.build();
        } catch (final Exception e) {
            log.warn(
                    "[EvmClprVerifier] decodeVerifyBundleV2Return failed: rawBytes={} ({})",
                    rawEvmBytes.length(),
                    e.getMessage());
            throw new HandleException(failureCode);
        }
    }

    /**
     * Decodes the V3 (manifest-aware) bundle return: the V2 members plus a trailing
     * {@code newEndpointManifest} (§4.2 Step 1b). A {@code version == 0} manifest is "absent" and
     * left off the returned content so the handler's version-advance check treats it as no update.
     */
    @NonNull
    private static ClprBundleContent decodeVerifyBundleV3Return(
            @NonNull final Bytes rawEvmBytes, @NonNull final ResponseCodeEnum failureCode) throws HandleException {
        try {
            final var decoded =
                    ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(ByteBuffer.wrap(rawEvmBytes.toByteArray()));
            final Tuple metaTuple = decoded.get(0);
            final byte[][] messagePayloadArrays = decoded.get(1);
            final byte[] newTrustAnchorBytes = decoded.get(2);
            final byte[] newTrustAnchorIdBytes = decoded.get(3);
            final Tuple newManifestTuple = decoded.get(4);

            final long nextMessageId = ((BigInteger) metaTuple.get(0)).longValue();
            final byte[] sentRunningHash = metaTuple.get(1);
            final long receivedMessageId = ((BigInteger) metaTuple.get(2)).longValue();
            final byte[] receivedRunningHash = metaTuple.get(3);
            final int statusOrdinal = metaTuple.get(4);

            final List<ClprMessagePayload> messages = new ArrayList<>(messagePayloadArrays.length);
            for (final byte[] msgBytes : messagePayloadArrays) {
                messages.add(ClprMessagePayload.PROTOBUF.parseStrict(
                        Bytes.wrap(msgBytes).toReadableSequentialData()));
            }

            final var builder = ClprBundleContent.newBuilder()
                    .messages(messages)
                    .newTrustAnchor(Bytes.wrap(newTrustAnchorBytes))
                    .newTrustAnchorId(Bytes.wrap(newTrustAnchorIdBytes));
            // Metadata-absent sentinel: nextMessageId == 0 marks a state-update-only bundle — a
            // manifest-only recovery (spec §8.1.4) or a trust-anchor rotation. A normal bundle's
            // nextMessageId is always >= 1 (= ackedMessageId + 1 + messages.size()), so this is
            // unambiguous. Leave metadata unset — the handler then takes its state-update-only path.
            if (!ClprVerifierAbi.isMetadataAbsent(metaTuple)) {
                builder.metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(nextMessageId)
                        .sentRunningHash(Bytes.wrap(sentRunningHash))
                        .receivedMessageId(receivedMessageId)
                        .receivedRunningHash(Bytes.wrap(receivedRunningHash))
                        .status(ClprChannelStatus.fromProtobufOrdinal(statusOrdinal))
                        .build());
            }
            final var manifest = decodeManifestTuple(newManifestTuple);
            // version 0 = absent: leave newEndpointManifest unset so the handler applies no update.
            if (manifest.version() > 0L) {
                builder.newEndpointManifest(manifest);
            }
            return builder.build();
        } catch (final Exception e) {
            log.warn(
                    "[EvmClprVerifier] decodeVerifyBundleV3Return failed: rawBytes={} ({})",
                    rawEvmBytes.length(),
                    e.getMessage());
            throw new HandleException(failureCode);
        }
    }

    private static void putUint256(@NonNull final ByteBuffer buf, final int value) {
        buf.put(new byte[28]);
        buf.putInt(value);
    }

    private static String shortHex(@NonNull final Bytes bytes) {
        final var hex = bytes.toHex();
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
    }

    @NonNull
    private static String evmResultSummary(@NonNull final HookDispatchStreamBuilder result) {
        try {
            final var evmResult = result.getEvmCallResult();
            if (evmResult == null) {
                return "null";
            }
            return "bytes="
                    + evmResult.length()
                    + " hash="
                    + shortHex(MiscCryptoUtils.keccak256DigestOf(evmResult))
                    + " prefix="
                    + shortHex(evmResult);
        } catch (final RuntimeException e) {
            return "unavailable(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }
}
