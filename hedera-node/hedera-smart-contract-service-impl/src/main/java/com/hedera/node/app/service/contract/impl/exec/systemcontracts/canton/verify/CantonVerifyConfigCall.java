// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify;

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
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofException;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyConfig(bytes,bytes32,bytes)} for the Canton verifier system contract (EVM address
 * {@code 0x173}).
 *
 * <p>The config proof and the optional endpoint-manifest proof are checked by
 * {@link CantonProofVerifier#verifyConfig(byte[], byte[], byte[], byte[])}. The proven {@code ChannelConfig} must
 * name this ledger, so the call reads this ledger's chain id from the CLPR ledger configuration through
 * {@code readableLedgerConfigurationStore()}, which works in both handle and query contexts. The result is encoded
 * with the same return tuple as the other verifiers.
 */
public class CantonVerifyConfigCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(CantonVerifyConfigCall.class);
    /** A flat placeholder, as for the Ethereum verifier's bundle call; not calibrated to Canton's actual cost. */
    private static final long GAS_REQUIREMENT = 100_000L;

    private final byte[] configProof;
    private final byte[] channelId32;
    private final byte[] manifestProof;

    public CantonVerifyConfigCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] configProof,
            @NonNull final byte[] channelId32,
            @NonNull final byte[] manifestProof) {
        super(gasCalculator, enhancement, true);
        this.configProof = requireNonNull(configProof);
        this.channelId32 = requireNonNull(channelId32);
        this.manifestProof = requireNonNull(manifestProof);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.info(
                "[CantonVerifier] verifyConfig ENTER: configProof={} bytes, manifestProof={} bytes",
                configProof.length,
                manifestProof.length);
        final byte[] localChainId = localChainId();
        if (localChainId == null) {
            return fail();
        }
        final VerifiedConfig verified;
        try {
            verified = CantonProofVerifier.verifyConfig(configProof, channelId32, manifestProof, localChainId);
        } catch (final CantonProofException e) {
            log.info("[CantonVerifier] verifyConfig: config rejected: {}", e.getMessage());
            return fail();
        } catch (final RuntimeException e) {
            log.warn("[CantonVerifier] verifyConfig: unexpected error", e);
            return fail();
        }
        return success(verified.config(), verified.manifest());
    }

    /** This ledger's CAIP-2 chain id as UTF-8, or {@code null} if the ledger configuration cannot be read. */
    @Nullable
    private byte[] localChainId() {
        try {
            return nativeOperations()
                    .readableLedgerConfigurationStore()
                    .getConfiguration()
                    .chainId()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (final RuntimeException e) {
            log.warn("[CantonVerifier] verifyConfig: ledger configuration unavailable", e);
            return null;
        }
    }

    @NonNull
    private PricedResult success(
            @NonNull final ClprLedgerConfiguration config, @NonNull final ClprEndpointManifest manifest) {
        final byte[] serviceAddress = config.serviceAddress().toByteArray();
        final byte[] channelContext = new byte[channelId32.length + serviceAddress.length];
        System.arraycopy(channelId32, 0, channelContext, 0, channelId32.length);
        System.arraycopy(serviceAddress, 0, channelContext, channelId32.length, serviceAddress.length);

        // headlong requires a Long for each uint32 throttle field and a BigInteger for each uint64 field.
        final ClprThrottles t = config.throttlesOrElse(ClprThrottles.DEFAULT);
        final Tuple throttles = Tuple.from(
                Long.valueOf(t.maxMessagesPerBundle()),
                BigInteger.valueOf(t.maxMessagePayloadBytes()),
                BigInteger.valueOf(t.maxGasPerMessage()),
                Long.valueOf(t.maxQueueDepth()),
                BigInteger.valueOf(t.maxSyncBytes()),
                Long.valueOf(t.maxLocalEndpoints()),
                Long.valueOf(t.maxPeerEndpoints()));
        final var ts = config.timestamp();
        final long peerConfigNanos = ts != null ? ts.seconds() * 1_000_000_000L + ts.nanos() : 0L;

        log.info(
                "[CantonVerifier] verifyConfig EXIT: SUCCESS chainId={} manifestVersion={} manifestEndpoints={}",
                config.chainId(),
                manifest.version(),
                manifest.endpoints().size());
        return gasOnly(
                successResult(
                        CantonVerifyConfigTranslator.VERIFY_CONFIG
                                .getOutputs()
                                .encode(Tuple.from(
                                        channelContext,
                                        config.chainId(),
                                        serviceAddress,
                                        BigInteger.valueOf(peerConfigNanos),
                                        throttles,
                                        config.initialTrustAnchor().toByteArray(),
                                        config.initialTrustAnchorId().toByteArray(),
                                        manifestStructTuple(manifest))),
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
