// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.createBundle;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.ClprGetMessagesResponse;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.ReadableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

public class ClprGetMessagesHandler extends FreeQueryHandler {
    private static final Logger log = LogManager.getLogger(ClprGetMessagesHandler.class);
    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprGetMessagesHandler(final ClprStateProofManager stateProofManager) {
        requireNonNull(stateProofManager);
        this.stateProofManager = stateProofManager;
    }

    @Override
    public QueryHeader extractHeader(@NonNull Query query) {
        requireNonNull(query);
        return query.getClprMessages().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull ResponseHeader header) {
        requireNonNull(header);
        final var response = ClprGetMessagesResponse.newBuilder().header(header);
        return Response.newBuilder().clprMessages(response).build();
    }

    @Override
    public void validate(@NonNull QueryContext context) throws PreCheckException {
        requireNonNull(context);
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        final var query = context.query();
        final var op = query.getClprMessagesOrThrow();
        // validate required field
        validateTruePreCheck(op.hasLedgerId(), CLPR_INVALID_LEDGER_ID);

        final var remoteLedgerId = op.ledgerIdOrThrow();
        final var queueStore = context.createStore(ReadableClprMessageQueueMetadataStore.class);
        final var queueMetadata = queueStore.get(remoteLedgerId);
        // validate queue for remote ledger exists
        validateTruePreCheck(queueMetadata != null, CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);
    }

    @Override
    public Response findResponse(@NonNull QueryContext context, @NonNull ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();

        final var op = query.getClprMessagesOrThrow();
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        final var remoteLedgerId = op.ledgerIdOrThrow();
        final var bundleSize = op.maxNumberOfMessages();
        final var readableMessageQueueStore = context.createStore(ReadableClprMessageQueueMetadataStore.class);
        final var readableMessagesStore = context.createStore(ReadableClprMessageStore.class);
        final var messageQueue = readableMessageQueueStore.get(remoteLedgerId);
        // TEMP-OBSERVABILITY (delete before production): traces CLPR message query ingress and requested bundle size.
        log.info(
                "CLPR_OBS|component=clpr_get_messages_handler|stage=query_received|remoteLedgerId={}|bundleSize={}",
                remoteLedgerId.ledgerId(),
                bundleSize);

        if (bundleSize <= 0) {
            // TEMP-OBSERVABILITY (delete before production): traces empty-result reason for CLPR message query.
            log.info(
                    "CLPR_OBS|component=clpr_get_messages_handler|stage=query_empty|reason=non_positive_bundle_size|bundleSize={}",
                    bundleSize);
            return createEmptyResponse(header);
        }

        final var firsMessageInBundle = messageQueue.sentMessageId() + 1;
        final var lastAvailableMessageId = messageQueue.nextMessageId() - 1;
        if (firsMessageInBundle > lastAvailableMessageId) {
            // TEMP-OBSERVABILITY (delete before production): traces empty-result reason when queue has nothing to send.
            log.info(
                    "CLPR_OBS|component=clpr_get_messages_handler|stage=query_empty|reason=no_available_messages|firstMessageInBundle={}|lastAvailableMessageId={}",
                    firsMessageInBundle,
                    lastAvailableMessageId);
            return createEmptyResponse(header);
        }
        final var lastMessageInBundle = Math.min(firsMessageInBundle + bundleSize - 1, lastAvailableMessageId);

        final var bundle = createBundle(
                firsMessageInBundle, lastMessageInBundle, localLedgerId, remoteLedgerId, readableMessagesStore::get);
        if (bundle != null) {
            // TEMP-OBSERVABILITY (delete before production): traces successful bundle assembly for remote pull.
            log.info(
                    "CLPR_OBS|component=clpr_get_messages_handler|stage=query_success|firstMessageInBundle={}|lastMessageInBundle={}",
                    firsMessageInBundle,
                    lastMessageInBundle);
            final var result = ClprGetMessagesResponse.newBuilder()
                    .header(header)
                    .messageBundle(bundle)
                    .build();
            return Response.newBuilder().clprMessages(result).build();
        }

        // TEMP-OBSERVABILITY (delete before production): traces empty-result fallback when bundle assembly returns
        // null.
        log.info("CLPR_OBS|component=clpr_get_messages_handler|stage=query_empty|reason=null_bundle");
        return createEmptyResponse(header);
    }
}
