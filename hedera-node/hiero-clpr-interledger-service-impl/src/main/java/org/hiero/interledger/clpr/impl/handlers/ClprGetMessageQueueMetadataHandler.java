// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

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
import org.hiero.hapi.interledger.clpr.ClprGetMessageQueueMetadataResponse;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

public class ClprGetMessageQueueMetadataHandler extends FreeQueryHandler {
    private static final Logger log = LogManager.getLogger(ClprGetMessageQueueMetadataHandler.class);
    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprGetMessageQueueMetadataHandler(@NonNull final ClprStateProofManager stateProofManager) {
        requireNonNull(stateProofManager);
        this.stateProofManager = stateProofManager;
    }

    @Override
    public QueryHeader extractHeader(@NonNull Query query) {
        requireNonNull(query);
        return query.getClprMessageQueueMetadata().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull ResponseHeader header) {
        requireNonNull(header);
        final var response = ClprGetMessageQueueMetadataResponse.newBuilder().header(header);
        return Response.newBuilder().clprMessageQueueMetadata(response).build();
    }

    @Override
    public void validate(@NonNull QueryContext context) throws PreCheckException {
        requireNonNull(context);
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        final var query = context.query();
        final var op = query.getClprMessageQueueMetadataOrThrow();
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
        final var op = query.getClprMessageQueueMetadata();
        final var ledgerId = op.ledgerId();
        // TEMP-OBSERVABILITY (delete before production): traces queue-metadata query ingress by remote ledger id.
        log.info(
                "CLPR_OBS|component=clpr_get_message_queue_metadata_handler|stage=query_received|remoteLedgerId={}",
                ledgerId.ledgerId());
        final var readableMessageQueueMetadataStore = context.createStore(ReadableClprMessageQueueMetadataStore.class);
        final var metadata = readableMessageQueueMetadataStore.get(ledgerId);
        if (metadata != null) {
            // TEMP-OBSERVABILITY (delete before production): traces successful metadata proof query response.
            log.info(
                    "CLPR_OBS|component=clpr_get_message_queue_metadata_handler|stage=query_success|nextMessageId={}|sentMessageId={}|receivedMessageId={}",
                    metadata.nextMessageId(),
                    metadata.sentMessageId(),
                    metadata.receivedMessageId());
            final var result = ClprGetMessageQueueMetadataResponse.newBuilder()
                    .header(header)
                    .messageQueueMetadataProof(stateProofManager.getMessageQueueMetadata(ledgerId))
                    .build();
            return Response.newBuilder().clprMessageQueueMetadata(result).build();
        }
        // TEMP-OBSERVABILITY (delete before production): traces empty metadata query response for unknown queue.
        log.info("CLPR_OBS|component=clpr_get_message_queue_metadata_handler|stage=query_empty|reason=missing_queue");
        return createEmptyResponse(header);
    }
}
