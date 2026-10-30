// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles {@link com.hedera.hapi.node.base.HederaFunctionality#CLPR_REGISTER_CHANNEL} transactions.
 *
 * <p>This is the commit phase of the commit-reveal channel registration scheme.
 * It stores an opaque ownership commitment hash. The reveal phase
 * ({@link ClprCompleteChannelHandler}) validates the preimage and creates the Channel.
 *
 * <p>A commitment stays registered while it is pending reveal and for as long as the Channel it
 * was revealed into exists, which includes CLOSED Channels, so re-registering it is rejected with
 * {@code CLPR_CHANNEL_ALREADY_EXISTS}.
 */
@Singleton
public class ClprRegisterChannelHandler extends AbstractClprHandler {

    @Inject
    public ClprRegisterChannelHandler() {
        // Exists for Dagger injection
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final var op = context.body().clprRegisterChannelOrThrow();
        validateTruePreCheck(op.ownershipCommitment().length() == COMMITMENT_LENGTH, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprRegisterChannelOrThrow();
        final var commitmentStore = context.storeFactory().writableStore(WritablePendingCommitmentStore.class);

        validateTrue(!commitmentStore.contains(op.ownershipCommitment()), CLPR_CHANNEL_ALREADY_EXISTS);
        commitmentStore.put(op.ownershipCommitment());
    }
}
