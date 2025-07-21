package org.hiero.interledger.clpr.impl.handlers;

import com.hedera.node.app.spi.workflows.*;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ClprSetLedgerConfigurationHandler implements TransactionHandler {
    @Override
    public void preHandle(@NonNull PreHandleContext context) throws PreCheckException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void pureChecks(@NonNull PureChecksContext context) throws PreCheckException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void handle(@NonNull HandleContext context) throws HandleException {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
