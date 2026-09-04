// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_DECODE_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INTERNAL_STATE_CORRUPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_RUNNING_HASH_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConfigUpdate;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.service.clpr.impl.ClprServiceApiImpl;
import com.hedera.node.app.service.clpr.impl.ClprSlashingUtils;
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
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    /** Function selector for onClprMessage(bytes32,bytes,bytes) = 0x2ab00809 */
    private static final byte[] ON_CLPR_MESSAGE_SELECTOR = new byte[] {0x2a, (byte) 0xb0, 0x08, 0x09};

    /** Function selector for onClprResponse(bytes32,uint64,uint8,bytes) = 0x3b74550e */
    private static final byte[] ON_CLPR_RESPONSE_SELECTOR = new byte[] {0x3b, 0x74, 0x55, 0x0e};

    private static final DispatchMetadata CLPR_DISPATCH_METADATA = new DispatchMetadata(
            CLPR_DISPATCH, new ClprDispatchMetadata(CLPR_SERVICE_ACCOUNT_ID, CLPR_EVM_ADDRESS_BYTES));

    private final ClprVerifierFactory verifierFactory;
    private final EntityIdFactory entityIdFactory;
    private final ClprChannelLifecycle channelLifecycle;

    @Inject
    public ClprSubmitBundleHandler(
            @NonNull final ClprVerifierFactory verifierFactory,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ClprChannelLifecycle channelLifecycle) {
        this.verifierFactory = requireNonNull(verifierFactory);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final var op = context.body().clprSubmitBundleOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        // An empty bundlePayload is a valid pure-ack bundle (no messages, just a metadata ack).
        // Rejecting it here would break legitimate use cases, so we do not validate length > 0.
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        final var op = context.body().clprSubmitBundleOrThrow();
        final var nodeStore = context.createStore(ReadableNodeStore.class);
        final var node = nodeStore.get(op.endpointNodeId());
        validateTruePreCheck(node != null && !node.deleted(), INVALID_NODE_ID);
        // TODO(CLPR-4): Re-enable admin-key signature requirement once
        // ClprBundleSubmitter signs internally-submitted transactions.
        // Until then, requiring the key here makes the inbound bundle pipeline
        // self-deadlock (sync workflow submits with empty sigmap → preHandle rejects).
        // context.requireKeyOrThrow(node.adminKey(), INVALID_NODE_ID);
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprSubmitBundleOrThrow();
        log.info(
                "[ClprSubmitBundle] doHandle ENTER conn={} bundleBytes={} endpointNode={} creatorNode={} payer={}",
                op.channelId(),
                op.bundlePayload().length(),
                op.endpointNodeId(),
                context.creatorInfo().nodeId(),
                context.payer());
        final var configuration = context.configuration();
        final var clprConfig = configuration.getConfigData(ClprConfig.class);
        // Use EntityIdFactory so shard/realm match this network
        final var systemAdminAccountId = entityIdFactory.newAccountId(
                configuration.getConfigData(AccountsConfig.class).systemAdmin());
        final var penaltyAmount = clprConfig.endpointMisbehaviorPenaltyTinybars();
        final var storeFactory = context.storeFactory();

        // Resolve the endpoint node's account for penalty charging. The node is guaranteed
        // to exist and not be deleted — preHandle already verified that.
        final var nodeStore = storeFactory.readableStore(ReadableNodeStore.class);
        final var endpointNode = nodeStore.get(op.endpointNodeId());
        final var endpointAccountId = endpointNode.accountIdOrThrow();
        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var channelStore = storeFactory.writableStore(WritableChannelStore.class);
        final var messageQueueStore = storeFactory.writableStore(WritableMessageQueueStore.class);

        // --- Step 1: Look up Channel ---
        // Reject any handling if this channel is CLOSED or PENDING. In any other state, this channel will attempt
        // to handle and respond to transactions to help the remote peer also achieve a CLOSED state.
        var channel = requireNonClosedChannel(channelStore, op.channelId());
        final var channelId = channel.channelId();
        log.info(
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
        log.info(
                "[ClprSubmitBundle] ledger config conn={} maxSyncBytes={} maxMessagesPerBundle={} "
                        + "maxPayloadBytes={} appDispatchGas={} messageExecutionCost={} endpointMarginPercent={}",
                channelId,
                throttles.maxSyncBytes(),
                throttles.maxMessagesPerBundle(),
                throttles.maxMessagePayloadBytes(),
                throttles.maxGasPerMessage(),
                clprConfig.messageExecutionCost(),
                clprConfig.endpointMarginPercent());

        // Reject an oversized wire payload before invoking a potentially expensive cryptographic
        // verifier. The limit applies to the raw bundle payload received on the wire.
        validateTrueOrPenalize(
                op.bundlePayload().length() <= throttles.maxSyncBytes(),
                CLPR_PAYLOAD_TOO_LARGE,
                endpointAccountId,
                penaltyAmount);

        // --- Step 4: Verifier call ---
        // Delegates to the Channel's verifier contract, passing the Channel's current
        // trust_anchor. The verifier returns verified queue metadata, ordered message payloads,
        // and an optional successor trust anchor (non-empty only when rotation evidence was
        // state-proven inside the bundle).
        log.debug(
                "[ClprSubmitBundle] invoking verifier contract={} trustAnchorBytes={}",
                channel.verifierContractOrThrow(),
                channel.trustAnchor().length());
        final var verifier = verifierFactory.getVerifier(channel.verifierContractOrThrow());
        final var bundleContent =
                verifier.verifyBundle(op.bundlePayload(), channel.trustAnchor(), channel.channelContext(), context);

        final var metadata = bundleContent.metadata();
        final var messages = bundleContent.messages();
        final var newTrustAnchor = bundleContent.newTrustAnchor();
        final var newTrustAnchorId = bundleContent.newTrustAnchorId();
        // Captured BEFORE Step 1b installs the successor anchor so the Step 1a
        // check below can compare the verifier-returned id against
        // the pre-bundle Channel.trust_anchor_id (spec §2.1.2).
        final var priorTrustAnchorId = channel.trustAnchorId();
        log.info(
                "[ClprSubmitBundle] post-verifier: messages={} newTrustAnchorLen={} newTrustAnchorIdLen={}"
                        + " metadata.nextMsgId={} metadata.recvMsgId={} metadata.status={}"
                        + " conn.recvMsgId={} conn.ackedMsgId={} conn.nextMsgId={} conn.sentRH(prefix)={}"
                        + " bundleSentRH(prefix)={}",
                messages.size(),
                newTrustAnchor.length(),
                newTrustAnchorId.length(),
                metadata == null ? null : metadata.nextMessageId(),
                metadata == null ? null : metadata.receivedMessageId(),
                metadata == null ? null : metadata.status(),
                channel.receivedMessageId(),
                channel.ackedMessageId(),
                channel.nextMessageId(),
                shortHex(channel.sentRunningHash()),
                metadata == null ? "<absent>" : shortHex(metadata.sentRunningHash()));

        // Spec invariant: new_trust_anchor_id MUST be non-empty iff new_trust_anchor is non-empty.
        validateTrueOrPenalize(
                newTrustAnchor.length() == 0 ? newTrustAnchorId.length() == 0 : newTrustAnchorId.length() > 0,
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);
        log.debug("[ClprSubmitBundle] check passed: trust-anchor invariant");

        // Spec §4.2 Step 1b: apply new_endpoint_manifest, and Step 1c: install successor
        // trust anchor. Applied together in a single copyBuilder / put so a manifest update
        // and a trust-anchor rotation carried by the same bundle are atomic (they're already
        // covered by the same verifyBundle call). Persisted immediately after the verifier
        // returns — before any early-return path (EmptyBundle, maxSyncBytes, etc.) so both
        // fields are installed even if a later check causes a penalizing rejection.
        //
        // Step 1b guard rules (per ADR "Propagating Updates Through Bundle Payloads"):
        // - Applied only when clpr.endpointManifestEnabled=true (flag-gates the acceptance
        //   of manifest updates carried by peer verifier contracts on this ledger).
        // - Applied only when newEndpointManifest.version() strictly advances the current
        //   cached version. Absent or stale manifest → silent skip; the bundle is NOT
        //   rejected for this reason alone. Manifest updates are allowed on CLOSING /
        //   DRAINED channels (harmless, keeps the cache current for admin purposes).
        // - Entire manifest replacement; no partial merge.
        final var newEndpointManifest = bundleContent.newEndpointManifest();
        final boolean endpointManifestEnabled =
                context.configuration().getConfigData(ClprConfig.class).endpointManifestEnabled();
        final boolean shouldUpdateManifest = endpointManifestEnabled
                && newEndpointManifest != null
                && newEndpointManifest.version() > channel.endpointManifestVersion();
        final boolean shouldUpdateTrustAnchor = newTrustAnchor.length() > 0;
        if (shouldUpdateManifest || shouldUpdateTrustAnchor) {
            var updatedBuilder = channel.copyBuilder();
            if (shouldUpdateManifest) {
                log.info(
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
                // Apply this ledger's max_peer_endpoints policy before storing, mirroring
                // ClprCompleteChannel — the peer's declared list must not override our storage
                // bound (spec §3.10.5). Zero means no limit; entries beyond it are dropped in order.
                final int rawPeerLimit = throttles.maxPeerEndpoints();
                final var incomingEndpoints = newEndpointManifest.endpoints();
                final var truncatedEndpoints = rawPeerLimit > 0 && incomingEndpoints.size() > rawPeerLimit
                        ? incomingEndpoints.subList(0, rawPeerLimit)
                        : incomingEndpoints;
                final var storedManifest = newEndpointManifest
                        .copyBuilder()
                        .endpoints(truncatedEndpoints)
                        .build();
                updatedBuilder = updatedBuilder
                        .endpointManifest(storedManifest)
                        .endpointManifestVersion(storedManifest.version());
            }
            if (shouldUpdateTrustAnchor) {
                log.info(
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
            validateTrueOrPenalize(
                    messages.isEmpty() && (newTrustAnchor.length() > 0 || shouldUpdateManifest),
                    CLPR_BUNDLE_VERIFICATION_FAILED,
                    endpointAccountId,
                    penaltyAmount);
            log.info(
                    "[ClprSubmitBundle] state-update-only bundle accepted conn={} trustAnchorLen={} manifestUpdated={}",
                    channelId,
                    newTrustAnchor.length(),
                    shouldUpdateManifest);
            return;
        }

        // --- Step 4a: NoProgress check (spec §4.2 Step 1a, §2.1.2) ---
        // Any ONE of the five Bundle Progress Criteria satisfies the check.
        final boolean hasNewMessages = !messages.isEmpty();
        // Criterion 3 (trust-anchor advancement). Non-empty anchor AND id distinct from the
        // pre-bundle Channel.trust_anchor_id — bytes-non-empty alone would classify a no-op
        // rotation (same id restated) as progress; id-different alone would classify an
        // empty-anchor bundle with a stale id mismatch as progress.
        final boolean hasTrustAnchorAdvancement =
                newTrustAnchor.length() > 0 && !newTrustAnchorId.equals(priorTrustAnchorId);
        final boolean hasAckProgress = metadata.receivedMessageId() > channel.ackedMessageId();
        final boolean hasStateTransition = isStateTransitionProgress(metadata.status(), channel.status());
        // Criterion 5 (endpoint-manifest advancement) per spec §4.2 Step 1a and ADR
        // "Endpoint Manifest Advancement as a Bundle Progress Criterion". Reuses the Step 1b
        // guard (flag ON + non-null + strictly advancing version) computed against the
        // pre-bundle version — a bundle that only advances the manifest is still valid.
        final boolean hasManifestAdvancement = shouldUpdateManifest;
        validateTrueOrPenalize(
                hasNewMessages
                        || hasTrustAnchorAdvancement
                        || hasAckProgress
                        || hasStateTransition
                        || hasManifestAdvancement,
                CLPR_NO_PROGRESS,
                endpointAccountId,
                penaltyAmount);
        log.debug("[ClprSubmitBundle] check passed: makes progress");

        validateTrueOrPenalize(
                messages.size() <= throttles.maxMessagesPerBundle(),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);
        log.debug(
                "[ClprSubmitBundle] check passed: maxMessagesPerBundle (size={} max={})",
                messages.size(),
                throttles.maxMessagesPerBundle());

        // --- Step 4b: Per-message payload size check (spec §3.5.4) ---
        final long maxPayloadBytes = throttles.maxMessagePayloadBytes();
        if (maxPayloadBytes > 0) {
            for (final var payload : messages) {
                if (payload.hasMessage()) {
                    validateTrueOrPenalize(
                            payload.messageOrThrow().messageData().length() <= maxPayloadBytes,
                            CLPR_PAYLOAD_TOO_LARGE,
                            endpointAccountId,
                            penaltyAmount);
                }
            }
        }

        // --- Step 5: Replay defense (idempotent) ---
        // The bundle covers peer's outbound message IDs [peerAckedMessageId+1 .. metadata.nextMessageId-1].
        // Under bidirectional traffic the peer's channel.ackedMessageId can lag our
        // channel.receivedMessageId until the next ack round-trip lands, so the bundle's leading
        // messages may be replays we have already processed. We tolerate that: validate the bundle
        // structurally, skip the replayed prefix, and process only the new tail. This is what makes
        // re-delivery idempotent — without it, a strict equality check would deadlock both sides
        // (each rejects the other's bundle because of the unavoidable replay overlap, neither side's
        // ack ever propagates, neither side's channel.ackedMessageId ever advances).
        final var peerAckedMessageId = metadata.nextMessageId() - messages.size() - 1;
        // Sanity:
        //  - peer.ackedMessageId must be >= 0 (cannot be negative)
        //  - peer.ackedMessageId must be <= our.receivedMessageId (peer can't have observed an ack
        //    from us beyond what we actually received — that would imply corruption or a forged proof)
        validateTrueOrPenalize(
                peerAckedMessageId >= 0 && peerAckedMessageId <= channel.receivedMessageId(),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);
        final int skipCount = (int) (channel.receivedMessageId() - peerAckedMessageId);
        // skipCount <= messages.size() iff our.receivedMessageId <= peer.nextMessageId - 1
        // (we can't have received more than peer has sent). Reject if violated.
        validateTrueOrPenalize(
                skipCount <= messages.size(), CLPR_BUNDLE_VERIFICATION_FAILED, endpointAccountId, penaltyAmount);
        // Trim the replayed prefix; downstream steps process only the new tail.
        final var newMessages = skipCount > 0 ? messages.subList(skipCount, messages.size()) : messages;
        final var expectedFirstId = channel.receivedMessageId() + 1;
        log.info(
                "[ClprSubmitBundle] step5 check: peerAckedMsgId={} skipCount={} messages.size={} "
                        + "newMessages.size={} expectedFirstId={} metadata.nextMsgId={} newKinds={}",
                peerAckedMessageId,
                skipCount,
                messages.size(),
                newMessages.size(),
                expectedFirstId,
                metadata.nextMessageId(),
                newMessages.stream().map(ClprSubmitBundleHandler::payloadKind).toList());
        log.debug("[ClprSubmitBundle] check passed: step5 replay-defense (idempotent)");

        // --- Step 6: Running hash verification (spec §4.2 step 4, §4.4) ---
        // Non-redacted slot: fold its serialized payload into the chain.
        // Redacted slot: adopt the running_hash carried inside the redacted payload — the
        // original bytes are gone so we can't recompute. Trust comes from the verifier proof.
        //
        // We fold only the NEW tail (newMessages); the replayed prefix has already been folded
        // into channel.receivedRunningHash from a prior bundle, and the peer's
        // runningHashAfterProcessing at our.receivedMessageId equals our anchor by construction,
        // so resuming from there yields metadata.sentRunningHash for the bundle's last slot.
        var computedHash = channel.receivedRunningHash();
        log.info(
                "[ClprSubmitBundle] step6 running-hash start conn={} baseReceivedMsgId={} baseHash={} "
                        + "expectedPeerSentHash={} newMessages={}",
                channelId,
                channel.receivedMessageId(),
                shortHex(computedHash),
                shortHex(metadata.sentRunningHash()),
                newMessages.size());
        // Empty hash is only valid on the very first bundle.
        if (computedHash.length() == 0) {
            validateTrueOrPenalize(
                    channel.receivedMessageId() == 0,
                    CLPR_BUNDLE_VERIFICATION_FAILED,
                    endpointAccountId,
                    penaltyAmount);
            computedHash = ZERO_HASH;
        }
        for (final var payload : newMessages) {
            if (payload.hasRedactedMessage()) {
                final var messageHash = payload.redactedMessageOrThrow().messageHash();
                log.info(
                        "[ClprSubmitBundle] step6 redacted slot conn={} messageHash={} beforeHash={}",
                        channelId,
                        shortHex(messageHash),
                        shortHex(computedHash));
                validateTrueOrPenalize(
                        messageHash != null && messageHash.length() == 32,
                        CLPR_RUNNING_HASH_MISMATCH,
                        endpointAccountId,
                        penaltyAmount);
                computedHash = ClprHashUtils.computeRunningHashFromPayloadHash(computedHash, messageHash);
                log.info(
                        "[ClprSubmitBundle] step6 redacted slot folded conn={} afterHash={}",
                        channelId,
                        shortHex(computedHash));
            } else {
                log.info(
                        "[ClprSubmitBundle] step6 folding payload conn={} kind={} beforeHash={}",
                        channelId,
                        payloadKind(payload),
                        shortHex(computedHash));
                computedHash = ClprHashUtils.computeRunningHash(computedHash, payload);
                log.info(
                        "[ClprSubmitBundle] step6 folded payload conn={} kind={} afterHash={}",
                        channelId,
                        payloadKind(payload),
                        shortHex(computedHash));
            }
        }
        validateTrueOrPenalize(
                computedHash.equals(metadata.sentRunningHash()),
                CLPR_RUNNING_HASH_MISMATCH,
                endpointAccountId,
                penaltyAmount);
        log.info(
                "[ClprSubmitBundle] step6 running-hash PASS conn={} computedHash={}",
                channelId,
                shortHex(computedHash));

        // --- Step 7: Verify acknowledgement metadata correctness ---
        final var newAckedMessageId = metadata.receivedMessageId(); // Remote has now seen up to message 110
        final var oldAckedMessageId = channel.ackedMessageId(); // Previously they have acked up to 105
        log.info(
                "[ClprSubmitBundle] step7 check: newAckedMsgId={} oldAckedMsgId={} conn.nextMsgId={}",
                newAckedMessageId,
                oldAckedMessageId,
                channel.nextMessageId());
        validateTrueOrPenalize(
                newAckedMessageId >= oldAckedMessageId,
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);
        // Allow newAckedMessageId == oldAckedMessageId (no change) even when nextMessageId=0 (initial state).
        // The condition newAckedMessageId < nextMessageId would otherwise incorrectly reject the "nothing sent,
        // nothing acked" initial state (0 < 0 = false).
        validateTrueOrPenalize(
                newAckedMessageId < channel.nextMessageId() || newAckedMessageId == oldAckedMessageId,
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);

        var currentStatus = channel.status(); // Our current channel status
        final var peerStatus = metadata.status(); // Remote peer state (used in step 10 and step 11)
        // Spec §4.5: sender's cached view of THIS ledger's endpoint manifest version. Handed to
        // the runtime sync orchestrator, which compares it against our local
        // ClprEndpointManifest.version() on the next outbound cycle to decide whether to embed a
        // proof of our own manifest. Recorded via the lifecycle SPI as a node-local, in-memory
        // signal — NOT consensus state — so it self-heals from the live metadata stream and needs
        // no proto/state field. Gated by clpr.endpointManifestEnabled: when the feature is disabled
        // we do not read or forward the peer-reported version, so no manifest-related signal leaks
        // while the feature is off.
        if (clprConfig.endpointManifestEnabled()) {
            final long peerEndpointManifestVersion = metadata.endpointManifestVersion();
            log.info(
                    "[ClprSubmitBundle] peer-reported cache of our manifest version: conn={} peerVersion={}",
                    channelId,
                    peerEndpointManifestVersion);
            channelLifecycle.recordPeerObservedManifestVersion(channelId, peerEndpointManifestVersion);
        }

        // --- Step 8: Read-only outbound queue pre-scan ---
        // Validates that replies in the bundle match our outbound Data messages in order.
        // No mutations — if validation fails and we return early, no state is left inconsistent.
        //
        // Iterate the NEW tail of the bundle only — the replayed prefix was processed in a prior
        // bundle (we already advanced ackedMessageId off any replies it contained, and consuming
        // them again here would mismatch against outbound messages that were already deleted).
        int responseIndex = 0;
        log.info(
                "[ClprSubmitBundle] step8 response pre-scan conn={} oldAckedMsgId={} newAckedMsgId={} "
                        + "newMessages={}",
                channelId,
                oldAckedMessageId,
                newAckedMessageId,
                newMessages.size());
        for (long id = oldAckedMessageId + 1; id <= newAckedMessageId; id++) {
            final var msg = messageQueueStore.getMessage(channelId, id);
            // A null here means data corruption — an outbound message ID was assigned, but the
            // message doesn't exist. Fail fast; this ledger needs a bug fix and data migration.
            validateTrue(msg != null, CLPR_INTERNAL_STATE_CORRUPTION);
            final var msgPayload = msg.payload();
            // Control and MessageReply slots are one-way — no inbound reply expected.
            // Redacted slots WERE originally Data, so the peer ships a REDACTED reply for them;
            // they participate in reply matching just like normal Data.
            if (msgPayload != null && (msgPayload.hasControl() || msgPayload.hasMessageReply())) {
                continue;
            }
            // Data message (or redacted Data slot) — the bundle MUST contain an in-order reply.
            boolean matched = false;
            for (; responseIndex < newMessages.size(); responseIndex++) {
                final var slotPayload = newMessages.get(responseIndex);
                if (slotPayload.hasMessageReply()) {
                    matched = slotPayload.messageReplyOrThrow().messageId() == id;
                    responseIndex++;
                    break;
                }
            }
            if (!matched) {
                log.warn(
                        "[ClprSubmitBundle] step8 missing expected reply conn={} outboundMsgId={} "
                                + "responseIndex={} currentStatus={}",
                        channelId,
                        id,
                        responseIndex,
                        currentStatus);
                if (currentStatus == ClprChannelStatus.ACTIVE) {
                    channelStore.put(channel.copyBuilder()
                            .status(ClprChannelStatus.PAUSED)
                            .build());
                    context.tryToCharge(endpointAccountId, penaltyAmount);
                }
                return;
            }
        }

        // The reply-matching loop above consumed exactly one inbound messageReply per acked DATA
        // outbound slot. Any messageReply entries still in the bundle were not consumed by that
        // loop — they have no matching acked DATA slot. Two legitimate cases remain:
        //   1. The reply targets a one-way outbound slot (control or messageReply) that still exists.
        //      One-way slots never require a reply, but the peer may optionally acknowledge them —
        //      e.g. when a peer acks a CHANNEL_CLOSED reply with its own messageReply.
        //   2. The slot was a one-way slot acked and deleted by Pass 2 within THIS bundle, so
        //      getMessage returns null. We identify this case by checking that the target ID falls
        //      within the range Pass 2 just processed: (oldAckedMessageId, newAckedMessageId].
        //      A null slot outside that range either never existed or was acked in a prior bundle —
        //      both are violations.
        // Any other case means the peer sent a reply targeting an un-acked DATA slot — a violation.
        for (int i = responseIndex; i < newMessages.size(); i++) {
            final var trailingMsg = newMessages.get(i);
            if (!trailingMsg.hasMessageReply()) {
                continue;
            }
            final var replyTargetId = trailingMsg.messageReplyOrThrow().messageId();
            final var targetMsg = messageQueueStore.getMessage(channelId, replyTargetId);
            final var targetPayload = targetMsg == null ? null : targetMsg.payload();
            final boolean cleanedByThisBundle =
                    targetMsg == null && replyTargetId > oldAckedMessageId && replyTargetId <= newAckedMessageId;
            if (cleanedByThisBundle
                    || (targetPayload != null && (targetPayload.hasControl() || targetPayload.hasMessageReply()))) {
                log.info(
                        "[ClprSubmitBundle] step8 reply targets one-way or already-cleaned slot conn={} "
                                + "replyTargetId={} targetKind={}; skipping",
                        channelId,
                        replyTargetId,
                        payloadKind(targetPayload));
                continue;
            }
            log.warn(
                    "[ClprSubmitBundle] step8 trailing unexpected reply conn={} newMessageIndex={} "
                            + "replyTargetId={} currentStatus={}",
                    channelId,
                    i,
                    replyTargetId,
                    currentStatus);
            if (currentStatus == ClprChannelStatus.ACTIVE) {
                channelStore.put(
                        channel.copyBuilder().status(ClprChannelStatus.PAUSED).build());
                context.tryToCharge(endpointAccountId, penaltyAmount);
            }
            return;
        }

        // --- Step 9: If PAUSED, transition back to ACTIVE ---
        // Ordering valid (and responses present if was PAUSED) — ensure ACTIVE
        if (currentStatus == ClprChannelStatus.PAUSED) {
            log.info("[ClprSubmitBundle] step9 resuming paused channel conn={}", channelId);
            currentStatus = ClprChannelStatus.ACTIVE;
        }

        // --- Pass 2: Mutations begin ---

        // Delete acked one-way outbound messages (control, reply). Data and redacted-Data
        // slots are deleted later when their inbound reply is processed in Step 10.
        for (long id = oldAckedMessageId + 1; id <= newAckedMessageId; id++) {
            final var msg = messageQueueStore.getMessage(channelId, id);
            if (msg == null) continue;
            final var payload = msg.payload();
            if (payload != null && (payload.hasControl() || payload.hasMessageReply())) {
                messageQueueStore.remove(channelId, id);
            }
        }

        // Lazy config propagation. Determine if the config has changed since the last time we sync'd. If so,
        // enqueue a new config via a control message.
        final var configTimestamp = ledgerConfig.timestamp();
        var lastConfigTimestamp = channel.lastConfigTimestamp();
        final var outbound = new OutboundQueue(messageQueueStore, channelStore, channelId);
        if (isTimestampBefore(lastConfigTimestamp, configTimestamp)) {
            log.info(
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
            log.info(
                    "[ClprSubmitBundle] peer requested close/drain conn={} peerStatus={} localStatus={} -> CLOSING",
                    channelId,
                    peerStatus,
                    currentStatus);
            currentStatus = ClprChannelStatus.CLOSING;
        }

        var peerConfigTimestamp = channel.peerConfigTimestamp();
        var peerThrottles = channel.peerThrottles();
        var receivedMessageId = channel.receivedMessageId();
        boolean bundleDecodeFailure = false;
        // Iterate the new tail only — replayed slots were dispatched in a prior bundle, so we
        // must not re-dispatch onClprMessage / re-enqueue a Reply for them.
        for (int i = 0; i < newMessages.size(); i++) {
            final var payload = newMessages.get(i);
            receivedMessageId = expectedFirstId + i;
            log.info(
                    "[ClprSubmitBundle] step10 slot ENTER conn={} newIndex={} receivedMsgId={} kind={} summary={}",
                    channelId,
                    i,
                    receivedMessageId,
                    payloadKind(payload),
                    payloadSummary(payload));

            try {
                if (payload.hasControl()) {
                    // Control message — validate the enclosed ClprLedgerConfiguration (spec §1.1 / §1.3),
                    // then update peerConfigTimestamp and peerThrottles. No response is generated.
                    // validatePeerConfig throws HandleException(CLPR_BUNDLE_VERIFICATION_FAILED) on any
                    // protocol-version, timestamp-range, or staleness violation; the outer HandleException
                    // catch above the RuntimeException handler in Step 10 lets that propagate to the
                    // bundle-level rollback + endpoint slash.
                    final var control = payload.controlOrThrow();
                    if (control.hasConfigUpdate()) {
                        final var peerConfig = control.configUpdateOrThrow().configuration();
                        log.info(
                                "[ClprSubmitBundle] step10 CONTROL configUpdate conn={} receivedMsgId={} "
                                        + "peerTimestamp={} endpoints={}",
                                channelId,
                                receivedMessageId,
                                peerConfig == null ? null : peerConfig.timestamp(),
                                peerConfig == null ? 0 : peerConfig.endpoints().size());
                        if (peerConfig != null) {
                            validatePeerConfig(
                                    peerConfig, ledgerConfig, peerConfigTimestamp, endpointAccountId, penaltyAmount);
                            peerConfigTimestamp = peerConfig.timestamp();
                        }
                        if (peerConfig != null && peerConfig.throttles() != null) {
                            peerThrottles = peerConfig.throttles();
                        }
                    } else {
                        // Spec §1.3: a ClprControlMessage with no known oneof variant set indicates an
                        // unknown control-message type from a newer protocol version. Silently skipping
                        // it would cause state divergence, so the entire bundle MUST be rejected.
                        //
                        // Penalty rationale: spec §8.1 permits ("MAY") penalizing the submitting endpoint
                        // for verifier-attested protocol violations. We use the same
                        // `endpointMisbehaviorPenaltyTinybars` applied to every other §4.2 verifier-attested
                        // violation (hash mismatch, replay, size caps, NoProgress, trust-anchor invariant),
                        // for consistency. Repeated violations escalate via §8.1's local misbehavior
                        // thresholds — an endpoint on a newer protocol version pays once and then upgrades.
                        log.warn(
                                "[ClprSubmitBundle] CONTROL unknown variant conn={} receivedMsgId={}",
                                channelId,
                                receivedMessageId);
                        validateTrueOrPenalize(
                                false, CLPR_BUNDLE_VERIFICATION_FAILED, endpointAccountId, penaltyAmount);
                    }
                } else if (payload.hasMessage()) {
                    // We must be ACTIVE, CLOSING, or DRAINED (CLOSED channels are rejected at the top of the
                    // handler, and PAUSED channels quit before reaching per-message dispatch)
                    final var dataMsg = payload.messageOrThrow();
                    log.info(
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
                        log.warn(
                                "[ClprSubmitBundle] step10 DATA connector NOT_FOUND conn={} receivedMsgId={} "
                                        + "connectorId={} -> CONNECTOR_NOT_FOUND reply",
                                channelId,
                                receivedMessageId,
                                dataMsg.connectorId());
                        outbound.enqueueReply(
                                receivedMessageId, ClprMessageReplyStatus.CONNECTOR_NOT_FOUND, Bytes.EMPTY);
                    } else {
                        // Compute worst-case charge: execution cost + endpoint margin
                        final var executionCost = clprConfig.messageExecutionCost();
                        final var margin = executionCost * clprConfig.endpointMarginPercent() / 100;
                        final var worstCaseCharge = executionCost + margin;

                        // Validate the connector has sufficient balance before dispatching.
                        // Use getContractById to handle both contractNum and evmAddress forms.
                        final var tokenServiceApi = storeFactory.serviceApi(TokenServiceApi.class);
                        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
                        final var connectorAccount = accountStore.getContractById(connector.connectorContractOrThrow());
                        log.info(
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
                            log.warn(
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
                            log.info(
                                    "[ClprSubmitBundle] step10 DATA connector underfunded handling complete conn={} "
                                            + "receivedMsgId={} penalty={}",
                                    channelId,
                                    receivedMessageId,
                                    slashResult.penaltyAmount());
                            continue;
                        }

                        // CEI: debit the connector BEFORE dispatching so that a reentering call
                        // cannot observe unspent balance and dispatch again before the charge lands.
                        log.info(
                                "[ClprSubmitBundle] step10 DATA debiting connector conn={} receivedMsgId={} "
                                        + "from={} toPayer={} amount={}",
                                channelId,
                                receivedMessageId,
                                connectorAccount.accountIdOrThrow(),
                                context.payer(),
                                worstCaseCharge);
                        tokenServiceApi.transferFromTo(
                                connectorAccount.accountIdOrThrow(), context.payer(), worstCaseCharge);
                        log.info(
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
                                        .gas(throttles.maxGasPerMessage())
                                        .functionParameters(Bytes.wrap(callData))
                                        .build())
                                .build();
                        log.info(
                                "[ClprSubmitBundle] step10 DATA dispatch prepared conn={} receivedMsgId={} "
                                        + "target={} gas={} callDataLen={} selector={} callData={}",
                                channelId,
                                receivedMessageId,
                                appContractId,
                                throttles.maxGasPerMessage(),
                                callData.length,
                                shortHex(Bytes.wrap(ON_CLPR_MESSAGE_SELECTOR)),
                                shortHex(Bytes.wrap(callData)));

                        ClprMessageReplyStatus replyStatus;
                        Bytes responseData = Bytes.EMPTY;
                        try {
                            log.info(
                                    "[ClprSubmitBundle] step10 DATA dispatch ENTER conn={} receivedMsgId={} "
                                            + "target={} payer={}",
                                    channelId,
                                    receivedMessageId,
                                    appContractId,
                                    systemAdminAccountId);
                            final var result = context.dispatch(stepDispatch(
                                    systemAdminAccountId,
                                    syntheticBody,
                                    HookDispatchStreamBuilder.class,
                                    NOOP_SIGNED_TX_CUSTOMIZER,
                                    CLPR_DISPATCH_METADATA));
                            log.info(
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
                                // Treat as empty return data — the reply status is set by result.status().
                                log.warn(
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
                            log.info(
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
                                    final var decoded =
                                            TupleType.parse("(bytes)").decode(rawEvmResult.toByteArray());
                                    responseData = Bytes.wrap((byte[]) decoded.get(0));
                                    log.info(
                                            "[ClprSubmitBundle] step10 DATA ABI unwrap OK conn={} receivedMsgId={} "
                                                    + "responseDataLen={} responseData={}",
                                            channelId,
                                            receivedMessageId,
                                            responseData.length(),
                                            shortHex(responseData));
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
                            }
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
                        }

                        // OutboundQueue reads/writes channel state directly from the
                        // store on each enqueue, so any precompile-side bumps from the
                        // dispatched onClprMessage (e.g. PingPong's bounce) are picked up
                        // automatically and our reply lands on the next free slot.
                        log.info(
                                "[ClprSubmitBundle] step10 DATA enqueueing reply conn={} receivedMsgId={} "
                                        + "replyStatus={} responseDataLen={} responseData={}",
                                channelId,
                                receivedMessageId,
                                replyStatus,
                                responseData.length(),
                                shortHex(responseData));
                        outbound.enqueueReply(receivedMessageId, replyStatus, responseData);
                        log.info(
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
                    log.info(
                            "[ClprSubmitBundle] step10 MESSAGE_REPLY conn={} receivedMsgId={} replyTargetId={} "
                                    + "status={} replyDataLen={} replyData={}",
                            channelId,
                            receivedMessageId,
                            replyTargetId,
                            reply.status(),
                            reply.messageReplyData().length(),
                            shortHex(reply.messageReplyData()));

                    final var originalMsg = messageQueueStore.getMessage(channelId, replyTargetId);
                    messageQueueStore.remove(channelId, replyTargetId);
                    log.info(
                            "[ClprSubmitBundle] step10 MESSAGE_REPLY original lookup conn={} replyTargetId={} "
                                    + "originalPresent={}",
                            channelId,
                            replyTargetId,
                            originalMsg != null);

                    // It should never be the case that originalMsg, its payload, or its inner message are EVER null.
                    // That should not have been allowed to be enqueued in this way. If something went wrong, we should
                    // log a stern warning and then just ignore the original message and the response.
                    //
                    // The originating slot may be either an unredacted Data Message OR a ClprRedactedMessage that
                    // preserved the sender when the admin redacted before delivery. Both paths
                    // need the sender to dispatch the response back to the originating application; only the
                    // unredacted Data Message path can slash the source-side connector (redacted slots don't carry
                    // the connector id — and redacting an admin-approved outbound shouldn't be a slashing event).
                    final var originalPayload =
                            originalMsg == null ? null : originalMsg.hasPayload() ? originalMsg.payload() : null;
                    final var origDataMsg = originalPayload == null
                            ? null
                            : originalPayload.hasMessage() ? originalPayload.message() : null;
                    final var origRedacted = originalPayload == null
                            ? null
                            : originalPayload.hasRedactedMessage() ? originalPayload.redactedMessage() : null;
                    if (origDataMsg == null && origRedacted == null) {
                        log.warn(
                                "[ClprSubmitBundle] step10 MESSAGE_REPLY has no original DATA/REDACTED message "
                                        + "conn={} replyTargetId={} originalPayloadKind={}; skipping app callback",
                                channelId,
                                replyTargetId,
                                payloadKind(originalPayload));
                        continue;
                    }

                    // Deliver response to the originating application (best-effort). The response might even be
                    // CONNECTOR_NOT_FOUND or some other error, or that the remote side is CLOSED. Whatever it is,
                    // we must inform the originating application so it can do proper bookkeeping.
                    final var senderAddress = origDataMsg != null ? origDataMsg.sender() : origRedacted.sender();
                    if (senderAddress.length() > 0) {
                        final var callData = encodeOnClprResponse(
                                channelId, replyTargetId, reply.status(), reply.messageReplyData());
                        // Use EntityIdFactory so shard/realm match this network
                        final var senderContractId = entityIdFactory.newContractIdWithEvmAddress(senderAddress);
                        final var syntheticBody = TransactionBody.newBuilder()
                                .contractCall(ContractCallTransactionBody.newBuilder()
                                        .contractID(senderContractId)
                                        // Response delivery is also an application dispatch (§6.0),
                                        // so it shares the same per-message gas ceiling
                                        // (spec §1.1 max_gas_per_message).
                                        .gas(throttles.maxGasPerMessage())
                                        .functionParameters(Bytes.wrap(callData))
                                        .build())
                                .build();
                        log.info(
                                "[ClprSubmitBundle] dispatching onClprResponse to sender={} replyTargetId={} "
                                        + "status={} replyDataLen={} callDataLen={} gas={} callData={}",
                                senderAddress,
                                replyTargetId,
                                reply.status(),
                                reply.messageReplyData().length(),
                                callData.length,
                                throttles.maxGasPerMessage(),
                                shortHex(Bytes.wrap(callData)));
                        try {
                            context.dispatch(stepDispatch(
                                    systemAdminAccountId,
                                    syntheticBody,
                                    HookDispatchStreamBuilder.class,
                                    NOOP_SIGNED_TX_CUSTOMIZER,
                                    CLPR_DISPATCH_METADATA));
                            log.info(
                                    "[ClprSubmitBundle] onClprResponse dispatch SUCCESS to sender={} replyTargetId={}",
                                    senderAddress,
                                    replyTargetId);
                        } catch (final HandleException e) {
                            // Best-effort: callback failure does not stop bundle processing. Log so we can
                            // see when an application's onClprResponse rejected or reverted.
                            log.warn(
                                    "[ClprSubmitBundle] onClprResponse dispatch FAILED to sender={} replyTargetId={}: {}",
                                    senderAddress,
                                    replyTargetId,
                                    e.getMessage());
                        }
                        // OutboundQueue reads/writes channel state directly from the store,
                        // so any precompile-side bumps from the dispatched onClprResponse
                        // (e.g. PingPong's bounce) are observed automatically on the next enqueue.
                    } else {
                        log.info(
                                "[ClprSubmitBundle] skipping onClprResponse dispatch — originating msg has no sender "
                                        + "(replyTargetId={} status={})",
                                replyTargetId,
                                reply.status());
                    }

                    // Source-side slashing on failure responses. That is, if we sent a message to the remote ledger
                    // and it replied indicating the connector on our side was at fault, then we will punish the
                    // connector on our side accordingly. Skipped when the originating slot was redacted: the
                    // connector id isn't preserved in ClprRedactedMessage, and an admin-approved redaction
                    // shouldn't retroactively punish the connector for what was originally an authorized send.
                    if (origDataMsg == null) {
                        continue;
                    }
                    final var replyStatus = reply.status();
                    final var sourceConnectorKey = new ClprConnectorKey(channelId, origDataMsg.connectorId());
                    final var sourceConnector = connectorStore.getConnector(sourceConnectorKey);
                    if (sourceConnector != null) {
                        // Decrement in-flight counter — this reply is a terminal event for the DATA message.
                        final long current = sourceConnector.inFlightMessageCount();
                        if (current > 0) {
                            connectorStore.put(sourceConnector
                                    .copyBuilder()
                                    .inFlightMessageCount(current - 1)
                                    .build());
                            log.info(
                                    "[ClprSubmitBundle] step10 MESSAGE_REPLY decremented source connector in-flight "
                                            + "conn={} connectorId={} {}->{}",
                                    channelId,
                                    origDataMsg.connectorId(),
                                    current,
                                    current - 1);
                        }

                        if (replyStatus == ClprMessageReplyStatus.CONNECTOR_NOT_FOUND
                                || replyStatus == ClprMessageReplyStatus.CONNECTOR_UNDERFUNDED) {
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
                                    replyStatus,
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
                } else if (payload.hasRedactedMessage()) {
                    // Redacted slot — all oneof fields unset. The verifier attested to this slot existing,
                    // so we must acknowledge it. Enqueue a REDACTED reply so the remote peer knows we saw
                    // the slot but cannot act on it (the contents were intentionally withheld).
                    log.info(
                            "[ClprSubmitBundle] step10 REDACTED conn={} receivedMsgId={} -> REDACTED reply",
                            channelId,
                            receivedMessageId);
                    outbound.enqueueReply(receivedMessageId, ClprMessageReplyStatus.REDACTED, Bytes.EMPTY);
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
        if (outbound.nextMessageId() > 0) {
            for (long id = newAckedMessageId + 1; id < outbound.nextMessageId(); id++) {
                final var msg = messageQueueStore.getMessage(channelId, id);
                if (msg == null) continue;
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

        log.info(
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
                && outboundDrained) {
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
        log.info(
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

        // Fast-path notify: drop this id from the orchestrator's in-memory
        // registry now that the channel is terminal. This is a best-effort
        // optimization — if the surrounding transaction rolls back, the registry
        // entry is stranded, but the orchestrator's syncTick observes-and-drops
        // any committed-CLOSED channel on its next pass, so the worst case
        // is one extra state lookup per tick until that pass runs.
        if (currentStatus == ClprChannelStatus.CLOSED) {
            log.info("[ClprSubmitBundle] notifying lifecycle channel closed conn={}", channelId);
            channelLifecycle.onChannelClosed(channelId);
        }
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
        } else if (payload.hasRedactedMessage()) {
            return "REDACTED";
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
        } else if (payload.hasRedactedMessage()) {
            return "messageHash=" + shortHex(payload.redactedMessageOrThrow().messageHash());
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
        final var hex = bytes.toHex();
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
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

    /**
     * Validate an inbound {@link ClprLedgerConfiguration} carried by a peer {@code ConfigUpdate}
     * before it is allowed to mutate any peer-config state on the Channel. Fails the bundle
     * with {@link ResponseCodeEnum#CLPR_BUNDLE_VERIFICATION_FAILED} and slashes the submitting
     * endpoint by {@code penaltyAmount} on any of:
     *
     * <ul>
     *   <li>Spec §1.1 — {@code peerConfig.protocolVersion()} does not match {@code ledgerConfig}.</li>
     *   <li>Spec §1.1 — {@code peerConfig.timestamp()} is null, has negative seconds, or nanos
     *       outside {@code [0, 999_999_999]}.</li>
     *   <li>Spec §1.3 — {@code peerConfig.timestamp()} is not strictly greater than the running
     *       {@code peerConfigTimestamp} (staleness). The running local is used so a bundle
     *       carrying multiple {@code ConfigUpdate}s enforces strict monotonicity across the whole
     *       bundle.</li>
     * </ul>
     */
    private static void validatePeerConfig(
            @NonNull final ClprLedgerConfiguration peerConfig,
            @NonNull final ClprLedgerConfiguration ledgerConfig,
            @Nullable final Timestamp peerConfigTimestamp,
            @NonNull final AccountID endpointAccountId,
            final long penaltyAmount) {
        requireNonNull(peerConfig);
        requireNonNull(ledgerConfig);
        requireNonNull(endpointAccountId);

        // Spec §1.1 Protocol Version: both sides MUST agree; cross-version messaging is not supported.
        validateTrueOrPenalize(
                peerConfig.protocolVersion() == ledgerConfig.protocolVersion(),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);

        // Spec §1.1 Timestamp: seconds MUST be non-negative and nanos MUST be in [0, 999_999_999].
        // Also required non-null so the staleness comparison below is meaningful — a ConfigUpdate
        // carrying no timestamp cannot be ordered against the stored peer_config_timestamp.
        final var peerTs = peerConfig.timestamp();
        validateTrueOrPenalize(
                peerTs != null && peerTs.seconds() >= 0 && peerTs.nanos() >= 0 && peerTs.nanos() < 1_000_000_000,
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);

        // Spec §1.3: the enclosed configuration's timestamp MUST be strictly greater than the
        // stored peer_config_timestamp.
        validateTrueOrPenalize(
                isTimestampBefore(peerConfigTimestamp, peerTs),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                endpointAccountId,
                penaltyAmount);
    }

    /**
     * Checks whether timestamp a is strictly before timestamp b.
     */
    private static boolean isTimestampBefore(final Timestamp a, final Timestamp b) {
        if (a == null || b == null) return a == null && b != null;
        if (a.seconds() != b.seconds()) return a.seconds() < b.seconds();
        return a.nanos() < b.nanos();
    }

    /**
     * Validates a condition and, if false, throws a {@link HandleException} whose
     * {@link HandleException.OnRollback} charges the endpoint's account a flat penalty.
     * The penalty is applied via {@link com.hedera.node.app.spi.fees.FeeCharging.Context#charge}
     * after the framework rolls back all state mutations, so it persists independently of the
     * failed transaction.
     *
     * @param condition          the condition that must be true
     * @param errorStatus        the response code to report on failure
     * @param endpointAccountId  the endpoint node's account to penalize
     * @param penaltyAmount      the penalty in tinybars
     */
    private static void validateTrueOrPenalize(
            final boolean condition,
            @NonNull final ResponseCodeEnum errorStatus,
            @NonNull final AccountID endpointAccountId,
            final long penaltyAmount) {
        if (!condition) {
            throw new HandleException(errorStatus, (feeChargingContext, ignored) -> {
                if (penaltyAmount > 0) {
                    try {
                        feeChargingContext.charge(endpointAccountId, new Fees(0, penaltyAmount, 0), null);
                    } catch (final Exception e) {
                        // Best effort — endpoint may be insolvent; the failed transaction
                        // status is punishment enough
                    }
                }
            });
        }
    }

    /**
     * Returns {@code true} if the peer's reported status relative to the local channel status
     * represents a state-transition that constitutes meaningful progress.
     *
     * @param peerStatus  the peer-reported {@link ClprChannelStatus} from the bundle metadata
     * @param localStatus the locally-stored {@link ClprChannelStatus} for this channel
     */
    private static boolean isStateTransitionProgress(
            @NonNull final ClprChannelStatus peerStatus, @NonNull final ClprChannelStatus localStatus) {
        // Peer shutting down triggers local CLOSING if still active
        if ((peerStatus == ClprChannelStatus.CLOSING
                        || peerStatus == ClprChannelStatus.DRAINED
                        || peerStatus == ClprChannelStatus.CLOSED)
                && (localStatus == ClprChannelStatus.ACTIVE || localStatus == ClprChannelStatus.PAUSED)) {
            return true;
        }
        // Peer drained/closed triggers local CLOSED when this channel is DRAINED
        if ((peerStatus == ClprChannelStatus.DRAINED || peerStatus == ClprChannelStatus.CLOSED)
                && localStatus == ClprChannelStatus.DRAINED) {
            return true;
        }
        // This channel is already CLOSING; bundle may complete the drain
        return localStatus == ClprChannelStatus.CLOSING;
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

        OutboundQueue(
                @NonNull final WritableMessageQueueStore msgStore,
                @NonNull final WritableChannelStore connStore,
                @NonNull final Bytes channelId) {
            this.msgStore = msgStore;
            this.connStore = connStore;
            this.channelId = channelId;
        }

        /** Enqueues an arbitrary payload and advances the channel's nextMessageId/sentRunningHash. */
        void enqueue(@NonNull final ClprMessagePayload payload) {
            final var conn = requireNonNull(connStore.getChannel(channelId));
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
            log.info(
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
            log.info(
                    "[ClprSubmitBundle] outbound enqueueReply conn={} inboundMsgId={} status={} dataLen={} data={}",
                    channelId,
                    inboundMessageId,
                    status,
                    data.length(),
                    shortHex(data));
            enqueue(ClprMessagePayload.newBuilder()
                    .messageReply(ClprMessageReply.newBuilder()
                            .messageId(inboundMessageId)
                            .status(status)
                            .messageReplyData(data)
                            .build())
                    .build());
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
