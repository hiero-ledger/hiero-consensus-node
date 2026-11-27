// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.VERIFY_BUNDLE_RETURN;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.metadataTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofException;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyBundle(bytes bundlePayload, bytes trustAnchor, bytes channelContext)} for the Canton
 * verifier system contract (EVM address {@code 0x173}).
 *
 * <p>The proof is checked by {@link CantonProofVerifier#verifyBundle(byte[], byte[], byte[],
 * CantonProofVerifier.OutboundLookup)}. A Canton channel is bound to one app party, which alone signs the channel, so
 * a REPLY is only accepted if it answers an outbound DATA message that this ledger sent to that app. The verifier
 * reads that message through the {@link CantonProofVerifier.OutboundLookup} built here from
 * {@code readableMessageQueueStore()}, which works in both handle and query contexts, so consensus verification and
 * local prevalidation ({@code ContractCallLocal}) apply the same check. The verified content is encoded with the same
 * return tuple as the other verifiers.
 */
public class CantonVerifyBundleCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(CantonVerifyBundleCall.class);
    /** A flat placeholder, as for the Ethereum verifier; not calibrated to Canton's actual cost. */
    private static final long GAS_REQUIREMENT = 100_000L;

    private final byte[] bundlePayload;
    private final byte[] trustAnchor;
    private final byte[] channelContext;

    public CantonVerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            @NonNull final byte[] channelContext) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = requireNonNull(channelContext);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.info(
                "[CantonVerifier] verifyBundle ENTER: bundlePayload={} bytes, trustAnchor={} bytes",
                bundlePayload.length,
                trustAnchor.length);
        final ClprBundleContent content;
        try {
            content = CantonProofVerifier.verifyBundle(bundlePayload, trustAnchor, channelContext, this::findOutbound);
        } catch (final CantonProofException e) {
            log.info("[CantonVerifier] verifyBundle: proof rejected: {}", e.getMessage());
            return fail();
        } catch (final RuntimeException e) {
            log.warn("[CantonVerifier] verifyBundle: unexpected error", e);
            return fail();
        }
        return success(content);
    }

    @VisibleForTesting
    @NonNull
    byte[] bundlePayload() {
        return bundlePayload.clone();
    }

    @VisibleForTesting
    @NonNull
    byte[] trustAnchor() {
        return trustAnchor.clone();
    }

    @VisibleForTesting
    @NonNull
    byte[] channelContext() {
        return channelContext.clone();
    }

    /**
     * This ledger's live outbound message {@code messageId} on the channel, as the reference verifier reads it with
     * {@code getMessage} on the calling CLPR service: {@code null} if the queue holds none, or holds it without a
     * payload.
     *
     * @throws CantonProofException {@code ServiceCallFailed} if the message queue cannot be read
     */
    @Nullable
    private ClprMessagePayload findOutbound(@NonNull final byte[] channelId, final long messageId) {
        final ReadableMessageQueueStore queue;
        try {
            queue = nativeOperations().readableMessageQueueStore();
        } catch (final RuntimeException e) {
            final var failure = CantonProofException.serviceCallFailed("message queue unavailable");
            failure.initCause(e);
            throw failure;
        }
        final var stored = queue.getMessage(Bytes.wrap(channelId), messageId);
        return stored == null || !stored.hasPayload() ? null : stored.payload();
    }

    @NonNull
    private PricedResult success(@NonNull final ClprBundleContent content) {
        final ClprQueueMetadata meta = content.metadataOrElse(ClprQueueMetadata.DEFAULT);
        final Tuple metaTuple = metadataTuple(meta);
        if (metaTuple == null) {
            log.warn("[CantonVerifier] verifyBundle: queue metadata is not ABI-encodable");
            return fail();
        }
        final byte[][] messages = content.messages().stream()
                .map(msg -> ClprMessagePayload.PROTOBUF.toBytes(msg).toByteArray())
                .toArray(byte[][]::new);
        final ClprEndpointManifest manifest = content.newEndpointManifestOrElse(ClprEndpointManifest.DEFAULT);
        log.info(
                "[CantonVerifier] verifyBundle EXIT: SUCCESS messages={} nextMessageId={} newTrustAnchor={} bytes"
                        + " manifestVersion={}",
                messages.length,
                meta.nextMessageId(),
                content.newTrustAnchor().length(),
                manifest.version());
        return gasOnly(
                successResult(
                        VERIFY_BUNDLE_RETURN.encode(Tuple.of(
                                metaTuple,
                                messages,
                                content.newTrustAnchor().toByteArray(),
                                content.newTrustAnchorId().toByteArray(),
                                manifestStructTuple(manifest))),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_BUNDLE_VERIFICATION_FAILED, GAS_REQUIREMENT),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                false);
    }
}
