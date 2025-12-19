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
import org.hiero.hapi.interledger.clpr.ClprGetMessagesResponse;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

public class ClprGetMessagesHandler extends FreeQueryHandler {
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
        final var op = query.getClprMessages();

        // TODO: Implement find response!
        return createEmptyResponse(header);
    }
}
