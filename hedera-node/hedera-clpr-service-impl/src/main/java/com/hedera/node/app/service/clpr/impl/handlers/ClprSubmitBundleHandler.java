// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_DECODE_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INTERNAL_STATE_CORRUPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_QUEUE_FULL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.MIN_PROOF_OVERHEAD_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.VERIFIABLE_SYNC_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.senderPayloadBudget;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.ConsensusThrottling.ON;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CONTRACT_GAS_USAGE;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.EXPLICIT_WRITE_TRACING;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConfigUpdate;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator.ReplyValidation;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator.ValidatedBundle;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy;
import com.hedera.node.app.service.clpr.impl.ClprServiceApiImpl;
import com.hedera.node.app.service.clpr.impl.ClprSlashingUtils;
import com.hedera.node.app.service.clpr.impl.ClprThrottlesUtils;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.concurrent.throttle.RateLimitedLogger;

/**
 * Handles {@link com.hedera.hapi.node.base.HederaFunctionality#CLPR_SUBMIT_BUNDLE} transactions.
 *
 * <p>Processes a bundle of cross-ledger messages from a peer ledger. The bundle contains
 * a proof (verified by the Channel's verifier contract) that attests to the peer's
 * queue state and the messages included. After verification, the handler updates
 * acknowledgements, processes each message by type, and maintains running hash integrity.
 */
@Singleton
public class ClprSubmitBundleHandler extends AbstractClprHandler {

    private static final Logger log = LogManager.getLogger(ClprSubmitBundleHandler.class);
    private static final Marker CLPR_MARKER = MarkerManager.getMarker("CLPR");

    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    private static final int ABI_WORD_BYTES = 32;
    private static final Bytes APPLICATION_RESPONSE_OFFSET = Bytes.wrap(ByteBuffer.allocate(ABI_WORD_BYTES)
            .putInt(ABI_WORD_BYTES - Integer.BYTES, ABI_WORD_BYTES)
            .array());
    private static final TupleType<?> APPLICATION_RESPONSE_TYPE = TupleType.parse("(bytes)");

    /**
     * Function selector for onClprMessage(bytes32,bytes,bytes) = 0x2ab00809
     */
    private static final byte[] ON_CLPR_MESSAGE_SELECTOR = new byte[] {0x2a, (byte) 0xb0, 0x08, 0x09};

    /** Function selector for onClprResponse(bytes32,uint64,uint8,bytes) = 0x3b74550e */
    private static final byte[] ON_CLPR_RESPONSE_SELECTOR = new byte[] {0x3b, 0x74, 0x55, 0x0e};

    private static final byte[] ON_CLPR_RESPONSE_UNAVAILABLE_SELECTOR =
            new Function("onClprResponseUnavailable(bytes32,uint64,uint8)").selector();

    private static DispatchMetadata callbackMetadata(final ContractGasUsage usage) {
        final var metadata = new DispatchMetadata(CLPR_DISPATCH_METADATA);
        metadata.putMetadata(CONTRACT_GAS_USAGE, usage);
        return metadata;
    }

    // A CLPR transaction may dispatch several EVM calls in one transactional unit, so their written storage
    // slots must be traced explicitly for the block stream to say which call wrote which value
    private static final DispatchMetadata CLPR_DISPATCH_METADATA = new DispatchMetadata(Map.of(
            CLPR_DISPATCH,
            new ClprDispatchMetadata(CLPR_SERVICE_ACCOUNT_ID, CLPR_EVM_ADDRESS_BYTES),
            EXPLICIT_WRITE_TRACING,
            true));

    private final ClprVerifierFactory verifierFactory;
    private final EntityIdFactory entityIdFactory;
    private final ClprChannelLifecycle channelLifecycle;
    private final ClprBundleValidator bundleValidator;
    // One bounded logger for the handler, shared across channels and bundles. The next emitted
    // warning reports how many fallback events were suppressed during the previous interval.
    private final RateLimitedLogger minimumReplyLogger;

    @Inject
    public ClprSubmitBundleHandler(
            @NonNull final ClprVerifierFactory verifierFactory,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ClprChannelLifecycle channelLifecycle,
            @NonNull final ClprBundleValidator bundleValidator) {
        this(verifierFactory, entityIdFactory, channelLifecycle, bundleValidator, Time.getCurrent());
    }

    /** Allows a controlled clock for diagnostics; the clock does not affect protocol behavior. */
    public ClprSubmitBundleHandler(
            @NonNull final ClprVerifierFactory verifierFactory,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ClprChannelLifecycle channelLifecycle,
            @NonNull final ClprBundleValidator bundleValidator,
            @NonNull final Time time) {
        this.verifierFactory = requireNonNull(verifierFactory);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.channelLifecycle = requireNonNull(channelLifecycle);
        this.bundleValidator = requireNonNull(bundleValidator);
        minimumReplyLogger = new RateLimitedLogger(log, requireNonNull(time), Duration.ofMinutes(1));
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final var op = context.body().clprSubmitBundleOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        // An empty bundlePayload is a valid pure-ack bundle (no messages, just a metadata ack).
        // Rejecting it here would break legitimate use cases, so we do not validate length > 0.
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {}

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        // Step 1b refreshes the node-local peer endpoint cache. That cache is in-memory, so the full-stack rollback
        // of a later penalizing HandleException would not undo it; the refresh is therefore applied only once the
        // bundle has been handled without an exception.
        final var deferredPeerEndpoints = new DeferredPeerEndpoints();
        final var gasAccounting = ClprGasAccounting.forContext(context);
        try {
            gasAccounting.reserveRollbackFee(
                    context.configuration().getConfigData(ClprConfig.class).endpointMisbehaviorPenaltyTinybars());
            handleBundle(context, deferredPeerEndpoints, gasAccounting);
            gasAccounting.refundUnusedGas();
            deferredPeerEndpoints.seedInto(channelLifecycle);
        } catch (final HandleException failure) {
            throw gasAccounting.preservingChargesOnRollback(failure);
        } catch (final RuntimeException failure) {
            throw ClprGasAccounting.preservingChargesOnRollback(context, failure);
        }
    }

