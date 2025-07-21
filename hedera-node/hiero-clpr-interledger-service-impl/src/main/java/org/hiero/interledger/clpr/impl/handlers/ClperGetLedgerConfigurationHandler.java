package org.hiero.interledger.clpr.impl.handlers;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ClperGetLedgerConfigurationHandler extends FreeQueryHandler {

    @Override
    public QueryHeader extractHeader(@NonNull Query query) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Response createEmptyResponse(@NonNull ResponseHeader header) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void validate(@NonNull QueryContext context) throws PreCheckException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Response findResponse(@NonNull QueryContext context, @NonNull ResponseHeader header) {
        throw new UnsupportedOperationException("Not supported yet.");   }
}
