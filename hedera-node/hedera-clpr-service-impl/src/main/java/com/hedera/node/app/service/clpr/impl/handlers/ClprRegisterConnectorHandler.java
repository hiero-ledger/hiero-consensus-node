// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_REGISTER_CONNECTOR} transactions.
 *
 * <p>Phase 1 (Commit): stores the commitment hash permissionlessly.
 * The caller must follow up with {@code completeConnector} to finalize registration.
 *
 * <p>A commitment stays registered while it is pending reveal and permanently once it is revealed
 * into a Connector (deregistration does not release it), so re-registering it is rejected with
 * {@code CLPR_CONNECTOR_ALREADY_EXISTS}.
 */
@Singleton
public class ClprRegisterConnectorHandler extends AbstractClprHandler {

    @Inject
    public ClprRegisterConnectorHandler() {}

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprRegisterConnectorOrThrow();
        validateTruePreCheck(op.commitment().length() == COMMITMENT_LENGTH, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprRegisterConnectorOrThrow();
        final var commitmentStore = context.storeFactory().writableStore(WritablePendingConnectorCommitmentStore.class);
        validateTrue(!commitmentStore.contains(op.commitment()), CLPR_CONNECTOR_ALREADY_EXISTS);
        commitmentStore.put(op.commitment());
    }
}