    private void handleBundle(
            @NonNull final HandleContext context,
            @NonNull final DeferredPeerEndpoints deferredPeerEndpoints,
            @NonNull final ClprGasAccounting gasAccounting)
            throws HandleException {
        final var op = context.body().clprSubmitBundleOrThrow();
        final var nodeAccountId = context.body().transactionIDOrThrow().accountIDOrThrow();
        log.debug(
                "[ClprSubmitBundle] doHandle ENTER conn={} bundleBytes={} creatorNode={} payer={}",
                op.channelId(),
                op.bundlePayload().length(),
                context.creatorInfo().nodeId(),
                context.payer());
        final var configuration = context.configuration();
        final var clprConfig = configuration.getConfigData(ClprConfig.class);
        final var penaltyAmount = clprConfig.endpointMisbehaviorPenaltyTinybars();
        final var storeFactory = context.storeFactory();

        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var channelStore = storeFactory.writableStore(WritableChannelStore.class);
        final var messageQueueStore = storeFactory.writableStore(WritableMessageQueueStore.class);

        // --- Step 1: Look up Channel ---
        // Reject any handling if this channel is CLOSED or PENDING. In any other state, this channel will attempt
        // to handle and respond to transactions to help the remote peer also achieve a CLOSED state.
        var channel = requireNonClosedChannel(channelStore, op.channelId());
        final var channelId = channel.channelId();
        log.debug(
                "[ClprSubmitBundle] loaded channel conn={} status={} nextMsgId={} ackedMsgId={} receivedMsgId={} "
                        + "sentRH={} receivedRH={} verifier={}",
                channelId,
                channel.status(),
                channel.nextMessageId(),
                channel.ackedMessageId(),
                channel.receivedMessageId(),
                shortHex(channel.sentRunningHash()),
                shortHex(channel.receivedRunningHash()),
                channel.verifierContractOrThrow());

        // --- Step 3: Load ledger config (throttles used in later checks) ---
        final var configStore = storeFactory.readableStore(ReadableLedgerConfigurationStore.class);
        final var ledgerConfig = configStore.getConfiguration();
        final var throttles = ledgerConfig.throttlesOrThrow();
        log.debug(
                "[ClprSubmitBundle] ledger config conn={} maxSyncBytes={} maxMessagesPerBundle={} "
                        + "maxPayloadBytes={} appDispatchGas={} messageExecutionCost={} endpointMarginPercent={}",
                channelId,
                throttles.maxSyncBytes(),
                throttles.maxMessagesPerBundle(),
                throttles.maxMessagePayloadBytes(),
                throttles.maxGasPerMessage(),
                clprConfig.messageExecutionCost(),
                clprConfig.endpointMarginPercent());

        // Complete node validation before any state mutation or application dispatch. The
        // validator is independent of handling so endpoints can reuse it with locally verified content.
        final var validated = verifyAndValidateBundle(context, channel, ledgerConfig, nodeAccountId, penaltyAmount);
        // An EVM verifier can send DATA in a nested dispatch. Preserve any outbound queue advances.
        channel = requireNonNull(channelStore.getChannel(channelId));
        final var bundleContent = validated.content();
        final var metadata = bundleContent.metadata();
        final var newTrustAnchor = bundleContent.newTrustAnchor();
        final var newTrustAnchorId = bundleContent.newTrustAnchorId();

        // Spec §4.2 Step 1b: apply new_endpoint_manifest, and Step 1c: install successor
        // trust anchor. Applied together in a single copyBuilder / put so a manifest update
        // and a trust-anchor rotation carried by the same bundle are atomic (they're already
        // covered by the same verifyBundle call). All node checks have passed before this write.
        //
        // Step 1b guard rules (per ADR "Propagating Updates Through Bundle Payloads"):
        // - Applied only when newEndpointManifest.version() strictly advances the current
        //   cached version. Absent or stale manifest → silent skip; the bundle is NOT
        //   rejected for this reason alone. Manifest updates are allowed on CLOSING /
        //   DRAINED channels (harmless, keeps the cache current for admin purposes).
        // - Entire manifest replacement; no partial merge.
        final var newEndpointManifest = bundleContent.newEndpointManifest();
        final boolean shouldUpdateManifest = validated.shouldUpdateManifest();
        final boolean shouldUpdateTrustAnchor = newTrustAnchor.length() > 0;
        if (shouldUpdateManifest || shouldUpdateTrustAnchor) {
            var updatedBuilder = channel.copyBuilder();
            if (shouldUpdateManifest) {
                log.debug(
                        "[ClprSubmitBundle] applying new endpoint manifest conn={} version={}->{} entries={} endpoints={}",
                        channelId,
                        channel.endpointManifestVersion(),
                        newEndpointManifest.version(),
                        newEndpointManifest.endpoints().size(),
                        newEndpointManifest.endpoints().stream()
                                .map(e -> {
                                    final var svc = e.serviceEndpoint();
                                    return svc == null ? "?" : svc.ipAddress() + ":" + svc.port();
                                })
                                .collect(Collectors.joining(", ", "[", "]")));
                // Bound the same serialized manifest state as channel completion, including certificate bytes.
                final ClprEndpointManifest storedManifest;
                try {
                    storedManifest =
                            ClprPeerManifestPolicy.boundedManifest(newEndpointManifest, throttles.maxPeerEndpoints());
                } catch (final IllegalArgumentException e) {
                    throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
                }
                final var truncatedEndpoints = storedManifest.endpoints();
                updatedBuilder = updatedBuilder
                        .endpointManifest(storedManifest)
                        .endpointManifestVersion(storedManifest.version());
                // Refresh the orchestrator's node-local peer endpoint cache — deferred until the bundle is
                // handled without an exception (see doHandle).
                deferredPeerEndpoints.set(channelId, truncatedEndpoints);
            }
            if (shouldUpdateTrustAnchor) {
                log.debug(
                        "[ClprSubmitBundle] installing successor trust anchor conn={} trustAnchorLen={} trustAnchorId={}",
                        channelId,
                        newTrustAnchor.length(),
                        shortHex(newTrustAnchorId));
                updatedBuilder = updatedBuilder.trustAnchor(newTrustAnchor).trustAnchorId(newTrustAnchorId);
            }
            channel = updatedBuilder.build();
            channelStore.put(channel);
        }

        // A metadata-less bundle carries only a state-proven update with no queue metadata or
        // messages: a trust-anchor rotation (e.g. a Sei validator-set update) or an endpoint-manifest
        // recovery (spec §8.1.4 manual recovery — the manifest was already applied at Step 1b above).
        // Either is meaningful progress, so persist the successor / manifest above and stop before the
        // metadata-dependent queue checks below.
        if (metadata == null) {
            log.debug(
                    "[ClprSubmitBundle] state-update-only bundle accepted conn={} trustAnchorLen={} manifestUpdated={}",
                    channelId,
                    newTrustAnchor.length(),
                    shouldUpdateManifest);
            return;
        }

        final var expectedFirstId = channel.receivedMessageId() + 1;
        final var peerReceivedMessageId = metadata.receivedMessageId();
        final var oldAckedMessageId = channel.ackedMessageId();

        var currentStatus = channel.status(); // Our current channel status
        final var peerStatus = metadata.status(); // Remote peer state (used in step 10 and step 11)

        // --- Step 8: Read-only outbound queue pre-scan ---
        // Validates that replies in the bundle match our outbound Data messages in order.
        // No mutations — if validation fails and we return early, no state is left inconsistent.
        //
        // Iterate the NEW tail of the bundle only — the replayed prefix was processed in a prior
        // bundle (we already advanced ackedMessageId off any replies it contained, and consuming
        // them again here would mismatch against outbound messages that were already deleted).
        log.debug(
                "[ClprSubmitBundle] step8 response pre-scan conn={} oldAckedMsgId={} peerReceivedMsgId={} "
                        + "newMessages={}",
                channelId,
                oldAckedMessageId,
                peerReceivedMessageId,
                validated.newMessages().size());
        // The local limit is expand-only, so credit advertised under this rule cannot be revoked.
        final int queueCapacity = throttles.maxQueueDepth();
        final ReplyValidation replyValidation;
        try {
            replyValidation = bundleValidator.validateReplies(channel, validated, messageQueueStore, queueCapacity);
        } catch (final PreCheckException e) {
            // Ineffective acknowledgement progress, local corruption and queue backpressure incur no node penalty.
            if (e.responseCode() == CLPR_INTERNAL_STATE_CORRUPTION) {
                // Consensus reads consistent state, so unlike before gossip, a missing slot here is real
                log.error(
                        "[ClprSubmitBundle] step8 outbound queue is missing a slot the peer has received conn={} "
                                + "ackedMsgId={} peerReceivedMsgId={}",
                        channelId,
                        oldAckedMessageId,
                        peerReceivedMessageId);
            }
            throw new HandleException(e.responseCode());
        }
        final long newAckedMessageId;
        final int acceptedMessageCount;
        switch (replyValidation) {
            case ReplyValidation.AckedThrough(final long lastAckedMessageId, final int count) -> {
                newAckedMessageId = lastAckedMessageId;
                acceptedMessageCount = count;
            }
            case ReplyValidation.OrderingViolated(final long expectedMessageId, final long repliedMessageId) -> {
                // A protocol violation by the submitting node, handled by pausing the channel and penalizing it
                log.info(
                        "[ClprSubmitBundle] step8 reply out of order conn={} expectedMsgId={} repliedMsgId={} "
                                + "currentStatus={}",
                        channelId,
                        expectedMessageId,
                        repliedMessageId,
                        currentStatus);
                if (currentStatus == ClprChannelStatus.ACTIVE) {
                    channelStore.put(channel.copyBuilder()
                            .status(ClprChannelStatus.PAUSED)
                            .build());
                    context.tryToCharge(nodeAccountId, penaltyAmount);
                }
                return;
            }
        }
        final boolean hasDeferredMessages =
                acceptedMessageCount < validated.newMessages().size();
        final var newMessages = validated.newMessages().subList(0, acceptedMessageCount);
        final var computedHash = hasDeferredMessages
                ? receivedHashAfterPrefix(channel.receivedRunningHash(), newMessages)
                : validated.receivedRunningHash();
        if (hasDeferredMessages) {
            log.debug(
                    "[ClprSubmitBundle] queue backpressure conn={} acceptedMessages={} deferredMessages={}",
                    channelId,
                    acceptedMessageCount,
                    validated.newMessages().size() - acceptedMessageCount);
        }
        if (newAckedMessageId < peerReceivedMessageId) {
            log.debug(
                    "[ClprSubmitBundle] step8 bundle carries no reply for outboundMsgId={} conn={}; "
                            + "acking through {} and awaiting the rest in a later bundle",
                    newAckedMessageId + 1,
                    channelId,
                    newAckedMessageId);
        }

        // --- Step 9: If PAUSED, transition back to ACTIVE ---
        // Ordering valid (and responses present if was PAUSED) — ensure ACTIVE
        if (currentStatus == ClprChannelStatus.PAUSED) {
            log.debug("[ClprSubmitBundle] step9 resuming paused channel conn={}", channelId);
            currentStatus = ClprChannelStatus.ACTIVE;
        }

        // --- Pass 2: Mutations begin ---

        // Delete acked one-way outbound messages (control, reply). Data slots are deleted later when their inbound
        // reply is processed in Step 10.
        for (long id = oldAckedMessageId + 1; id <= newAckedMessageId; id++) {
            final var msg = messageQueueStore.getMessage(channelId, id);
            if (msg == null) {
                continue;
            }
            final var payload = msg.payload();
            if (payload != null && (payload.hasControl() || payload.hasMessageReply())) {
                messageQueueStore.remove(channelId, id);
            }
        }

        // Lazy config propagation. Determine if the config has changed since the last time we sync'd. If so,
        // enqueue a new config via a control message.
        final var configTimestamp = ledgerConfig.timestamp();
        var lastConfigTimestamp = channel.lastConfigTimestamp();
        final var outbound =
                new OutboundQueue(messageQueueStore, channelStore, channelId, newAckedMessageId, 2L * queueCapacity);
        // Controls use the ordinary half; the other half remains reserved for replies.
        if (isTimestampBefore(lastConfigTimestamp, configTimestamp) && outbound.depth() < queueCapacity) {
            log.debug(
                    "[ClprSubmitBundle] enqueueing config update conn={} lastConfigTimestamp={} newConfigTimestamp={}",
                    channelId,
                    lastConfigTimestamp,
                    configTimestamp);
            final var configUpdate =
                    ClprConfigUpdate.newBuilder().configuration(ledgerConfig).build();
            final var controlMessage =
                    ClprControlMessage.newBuilder().configUpdate(configUpdate).build();
            outbound.enqueue(
                    ClprMessagePayload.newBuilder().control(controlMessage).build());
            lastConfigTimestamp = configTimestamp;
        }

        // --- Step 10: Per-message dispatch ---
        // If the peer has started closing down, transition us to CLOSING now per spec §4.2 step 5a.
        // No new Data Messages from the local application will be accepted, but all inbound
        // Data Messages in this bundle continue to be dispatched normally.
        if ((peerStatus == ClprChannelStatus.CLOSING
                        || peerStatus == ClprChannelStatus.DRAINED
                        || peerStatus == ClprChannelStatus.CLOSED)
                && (currentStatus == ClprChannelStatus.ACTIVE || currentStatus == ClprChannelStatus.PAUSED)) {
            log.debug(
                    "[ClprSubmitBundle] peer requested close/drain conn={} peerStatus={} localStatus={} -> CLOSING",
                    channelId,
                    peerStatus,
                    currentStatus);
            currentStatus = ClprChannelStatus.CLOSING;
        }

        // Nested application callbacks read the channel through the service API. Publish these
        // changes before dispatch so they cannot send after closing or enqueue this config again.
        // Use the latest queue state, including the config message just enqueued above.
        channelStore.put(requireNonNull(channelStore.getChannel(channelId))
                .copyBuilder()
                .status(currentStatus)
                .lastConfigTimestamp(lastConfigTimestamp)
                .build());

        var peerConfigTimestamp = channel.peerConfigTimestamp();
        var peerThrottles = channel.peerThrottles();
        // A later control message can lower the budget used to build proofs for these replies.
        var finalReplyPayloadLimit = Math.min(
                senderPayloadBudget(context.configuration()),
                maxSerializedReplyBytesAfterBundle(throttles, peerThrottles, newMessages));
        if (hasDeferredMessages) {
            finalReplyPayloadLimit = Math.min(
                    finalReplyPayloadLimit,
                    maxSerializedReplyBytesAfterBundle(throttles, peerThrottles, validated.newMessages()));
        }
        var receivedMessageId = channel.receivedMessageId();
        boolean bundleDecodeFailure = false;
        // Iterate the new tail only — replayed slots were dispatched in a prior bundle, so we
        // must not re-dispatch onClprMessage / re-enqueue a Reply for them.
        long pendingCallbacks = newMessages.stream()
                .filter(payload -> payload.hasMessage() || payload.hasMessageReply())
                .count();
        // Reserve before reversible connector reimbursements or slashing payouts can fund the payer.
        // The EVM verifier already reserves this same escrow when verification executes contract code.
        if (pendingCallbacks > 0 && !gasAccounting.reserveParentGas()) {
            throw new HandleException(INSUFFICIENT_PAYER_BALANCE);
        }
        for (int i = 0; i < newMessages.size(); i++) {
            final var payload = newMessages.get(i);
            final long callbackGasLimit;
            if (payload.hasMessage() || payload.hasMessageReply()) {
                callbackGasLimit = Math.min(
                        throttles.maxGasPerMessage(),
                        gasAccounting.availableApplicationGas(Long.MAX_VALUE) / pendingCallbacks);
                pendingCallbacks--;
            } else {
                callbackGasLimit = 0;
            }
            receivedMessageId = expectedFirstId + i;
            log.debug(
                    "[ClprSubmitBundle] step10 slot ENTER conn={} newIndex={} receivedMsgId={} kind={} summary={}",
                    channelId,
                    i,
                    receivedMessageId,
                    payloadKind(payload),
                    payloadSummary(payload));

            try {
                if (payload.hasControl()) {
                    // All new control updates were validated together before mutations began.
                    final var peerConfig =
                            payload.controlOrThrow().configUpdateOrThrow().configuration();
                    log.debug(
                            "[ClprSubmitBundle] step10 CONTROL configUpdate conn={} receivedMsgId={} peerTimestamp={}",
                            channelId,
                            receivedMessageId,
                            peerConfig == null ? null : peerConfig.timestamp());
                    if (peerConfig != null) {
                        peerConfigTimestamp = peerConfig.timestamp();
                        if (peerConfig.throttles() != null) {
                            peerThrottles = peerConfig.throttles();
                        }
                        // Subsequent nested sends must see the config already accepted in this prefix.
                        channelStore.put(requireNonNull(channelStore.getChannel(channelId))
                                .copyBuilder()
                                .peerConfigTimestamp(peerConfigTimestamp)
                                .peerThrottles(peerThrottles)
                                .build());
                    }
                } else if (payload.hasMessage()) {
                    // We must be ACTIVE, CLOSING, or DRAINED (CLOSED channels are rejected at the top of the
                    // handler, and PAUSED channels quit before reaching per-message dispatch)
                    final var dataMsg = payload.messageOrThrow();
                    log.debug(
                            "[ClprSubmitBundle] step10 DATA conn={} receivedMsgId={} connectorId={} "
                                    + "targetApplication={} sender={} messageDataLen={} messageData={}",
                            channelId,
                            receivedMessageId,
                            dataMsg.connectorId(),
                            dataMsg.targetApplication(),
                            dataMsg.sender(),
                            dataMsg.messageData().length(),
                            shortHex(dataMsg.messageData()));
                    final var connectorKey = new ClprConnectorKey(channelId, dataMsg.connectorId());
                    final var connector = connectorStore.getConnector(connectorKey);

                    // If there is no connector, then the connector on the remote peer lied. We will respond to
                    // the remote ledger that this connector is NOT_FOUND and the remote ledger can then reprimand
                    // the remote connector.
                    // rbair23: We also should keep track of this, because if this happens a lot, then maybe it
                    // isn't the remote connector that lied, maybe it was the remote ledger itself, in which case
                    // we probably should take some action because this channel is faulty.
                    if (connector == null) {
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA connector NOT_FOUND conn={} receivedMsgId={} "
                                        + "connectorId={} -> CONNECTOR_NOT_FOUND reply",
                                channelId,
                                receivedMessageId,
                                dataMsg.connectorId());
                        outbound.enqueueReply(
                                receivedMessageId, ClprMessageReplyStatus.CONNECTOR_NOT_FOUND, Bytes.EMPTY);
                    } else {
                        final long gasLimit = callbackGasLimit;
                        if (gasLimit == 0) {
                            // The bundle has spent its transaction-wide gas budget. A later callback
                            // must not consume more shared EVM capacity, even when its connector can pay.
                            outbound.enqueueReply(
                                    receivedMessageId, ClprMessageReplyStatus.APPLICATION_ERROR, Bytes.EMPTY);
                            continue;
                        }
                        // Endpoint compensation remains separate from the network fee for EVM work.
                        final var executionCost = clprConfig.messageExecutionCost();
                        final var margin = Math.multiplyExact(executionCost, clprConfig.endpointMarginPercent()) / 100;
                        final var endpointCharge = Math.addExact(executionCost, margin);
                        final var worstCaseCharge = Math.addExact(endpointCharge, gasAccounting.costForGas(gasLimit));

                        // Validate the connector has sufficient balance before dispatching.
                        // Use getContractById to handle both contractNum and evmAddress forms.
                        final var tokenServiceApi = storeFactory.serviceApi(TokenServiceApi.class);
                        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
                        final var connectorAccount = accountStore.getContractById(connector.connectorContractOrThrow());
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA connector found conn={} receivedMsgId={} "
                                        + "connectorContract={} inFlight={} accountPresent={} balance={} "
                                        + "worstCaseCharge={}",
                                channelId,
                                receivedMessageId,
                                connector.connectorContractOrThrow(),
                                connector.inFlightMessageCount(),
                                connectorAccount != null,
                                connectorAccount == null ? null : connectorAccount.tinybarBalance(),
                                worstCaseCharge);
                        if (connectorAccount == null || connectorAccount.tinybarBalance() < worstCaseCharge) {
                            // Insufficient balance — slash, enqueue CONNECTOR_UNDERFUNDED, skip dispatch
                            log.debug(
                                    "[ClprSubmitBundle] step10 DATA connector underfunded conn={} "
                                            + "receivedMsgId={} connectorContract={} accountPresent={} "
                                            + "balance={} required={} -> CONNECTOR_UNDERFUNDED reply",
                                    channelId,
                                    receivedMessageId,
                                    connector.connectorContractOrThrow(),
                                    connectorAccount != null,
                                    connectorAccount == null ? null : connectorAccount.tinybarBalance(),
                                    worstCaseCharge);
                            final var slashResult = ClprSlashingUtils.applySlash(connector, clprConfig, connectorStore);
                            ClprSlashingUtils.reimburseEndpoint(
                                    slashResult.penaltyAmount(),
                                    context.payer(),
                                    clprConfig,
                                    entityIdFactory,
                                    accountStore,
                                    tokenServiceApi);
                            outbound.enqueueReply(
                                    receivedMessageId, ClprMessageReplyStatus.CONNECTOR_UNDERFUNDED, Bytes.EMPTY);
                            log.debug(
                                    "[ClprSubmitBundle] step10 DATA connector underfunded handling complete conn={} "
                                            + "receivedMsgId={} penalty={}",
                                    channelId,
                                    receivedMessageId,
                                    slashResult.penaltyAmount());
                            continue;
                        }

                        final long replyPayloadLimit =
                                Math.min(finalReplyPayloadLimit, maxSerializedReplyBytes(throttles, peerThrottles));
                        final var emptyError =
                                replyPayload(receivedMessageId, ClprMessageReplyStatus.APPLICATION_ERROR, Bytes.EMPTY);
                        final int minimumReplyBytes = ClprMessagePayload.PROTOBUF.measureRecord(emptyError);
                        if (minimumReplyBytes > replyPayloadLimit) {
                            // The proof allowance is conservative, and a peer may advertise invalid limits.
                            // Keep the hash-chained queue moving with the minimum protocol response, without
                            // dispatching or charging for application work. The proof builder still checks
                            // the actual serialized bundle size before transmission.
                            minimumReplyLogger.warn(
                                    CLPR_MARKER,
                                    "[ClprSubmitBundle] minimum error reply exceeds conservative allowance "
                                            + "(sample across channels) conn={} "
                                            + "receivedMsgId={} minimumReplyBytes={} maxSerializedReplyBytes={}; "
                                            + "skipping application dispatch and enqueueing an empty application error reply",
                                    channelId,
                                    receivedMessageId,
                                    minimumReplyBytes,
                                    replyPayloadLimit);
                            outbound.enqueueReply(
                                    receivedMessageId, ClprMessageReplyStatus.APPLICATION_ERROR, Bytes.EMPTY);
                            continue;
                        }

                        // CEI: debit the connector BEFORE dispatching so that a reentering call
                        // cannot observe unspent balance and dispatch again before the charge lands.
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA debiting connector conn={} receivedMsgId={} "
                                        + "from={} toPayer={} amount={}",
                                channelId,
                                receivedMessageId,
                                connectorAccount.accountIdOrThrow(),
                                context.payer(),
                                worstCaseCharge);
                        tokenServiceApi.transferFromTo(
                                connectorAccount.accountIdOrThrow(), context.payer(), worstCaseCharge);
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA connector debit complete conn={} receivedMsgId={}",
                                channelId,
                                receivedMessageId);

                        final var callData = encodeOnClprMessage(channelId, dataMsg.sender(), dataMsg.messageData());
                        // Use EntityIdFactory so shard/realm match this network
                        final var appContractId =
                                entityIdFactory.newContractIdWithEvmAddress(dataMsg.targetApplication());
                        final var syntheticBody = TransactionBody.newBuilder()
                                .contractCall(ContractCallTransactionBody.newBuilder()
                                        .contractID(appContractId)
                                        // Per-message gas ceiling: spec §1.1
                                        // ClprThrottles.max_gas_per_message governs the
                                        // destination-side callback budget (§6.0 Application
                                        // Delivery). On overrun the dispatch returns
                                        // non-SUCCESS → APPLICATION_ERROR below, with no
                                        // Connector slash (§4.6).
                                        .gas(gasLimit)
                                        .functionParameters(Bytes.wrap(callData))
                                        .build())
                                .build();
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA dispatch prepared conn={} receivedMsgId={} "
                                        + "target={} gas={} callDataLen={} selector={} callData={}",
                                channelId,
                                receivedMessageId,
                                appContractId,
                                gasLimit,
                                callData.length,
                                shortHex(Bytes.wrap(ON_CLPR_MESSAGE_SELECTOR)),
                                shortHex(Bytes.wrap(callData)));

