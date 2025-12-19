// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
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
import org.hiero.interledger.clpr.ReadableClprMessageQueueStore;
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
        if (!stateProofManager.clprEnabled()) {
            throw new PreCheckException(ResponseCodeEnum.NOT_SUPPORTED);
        }

        // TODO: Implement validate!
    }

    @Override
    public Response findResponse(@NonNull QueryContext context, @NonNull ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var op = query.getClprMessageQueueMetadata();
        final var ledgerId = op.ledgerId();
        final var readableMessageQueueMetadataStore = context.createStore(ReadableClprMessageQueueStore.class);
        final var metadata = readableMessageQueueMetadataStore.get(ledgerId);
        if (metadata != null) {
            final var result = ClprGetMessageQueueMetadataResponse.newBuilder()
                    .header(header)
                    .messageQueueMetadataProof(stateProofManager.getMessageQueueMetadata(ledgerId))
                    .build();
            return Response.newBuilder().clprMessageQueueMetadata(result).build();
        }
        return createEmptyResponse(header);
    }
}