                        ClprMessageReplyStatus replyStatus;
                        Bytes responseData = Bytes.EMPTY;
                        final var gasPayer = connectorAccount.accountIdOrThrow();
                        if (!gasAccounting.prepay(gasPayer, gasLimit)) {
                            throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
                        }
                        final var gasUsage = new ContractGasUsage();
                        final var applicationStack = context.savepointStack();
                        applicationStack.createSavepoint();
                        boolean commitApplication = false;
                        try {
                            log.debug(
                                    "[ClprSubmitBundle] step10 DATA dispatch ENTER conn={} receivedMsgId={} "
                                            + "target={} payer={}",
                                    channelId,
                                    receivedMessageId,
                                    appContractId,
                                    gasPayer);
                            final var result = context.dispatch(stepDispatch(
                                    gasPayer,
                                    syntheticBody,
                                    HookDispatchStreamBuilder.class,
                                    NOOP_SIGNED_TX_CUSTOMIZER,
                                    callbackMetadata(gasUsage),
                                    ON));
                            log.debug(
                                    "[ClprSubmitBundle] step10 DATA dispatch RETURNED conn={} receivedMsgId={} "
                                            + "target={} status={}",
                                    channelId,
                                    receivedMessageId,
                                    appContractId,
                                    result.status());
                            Bytes rawEvmResult = null;
                            try {
                                rawEvmResult = result.getEvmCallResult();
                            } catch (final Exception ignored) {
                                // getEvmCallResult() throws when the child dispatch did not produce
                                // an EVM result record (e.g. the target address has no bytecode).
                                // A successful call without the required bytes-return ABI is invalid and
                                // must be rolled back before reporting application failure.
                                log.debug(
                                        "[ClprSubmitBundle] step10 DATA dispatch produced no EVM result conn={} "
                                                + "receivedMsgId={} target={} reason={}: {}",
                                        channelId,
                                        receivedMessageId,
                                        appContractId,
                                        ignored.getClass().getSimpleName(),
                                        ignored.getMessage());
                            }
                            if (result.status() == SUCCESS) {
                                replyStatus = ClprMessageReplyStatus.SUCCESS;
                            } else {
                                replyStatus = ClprMessageReplyStatus.APPLICATION_ERROR;
                            }
                            log.debug(
                                    "[ClprSubmitBundle] step10 DATA dispatch status mapped conn={} receivedMsgId={} "
                                            + "resultStatus={} replyStatus={} rawEvmResultLen={} rawEvmResult={}",
                                    channelId,
                                    receivedMessageId,
                                    result.status(),
                                    replyStatus,
                                    rawEvmResult == null ? null : rawEvmResult.length(),
                                    shortHex(rawEvmResult));
                            // The EVM serializes the return value of a `bytes memory` function as
                            // ABI-encoded `(bytes)` — i.e. [32B offset][32B length][padded payload].
                            // Unwrap to recover the inner payload so the value travels the bundle as
                            // raw application bytes; otherwise the peer's onClprResponse sees the
                            // double-wrapped form and length-based checks (e.g. drop detection via
                            // `responseData.length == 0`) misfire.
                            if (rawEvmResult != null && rawEvmResult.length() > 0) {
                                try {
                                    final var decoded = decodeApplicationResponse(rawEvmResult, replyPayloadLimit);
                                    if (decoded == null
                                            || ClprMessagePayload.PROTOBUF.measureRecord(
                                                            replyPayload(receivedMessageId, replyStatus, decoded))
                                                    > replyPayloadLimit) {
                                        log.debug(
                                                "[ClprSubmitBundle] oversized application response conn={} "
                                                        + "receivedMsgId={} target={} encodedResponseBytes={} "
                                                        + "maxSerializedReplyBytes={}",
                                                channelId,
                                                receivedMessageId,
                                                appContractId,
                                                rawEvmResult.length(),
                                                replyPayloadLimit);
                                        replyStatus = ClprMessageReplyStatus.APPLICATION_ERROR;
                                    } else {
                                        responseData = decoded;
                                        log.debug(
                                                "[ClprSubmitBundle] step10 DATA ABI unwrap OK conn={} receivedMsgId={} "
                                                        + "responseDataLen={} responseData={}",
                                                channelId,
                                                receivedMessageId,
                                                responseData.length(),
                                                shortHex(responseData));
                                    }
                                } catch (final Exception e) {
                                    log.warn(
                                            "[ClprSubmitBundle] step10 DATA ABI unwrap FAILED conn={} "
                                                    + "receivedMsgId={} target={} rawEvmResultLen={} reason={}: {}; "
                                                    + "treating as APPLICATION_ERROR with empty payload",
                                            channelId,
                                            receivedMessageId,
                                            appContractId,
                                            rawEvmResult.length(),
                                            e.getClass().getSimpleName(),
                                            e.getMessage());
                                    responseData = Bytes.EMPTY;
                                    replyStatus = ClprMessageReplyStatus.APPLICATION_ERROR;
                                }
                            } else if (result.status() == SUCCESS) {
                                replyStatus = ClprMessageReplyStatus.APPLICATION_ERROR;
                            }
                            // The child has committed into this parent savepoint. Keep successful
                            // application effects only if their response can be delivered; otherwise
                            // revert the whole child subtree, including nested CLPR sends. Failed
                            // dispatches already reverted their effects, so no additional parent rollback is needed.
                            commitApplication =
                                    result.status() != SUCCESS || replyStatus == ClprMessageReplyStatus.SUCCESS;
                        } catch (final HandleException e) {
                            log.warn(
                                    "[ClprSubmitBundle] step10 DATA dispatch HandleException conn={} "
                                            + "receivedMsgId={} target={} status={} message={}",
                                    channelId,
                                    receivedMessageId,
                                    appContractId,
                                    e.getStatus(),
                                    e.getMessage());
                            replyStatus = ClprMessageReplyStatus.APPLICATION_ERROR;
                        } finally {
                            try {
                                if (commitApplication) {
                                    applicationStack.commit();
                                } else {
                                    applicationStack.rollback();
                                }
                            } finally {
                                // Prepayment and settlement stay outside the application savepoint.
                                // The contract handler reports gas before rollback can clear its stream
                                // builder, so reverted application work still pays for the gas it executed.
                                final long actualGasFee =
                                        gasAccounting.settleWithGasUsage(gasPayer, gasLimit, gasUsage);
                                final long unusedReimbursement = gasAccounting.costForGas(gasLimit) - actualGasFee;
                                if (unusedReimbursement > 0) {
                                    tokenServiceApi.transferFromTo(context.payer(), gasPayer, unusedReimbursement);
                                }
                            }
                        }

                        // OutboundQueue reads/writes channel state directly from the
                        // store on each enqueue, so any precompile-side bumps from the
                        // dispatched onClprMessage (e.g. PingPong's bounce) are picked up
                        // automatically and our reply lands on the next free slot.
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA enqueueing reply conn={} receivedMsgId={} "
                                        + "replyStatus={} responseDataLen={} responseData={}",
                                channelId,
                                receivedMessageId,
                                replyStatus,
                                responseData.length(),
                                shortHex(responseData));
                        outbound.enqueueReply(receivedMessageId, replyStatus, responseData);
                        log.debug(
                                "[ClprSubmitBundle] step10 DATA reply enqueued conn={} receivedMsgId={} "
                                        + "nextOutboundMsgId={} outboundRH={}",
                                channelId,
                                receivedMessageId,
                                outbound.nextMessageId(),
                                shortHex(outbound.runningHash()));
                    }
                } else if (payload.hasMessageReply()) {
                    // This is a response to a message we sent to the remote peer. We've already exited this method
                    // if PAUSED. In any other state, we should handle these replies.
                    final var reply = payload.messageReplyOrThrow();
                    final var replyTargetId = reply.messageId();
                    log.debug(
                            "[ClprSubmitBundle] step10 MESSAGE_REPLY conn={} receivedMsgId={} replyTargetId={} "
                                    + "status={} replyDataLen={} replyData={}",
                            channelId,
                            receivedMessageId,
                            replyTargetId,
                            reply.status(),
                            reply.messageReplyData().length(),
                            shortHex(reply.messageReplyData()));

                    final var originalMsg = messageQueueStore.getMessage(channelId, replyTargetId);
                    log.debug(
                            "[ClprSubmitBundle] step10 MESSAGE_REPLY original lookup conn={} replyTargetId={} "
                                    + "originalPresent={}",
                            channelId,
                            replyTargetId,
                            originalMsg != null);

                    // It should never be the case that originalMsg, its payload, or its inner message are EVER null.
                    // That should not have been allowed to be enqueued in this way. If something went wrong, we should
                    // log a stern warning and then just ignore the original message and the response.
                    final var originalPayload =
                            originalMsg == null ? null : originalMsg.hasPayload() ? originalMsg.payload() : null;
                    final var origDataMsg = originalPayload == null
                            ? null
                            : originalPayload.hasMessage() ? originalPayload.message() : null;
                    if (origDataMsg == null) {
                        log.debug(
                                "[ClprSubmitBundle] step10 MESSAGE_REPLY has no original DATA message "
                                        + "conn={} replyTargetId={} originalPayloadKind={}; skipping app callback",
                                channelId,
                                replyTargetId,
                                payloadKind(originalPayload));
                        continue;
                    }

                    // One-way slots are removed only when acknowledged in Pass 2.
                    messageQueueStore.remove(channelId, replyTargetId);

                    // A peer can have already queued this reply under an older advertised limit. Keep the
                    // attested payload in the running hash and ACK, but bound what the application receives.
                    final long replyPayloadLimit = throttles.maxMessagePayloadBytes();
                    final var attestedReplyData = reply.messageReplyData();
                    final long serializedReplyBytes = ClprMessagePayload.PROTOBUF.measureRecord(payload);
                    final boolean oversized = replyPayloadLimit > 0 && serializedReplyBytes > replyPayloadLimit;
                    final var replyStatus = reply.status();
                    final var replyData = oversized ? Bytes.EMPTY : attestedReplyData;
                    final var callbackName = oversized ? "onClprResponseUnavailable" : "onClprResponse";
                    if (oversized) {
                        log.debug(
                                "[ClprSubmitBundle] oversized incoming reply conn={} receivedMsgId={} "
                                        + "replyTargetId={} serializedReplyBytes={} maxSerializedReplyBytes={}",
                                channelId,
                                receivedMessageId,
                                replyTargetId,
                                serializedReplyBytes,
                                replyPayloadLimit);
                    }

                    // Deliver response to the originating application (best-effort). The response might even be
                    // CONNECTOR_NOT_FOUND or some other error, or that the remote side is CLOSED. Whatever it is,
                    // we must inform the originating application so it can do proper bookkeeping.
                    final var senderAddress = origDataMsg.sender();
                    final var callbackConnector =
                            connectorStore.getConnector(new ClprConnectorKey(channelId, origDataMsg.connectorId()));
                    final var callbackAccounts = storeFactory.readableStore(ReadableAccountStore.class);
                    final var callbackAccount = callbackConnector == null
                            ? null
                            : callbackAccounts.getContractById(callbackConnector.connectorContractOrThrow());
                    final long gasLimit = callbackGasLimit;
                    if (senderAddress.length() > 0
                            && callbackAccount != null
                            && gasLimit > 0
                            && callbackAccount.tinybarBalance() >= gasAccounting.costForGas(gasLimit)) {
                        // An old application may not implement the unavailable-result hook. Its
                        // failure is best-effort; never retry the ordinary callback with a false
                        // execution-failure status or an indistinguishable empty success result.
                        final var callData = oversized
                                ? encodeOnClprResponseUnavailable(channelId, replyTargetId, replyStatus)
                                : encodeOnClprResponse(channelId, replyTargetId, replyStatus, replyData);
                        // Use EntityIdFactory so shard/realm match this network
                        final var senderContractId = entityIdFactory.newContractIdWithEvmAddress(senderAddress);
                        final var syntheticBody = TransactionBody.newBuilder()
                                .contractCall(ContractCallTransactionBody.newBuilder()
                                        .contractID(senderContractId)
                                        // Response delivery is also an application dispatch (§6.0),
                                        // so it shares the same per-message gas ceiling
                                        // (spec §1.1 max_gas_per_message).
                                        .gas(gasLimit)
                                        .functionParameters(Bytes.wrap(callData))
                                        .build())
                                .build();
                        log.debug(
                                "[ClprSubmitBundle] dispatching {} to sender={} replyTargetId={} "
                                        + "status={} replyDataLen={} callDataLen={} gas={} callData={}",
                                callbackName,
                                senderAddress,
                                replyTargetId,
                                replyStatus,
                                replyData.length(),
                                callData.length,
                                gasLimit,
                                shortHex(Bytes.wrap(callData)));
                        final var callbackGasPayer = callbackAccount.accountIdOrThrow();
                        final var callbackTokenApi = storeFactory.serviceApi(TokenServiceApi.class);
                        final long callbackGasFee = gasAccounting.costForGas(gasLimit);
                        if (callbackGasFee > 0) {
                            callbackTokenApi.transferFromTo(callbackGasPayer, context.payer(), callbackGasFee);
                        }
                        if (!gasAccounting.prepay(callbackGasPayer, gasLimit)) {
                            throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
                        }
                        final var gasUsage = new ContractGasUsage();
                        try {
                            final var callbackResult = context.dispatch(stepDispatch(
                                    callbackAccount.accountIdOrThrow(),
                                    syntheticBody,
                                    HookDispatchStreamBuilder.class,
                                    NOOP_SIGNED_TX_CUSTOMIZER,
                                    callbackMetadata(gasUsage),
                                    ON));
                            log.debug(
                                    "[ClprSubmitBundle] {} dispatch returned to sender={} replyTargetId={} status={}",
                                    callbackName,
                                    senderAddress,
                                    replyTargetId,
                                    callbackResult.status());
                        } catch (final HandleException e) {
                            // Best-effort: callback failure does not stop bundle processing. Log so we can
                            // see when an application's onClprResponse rejected or reverted.
                            log.warn(
                                    "[ClprSubmitBundle] {} dispatch FAILED to sender={} replyTargetId={}: {}",
                                    callbackName,
                                    senderAddress,
                                    replyTargetId,
                                    e.getMessage());
                        } finally {
                            final long actualGasFee =
                                    gasAccounting.settleWithGasUsage(callbackGasPayer, gasLimit, gasUsage);
                            final long unusedReimbursement = callbackGasFee - actualGasFee;
                            if (unusedReimbursement > 0) {
                                callbackTokenApi.transferFromTo(context.payer(), callbackGasPayer, unusedReimbursement);
                            }
                        }
                        // OutboundQueue reads/writes channel state directly from the store,
                        // so any precompile-side bumps from the dispatched onClprResponse
                        // (e.g. PingPong's bounce) are observed automatically on the next enqueue.
                    } else {
                        log.debug(
                                "[ClprSubmitBundle] skipping onClprResponse dispatch — originating msg has no sender "
                                        + "(replyTargetId={} status={})",
                                replyTargetId,
                                replyStatus);
                    }

                    // Source-side slashing on failure responses. That is, if we sent a message to the remote ledger
                    // and it replied indicating the connector on our side was at fault, then we will punish the
                    // connector on our side accordingly.
                    final var sourceConnectorKey = new ClprConnectorKey(channelId, origDataMsg.connectorId());
                    var sourceConnector = connectorStore.getConnector(sourceConnectorKey);
                    if (sourceConnector != null) {
                        // Decrement in-flight counter — this reply is a terminal event for the DATA message.
                        final long current = sourceConnector.inFlightMessageCount();
                        if (current > 0) {
                            sourceConnector = sourceConnector
                                    .copyBuilder()
                                    .inFlightMessageCount(current - 1)
                                    .build();
                            connectorStore.put(sourceConnector);
                            log.debug(
                                    "[ClprSubmitBundle] step10 MESSAGE_REPLY decremented source connector in-flight "
                                            + "conn={} connectorId={} {}->{}",
                                    channelId,
                                    origDataMsg.connectorId(),
                                    current,
                                    current - 1);
                        }

                        // The authenticated peer status determines connector fault even when the local
                        // payload limit requires sanitizing the application callback.
                        if (reply.status() == ClprMessageReplyStatus.CONNECTOR_NOT_FOUND
                                || reply.status() == ClprMessageReplyStatus.CONNECTOR_UNDERFUNDED) {
                            final var slashResult =
                                    ClprSlashingUtils.applySlash(sourceConnector, clprConfig, connectorStore);
                            final var tokenServiceApi = storeFactory.serviceApi(TokenServiceApi.class);
                            final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
                            ClprSlashingUtils.reimburseEndpoint(
                                    slashResult.penaltyAmount(),
                                    context.payer(),
                                    clprConfig,
                                    entityIdFactory,
                                    accountStore,
                                    tokenServiceApi);
                            log.warn(
                                    "[ClprSubmitBundle] step10 MESSAGE_REPLY slashed source connector conn={} "
                                            + "connectorId={} replyStatus={} penalty={}",
                                    channelId,
                                    origDataMsg.connectorId(),
                                    reply.status(),
                                    slashResult.penaltyAmount());
                        }
                    } else {
                        log.warn(
                                "[ClprSubmitBundle] step10 MESSAGE_REPLY source connector missing conn={} "
                                        + "connectorId={} replyTargetId={}",
                                channelId,
                                origDataMsg.connectorId(),
                                replyTargetId);
                    }
                }
                // Empty-oneof payloads with no variant set are not expected; the hash check
                // in Step 6 already rejected them via the chain mismatch path.
            } catch (final HandleException he) {
                log.warn(
                        "[ClprSubmitBundle] step10 HandleException while processing slot conn={} newIndex={} "
                                + "receivedMsgId={} kind={} currentStatus={} reason={}: {}",
                        channelId,
                        i,
                        receivedMessageId,
                        payloadKind(payload),
                        currentStatus,
                        he.getStatus(),
                        he.getMessage(),
                        he);
                throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
            } catch (final RuntimeException e) {
                // A RuntimeException here means either the PBJ decode of a field in this payload threw, or some
                // unexpected state corruption was encountered. Because the verifier already attested to the bundle
                // contents, reaching this branch means the verifier signed off on malformed data. We PAUSE the
                // channel and stop processing remaining messages; the partial state (up to the failed slot)
                // is persisted below before re-throwing as CLPR_BUNDLE_DECODE_FAILED.
                log.warn(
                        "[ClprSubmitBundle] step10 RuntimeException while processing slot conn={} newIndex={} "
                                + "receivedMsgId={} kind={} currentStatus={} reason={}: {}",
                        channelId,
                        i,
                        receivedMessageId,
                        payloadKind(payload),
                        currentStatus,
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
                bundleDecodeFailure = true;
                if (currentStatus == ClprChannelStatus.ACTIVE) {
                    currentStatus = ClprChannelStatus.PAUSED;
                }
                break;
            }
        }

        if (bundleDecodeFailure) {
            // Persist partial state accumulated before the decode failure so that any replies or ack
            // advances that succeeded prior to the failed slot are not lost. The trust anchor was
            // already applied atomically before message dispatch (spec §4.2 Step 1b), so it stays
            // installed on the partial write. Base on the latest channel in the store so any
            // precompile-side bumps from a nested dispatch (e.g. nextMessageId/sentRunningHash)
            // are not overwritten by the original snapshot.
            final var latest = channelStore.getChannel(channelId);
            final var partialBuilder = (latest != null ? latest : channel)
                    .copyBuilder()
                    .peerConfigTimestamp(peerConfigTimestamp)
                    .peerThrottles(peerThrottles)
                    .status(currentStatus)
                    .ackedMessageId(newAckedMessageId)
                    .receivedMessageId(receivedMessageId)
                    .receivedRunningHash(computedHash)
                    .lastConfigTimestamp(lastConfigTimestamp);
            final var partialChannel = partialBuilder.build();
            log.warn(
                    "[ClprSubmitBundle] persisting partial state after decode failure conn={} status={} "
                            + "ackedMsgId={} receivedMsgId={} nextMsgId={} receivedRH={} sentRH={}",
                    channelId,
                    partialChannel.status(),
                    partialChannel.ackedMessageId(),
                    partialChannel.receivedMessageId(),
                    partialChannel.nextMessageId(),
                    shortHex(partialChannel.receivedRunningHash()),
                    shortHex(partialChannel.sentRunningHash()));
            channelStore.put(partialChannel);
            throw new HandleException(CLPR_BUNDLE_DECODE_FAILED);
        }

        // --- Step 11: Update Channel state ---
        // CLOSING → DRAINED: fires when all outbound Data Messages have been acknowledged.
        // Response Messages generated during CLOSING (for the peer's remaining Data Messages) are NOT
        // required to be acknowledged for this transition — they may still be in flight and drain
        // through the DRAINED state.
        boolean dataMessagesDrained = true;
        if (currentStatus == ClprChannelStatus.CLOSING) {
            final long nextMessageId = outbound.nextMessageId();
            for (long id = newAckedMessageId + 1; id < nextMessageId; id++) {
                final var msg = messageQueueStore.getMessage(channelId, id);
                if (msg == null) {
                    continue;
                }
                final var msgPayload = msg.payload();
                if (msgPayload != null && msgPayload.hasMessage()) {
                    dataMessagesDrained = false;
                    break;
                }
            }
        }

        // True when ALL outbound messages (Data + Response) have been acknowledged.
        // Used for the DRAINED → CLOSED transition, which requires the full queue to be empty.
        final var outboundDrained = outbound.nextMessageId() == 0 || newAckedMessageId >= outbound.nextMessageId() - 1;

        log.debug(
                "[ClprSubmitBundle] step11 drain check conn={} currentStatus={} peerStatus={} "
                        + "outboundNextMsgId={} newAckedMsgId={} dataMessagesDrained={} outboundDrained={}",
                channelId,
                currentStatus,
                peerStatus,
                outbound.nextMessageId(),
                newAckedMessageId,
                dataMessagesDrained,
                outboundDrained);

        // CLOSING → DRAINED: all outbound Data Messages acknowledged by peer.
        if (currentStatus == ClprChannelStatus.CLOSING && dataMessagesDrained) {
            currentStatus = ClprChannelStatus.DRAINED;
        }

        // If we have drained, and they have drained, then it means there are no more messages to ever exchange
        // between the two of us. So we are now CLOSED. The ack check ensures we don't transition to CLOSED if
        // Response Messages generated during CLOSING are still unacknowledged.
        if (currentStatus == ClprChannelStatus.DRAINED
                && (peerStatus == ClprChannelStatus.DRAINED || peerStatus == ClprChannelStatus.CLOSED)
                && outboundDrained
                && !hasDeferredMessages) {
            currentStatus = ClprChannelStatus.CLOSED;
        }

        // Base on the latest channel in the store so any precompile-side bumps
        // (nextMessageId, sentRunningHash) from a nested dispatch are preserved.
        // OutboundQueue has been keeping these fields in sync on every enqueue, so they
        // already reflect the final correct values — we just need a fresh snapshot to
        // copyBuilder from.
        final var latest = channelStore.getChannel(channelId);
        final var updatedBuilder = (latest != null ? latest : channel)
                .copyBuilder()
                .peerConfigTimestamp(peerConfigTimestamp)
                .peerThrottles(peerThrottles)
                .status(currentStatus)
                .ackedMessageId(newAckedMessageId)
                .receivedMessageId(receivedMessageId)
                .receivedRunningHash(computedHash)
                .lastConfigTimestamp(lastConfigTimestamp);
        final var updatedChannel = updatedBuilder.build();
        log.debug(
                "[ClprSubmitBundle] final channel update conn={} status={} ackedMsgId={} receivedMsgId={} "
                        + "nextMsgId={} receivedRH={} sentRH={} lastConfigTimestamp={} peerConfigTimestamp={} ",
                channelId,
                updatedChannel.status(),
                updatedChannel.ackedMessageId(),
                updatedChannel.receivedMessageId(),
                updatedChannel.nextMessageId(),
                shortHex(updatedChannel.receivedRunningHash()),
                shortHex(updatedChannel.sentRunningHash()),
                updatedChannel.lastConfigTimestamp(),
                updatedChannel.peerConfigTimestamp());
        channelStore.put(updatedChannel);
    }

    @NonNull
    private static Bytes receivedHashAfterPrefix(
            @NonNull final Bytes priorHash, @NonNull final List<ClprMessagePayload> messages) {
        if (messages.isEmpty()) {
            return priorHash;
        }
        var hash = priorHash.length() == 0 ? ZERO_HASH : priorHash;
        for (final var message : messages) {
            hash = ClprHashUtils.computeRunningHash(hash, message);
        }
        return hash;
    }

    /**
     * ABI-encodes a call to {@code onClprMessage(bytes32,bytes,bytes)}.
     *
     * <p>Layout: 4-byte selector + ABI-encoded (bytes32, bytes, bytes).
     * The bytes32 is static; the two bytes args are dynamic with offset/length/data encoding.
     */
    @NonNull
    private static byte[] encodeOnClprMessage(
            @NonNull final Bytes channelId, @NonNull final Bytes sender, @NonNull final Bytes messageData) {
        final var senderBytes = sender.toByteArray();
        final var msgBytes = messageData.toByteArray();

        // ABI word-aligned sizes
        final int senderPadded = ((senderBytes.length + 31) / 32) * 32;
        final int msgPadded = ((msgBytes.length + 31) / 32) * 32;

        // Total: selector(4) + channelId(32) + senderOffset(32) + msgDataOffset(32)
        //        + senderLen(32) + senderPadded + msgLen(32) + msgPadded
        final int totalSize = 4 + 32 + 32 + 32 + 32 + senderPadded + 32 + msgPadded;
        final var buf = ByteBuffer.allocate(totalSize);

        // Function selector
        buf.put(ON_CLPR_MESSAGE_SELECTOR);

        // bytes32 channelId — static, 32 bytes
        buf.put(channelId.toByteArray());

        // Offset to sender (dynamic) — 3 words past the start of params = 96
        putUint256(buf, 96);

        // Offset to messageData (dynamic) — 96 + 32 + senderPadded
        putUint256(buf, 96 + 32 + senderPadded);

        // sender: length + padded data
        putUint256(buf, senderBytes.length);
        buf.put(senderBytes);
        if (senderPadded > senderBytes.length) {
            buf.put(new byte[senderPadded - senderBytes.length]);
        }

        // messageData: length + padded data
        putUint256(buf, msgBytes.length);
        buf.put(msgBytes);
        if (msgPadded > msgBytes.length) {
            buf.put(new byte[msgPadded - msgBytes.length]);
        }

        return buf.array();
    }

    /**
     * Writes a uint256 value (as a Java int) into a ByteBuffer, right-aligned in 32 bytes.
     */
    private static void putUint256(@NonNull final ByteBuffer buf, final int value) {
        buf.put(new byte[28]);
        buf.putInt(value);
    }

    private static String payloadKind(final ClprMessagePayload payload) {
        if (payload == null) {
            return "<null>";
        }
        if (payload.hasControl()) {
            return "CONTROL";
        } else if (payload.hasMessage()) {
            return "MESSAGE";
        } else if (payload.hasMessageReply()) {
            return "MESSAGE_REPLY";
        } else {
            return "EMPTY";
        }
    }

    private static String payloadSummary(final ClprMessagePayload payload) {
        if (payload == null) {
            return "<null>";
        }
        if (payload.hasMessage()) {
            final var msg = payload.messageOrThrow();
            return "connectorId="
                    + msg.connectorId()
                    + " target="
                    + msg.targetApplication()
                    + " sender="
                    + msg.sender()
                    + " messageDataLen="
                    + msg.messageData().length()
                    + " messageData="
                    + shortHex(msg.messageData());
        } else if (payload.hasMessageReply()) {
            final var reply = payload.messageReplyOrThrow();
            return "replyTargetId="
                    + reply.messageId()
                    + " status="
                    + reply.status()
                    + " replyDataLen="
                    + reply.messageReplyData().length()
                    + " replyData="
                    + shortHex(reply.messageReplyData());
        } else if (payload.hasControl()) {
            final var control = payload.controlOrThrow();
            if (!control.hasConfigUpdate()) {
                return "controlWithoutConfigUpdate";
            }
            final var configuration = control.configUpdateOrThrow().configuration();
            return configuration == null
                    ? "configUpdate without configuration"
                    : "configUpdate timestamp=" + configuration.timestamp();
        } else {
            return "emptyOneof";
        }
    }

    private static String shortHex(final Bytes bytes) {
        if (bytes == null) {
            return "<null>";
        }
        return bytes.length() <= 32 ? bytes.toHex() : bytes.slice(0, 32).toHex() + "...";
    }

    /** Budget for the entire serialized reply payload, including message id, status, and protobuf framing. */
    private static long maxSerializedReplyBytes(
            @NonNull final ClprThrottles localThrottles, @Nullable final ClprThrottles peerThrottles) {
        final var destination = peerThrottles == null ? localThrottles : peerThrottles;
        // A peer can advertise a higher cap, but it cannot raise the local state limit.
        final long payloadLimit = Math.min(
                localThrottles.maxMessagePayloadBytes(),
                ClprThrottlesUtils.effectiveMaxPayloadBytes(peerThrottles, localThrottles));
        final long syncLimit = Math.clamp(destination.maxSyncBytes(), MIN_PROOF_OVERHEAD_BYTES, VERIFIABLE_SYNC_BYTES);
        return Math.max(0, Math.min(payloadLimit, syncLimit - MIN_PROOF_OVERHEAD_BYTES));
    }

    private static long maxSerializedReplyBytesAfterBundle(
            @NonNull final ClprThrottles localThrottles,
            @Nullable final ClprThrottles peerThrottles,
            @NonNull final List<ClprMessagePayload> newMessages) {
        for (final var payload : newMessages.reversed()) {
            if (payload.hasControl()) {
                final var config =
                        payload.controlOrThrow().configUpdateOrThrow().configuration();
                if (config != null && config.hasThrottles()) {
                    return maxSerializedReplyBytes(localThrottles, config.throttlesOrThrow());
                }
            }
        }
        return maxSerializedReplyBytes(localThrottles, peerThrottles);
    }

    @Nullable
    private static Bytes decodeApplicationResponse(@NonNull final Bytes abiResponse, final long maxPayloadBytes) {
        // A single dynamic bytes value starts immediately after its offset word. Headlong
        // accepts gaps before the tail, but checks its length, padding and full consumption.
        if (abiResponse.length() < 2 * ABI_WORD_BYTES || !abiResponse.matchesPrefix(APPLICATION_RESPONSE_OFFSET)) {
            return null;
        }
        // Bound the input before copying it for Headlong. ABI pads the payload to a whole word, so
        // also check the decoded length to distinguish sizes that have the same encoded length.
        // The caller then measures the complete serialized reply with its actual message id and status.
        final long maxPaddedPayloadBytes = ((maxPayloadBytes + ABI_WORD_BYTES - 1) / ABI_WORD_BYTES) * ABI_WORD_BYTES;
        if (abiResponse.length() > 2 * ABI_WORD_BYTES + maxPaddedPayloadBytes) {
            return null;
        }
        final byte[] response =
                APPLICATION_RESPONSE_TYPE.decode(abiResponse.toByteArray()).get(0);
        return response.length > maxPayloadBytes ? null : Bytes.wrap(response);
    }

    private static ClprMessagePayload replyPayload(
            final long messageId, @NonNull final ClprMessageReplyStatus status, @NonNull final Bytes data) {
        return ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(messageId)
                        .status(status)
                        .messageReplyData(data))
                .build();
    }

    /**
     * ABI-encodes a call to {@code onClprResponse(bytes32,uint64,uint8,bytes)}.
     *
     * <p>Layout: 4-byte selector + ABI-encoded (bytes32, uint64, uint8, bytes).
     * The first three are static; the bytes arg is dynamic with offset/length/data encoding.
     */
    @NonNull
    private static byte[] encodeOnClprResponse(
            @NonNull final Bytes channelId,
            final long messageId,
            @NonNull final ClprMessageReplyStatus status,
            @NonNull final Bytes responseData) {
        final var respBytes = responseData.toByteArray();
        final int respPadded = ((respBytes.length + 31) / 32) * 32;

        // Total: selector(4) + channelId(32) + messageId(32) + status(32) + offset(32)
        //        + respLen(32) + respPadded
        final int totalSize = 4 + 32 + 32 + 32 + 32 + 32 + respPadded;
        final var buf = ByteBuffer.allocate(totalSize);

        // Function selector
        buf.put(ON_CLPR_RESPONSE_SELECTOR);

        // bytes32 channelId — static
        buf.put(channelId.toByteArray());

        // uint64 messageId — right-aligned in 32 bytes
        buf.put(new byte[24]);
        buf.putLong(messageId);

        // uint8 status — right-aligned in 32 bytes (ordinal value)
        buf.put(new byte[31]);
        buf.put((byte) status.protoOrdinal());

        // Offset to responseData (dynamic) — 4 words past start of params = 128
        putUint256(buf, 128);

        // responseData: length + padded data
        putUint256(buf, respBytes.length);
        buf.put(respBytes);
        if (respPadded > respBytes.length) {
            buf.put(new byte[respPadded - respBytes.length]);
        }

        return buf.array();
    }

    /** Preserves the attested outcome while explicitly notifying that its result data is unavailable. */
    private static byte[] encodeOnClprResponseUnavailable(
            @NonNull final Bytes channelId, final long messageId, @NonNull final ClprMessageReplyStatus status) {
        final var buf = ByteBuffer.allocate(4 + 3 * 32);
        buf.put(ON_CLPR_RESPONSE_UNAVAILABLE_SELECTOR);
        buf.put(channelId.toByteArray());
        buf.put(new byte[24]);
        buf.putLong(messageId);
        buf.put(new byte[31]);
        buf.put((byte) status.protoOrdinal());
        return buf.array();
    }

    /**
     * Checks whether timestamp a is strictly before timestamp b.
     */
    private static boolean isTimestampBefore(final Timestamp a, final Timestamp b) {
        if (a == null || b == null) {
            return a == null && b != null;
        }
        if (a.seconds() != b.seconds()) {
            return a.seconds() < b.seconds();
        }
        return a.nanos() < b.nanos();
    }

    /**
     * Verifies the bundle's proof and applies the node-level checks of {@link ClprBundleValidator},
     * before any state is modified or any message is dispatched.
     *
     * <p>A failed node-level check (payload size, the trust-anchor invariant, or
     * {@link ClprBundleValidator#validate}) fails the transaction and, after the framework rolls back
     * its state changes, charges the submitting node {@code penaltyAmount} on a best-effort basis, so
     * the penalty persists independently of the failed transaction. A failure to resolve or run the
     * verifier fails the transaction without that penalty.
     *
     * <p>Once the proof and the trust-anchor invariant pass, the peer's proven view of this ledger's
     * endpoint-manifest version (spec §4.5) is recorded, even if a later check rejects the bundle.
     *
     * @param context the handle context
     * @param channel the channel before this bundle is applied
     * @param ledgerConfig this ledger's configuration
     * @param nodeAccountId the submitting node's account, which pays the penalty
     * @param penaltyAmount the endpoint misbehavior penalty in tinybars; zero disables it
     * @return the validated bundle
     * @throws HandleException if the proof cannot be verified or a node-level check fails
     */
    private ValidatedBundle verifyAndValidateBundle(
            @NonNull final HandleContext context,
            @NonNull final ClprChannel channel,
            @NonNull final ClprLedgerConfiguration ledgerConfig,
            @NonNull final AccountID nodeAccountId,
            final long penaltyAmount) {
        try {
            final var payload = context.body().clprSubmitBundleOrThrow().bundlePayload();
            bundleValidator.validatePayloadSize(payload, ledgerConfig.throttlesOrThrow());
            final var verifier = verifierFactory.getVerifier(channel.verifierContractOrThrow());
            final var content =
                    verifier.verifyBundle(payload, channel.trustAnchor(), channel.channelContext(), context);
            bundleValidator.validateTrustAnchor(content);

            // Spec §4.5: this node-local signal must survive even a later NoProgress rejection.
            // Idle peers send pure acks containing our manifest version, so recording this only
            // after successful validation would keep resending the same manifest indefinitely.
            final var metadata = content.metadata();
            if (metadata != null) {
                channelLifecycle.recordPeerObservedManifestVersion(
                        channel.channelId(), metadata.endpointManifestVersion());
            }
            return bundleValidator.validate(channel, content, ledgerConfig);
        } catch (final PreCheckException e) {
            // Charge after rollback so the penalty persists independently of a rejected bundle.
            throw new HandleException(e.responseCode(), (feeChargingContext, ignored) -> {
                if (penaltyAmount > 0) {
                    try {
                        feeChargingContext.charge(nodeAccountId, new Fees(0, penaltyAmount, 0), null);
                    } catch (final Exception ignoredChargeFailure) {
                        // Best effort: the submitting endpoint may be insolvent.
                    }
                }
            });
        }
    }

    /**
     * A Step 1b peer endpoint cache refresh, held until the bundle has been handled without an exception so the
     * node-local cache only ever mirrors a manifest that is actually committed (see {@link #doHandle}).
     */
    private static final class DeferredPeerEndpoints {
        @Nullable
        private Bytes channelId;

        @Nullable
        private List<ClprEndpoint> endpoints;

        void set(@NonNull final Bytes channelId, @NonNull final List<ClprEndpoint> endpoints) {
            this.channelId = requireNonNull(channelId);
            this.endpoints = requireNonNull(endpoints);
        }

        void seedInto(@NonNull final ClprChannelLifecycle channelLifecycle) {
            if (channelId != null && endpoints != null) {
                channelLifecycle.seedPeerEndpoints(channelId, endpoints);
            }
        }
    }

    /**
     * Outbound message-queue accumulator backed by the {@link WritableChannelStore} as the
     * source of truth for {@code nextMessageId} and {@code sentRunningHash}. Each {@link #enqueue}
     * reads the latest channel, writes the message to the queue, and persists the advanced
     * counter and running hash back to the channel store atomically with the message write.
     *
     * <p>This keeps the channel store in sync with every enqueue, so a subsequent nested
     * {@code context.dispatch(...)} (which may invoke the CLPR precompile via
     * {@link ClprServiceApiImpl#sendMessage} and read {@code nextMessageId} directly from the
     * store) never collides with a slot we just enqueued — and vice versa: precompile-side
     * advances are observed automatically on the next enqueue.
     */
    private static final class OutboundQueue {
        private final WritableMessageQueueStore msgStore;
        private final WritableChannelStore connStore;
        private final Bytes channelId;
        private final long ackedMessageId;
        private final long maxDepth;

        OutboundQueue(
                @NonNull final WritableMessageQueueStore msgStore,
                @NonNull final WritableChannelStore connStore,
                @NonNull final Bytes channelId,
                final long ackedMessageId,
                final long maxDepth) {
            this.msgStore = msgStore;
            this.connStore = connStore;
            this.channelId = channelId;
            this.ackedMessageId = ackedMessageId;
            this.maxDepth = maxDepth;
        }

        long depth() {
            return Math.max(0, nextMessageId() - ackedMessageId - 1);
        }

        /** Enqueues an arbitrary payload and advances the channel's nextMessageId/sentRunningHash. */
        void enqueue(@NonNull final ClprMessagePayload payload) {
            final var conn = requireNonNull(connStore.getChannel(channelId));
            HandleException.validateTrue(conn.nextMessageId() - ackedMessageId - 1 < maxDepth, CLPR_QUEUE_FULL);
            final var prevHash = conn.sentRunningHash();
            final var base = prevHash.length() == 0 ? ZERO_HASH : prevHash;
            final var newHash = ClprHashUtils.computeRunningHash(base, payload);
            final var assignedId = conn.nextMessageId();
            msgStore.put(
                    channelId,
                    assignedId,
                    ClprMessageValue.newBuilder()
                            .payload(payload)
                            .runningHashAfterProcessing(newHash)
                            .build());
            connStore.put(conn.copyBuilder()
                    .nextMessageId(assignedId + 1)
                    .sentRunningHash(newHash)
                    .build());
            log.debug(
                    "[ClprSubmitBundle] outbound enqueue conn={} assignedMsgId={} kind={} prevHash={} newHash={} "
                            + "nextMsgId={}",
                    channelId,
                    assignedId,
                    payloadKind(payload),
                    shortHex(prevHash),
                    shortHex(newHash),
                    assignedId + 1);
        }

        /** Builds and enqueues a reply for the given inbound message. */
        void enqueueReply(
                final long inboundMessageId, @NonNull final ClprMessageReplyStatus status, @NonNull final Bytes data) {
            log.debug(
                    "[ClprSubmitBundle] outbound enqueueReply conn={} inboundMsgId={} status={} dataLen={} data={}",
                    channelId,
                    inboundMessageId,
                    status,
                    data.length(),
                    shortHex(data));
            enqueue(replyPayload(inboundMessageId, status, data));
        }

        long nextMessageId() {
            final var conn = connStore.getChannel(channelId);
            return conn != null ? conn.nextMessageId() : 0L;
        }

        @NonNull
        Bytes runningHash() {
            final var conn = connStore.getChannel(channelId);
            if (conn == null) {
                return ZERO_HASH;
            }
            final var h = conn.sentRunningHash();
            return h.length() == 0 ? ZERO_HASH : h;
        }
    }
}
