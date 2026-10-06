// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_CLOSE_CHANNEL} transactions.
 *
 * <p>Three cases:
 * <ol>
 *   <li>A Channel record exists for {@code channel_id} in ACTIVE or PAUSED status —
 *       transitions it to DRAINED (if fully acknowledged or nothing sent) or CLOSING (if unacked
 *       messages remain).</li>
 *   <li>A Channel record exists in DRAINED status — admin recovery path; transitions it to
 *       CLOSED and calls {@code channelLifecycle.onChannelClosed()}.</li>
 *   <li>No Channel record exists (PENDING state: only a {@code PENDING_COMMITMENTS} entry)
 *       and {@code ownership_commitment} is provided — deletes the pending commitment,
 *       cleaning up an abandoned registration.</li>
 * </ol>
 * Requires the CLPR admin key (network admin / superuser).
 */
@Singleton
public final class ClprCloseChannelHandler extends AbstractClprHandler {

    private final ClprChannelLifecycle channelLifecycle;

    @Inject
    public ClprCloseChannelHandler(@NonNull final ClprChannelLifecycle channelLifecycle) {
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprCloseChannelOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // Authorization enforced by PrivilegesVerifier.checkClprAdmin — only
        // treasury and system admin accounts are permitted.
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprCloseChannelOrThrow();
        final var storeFactory = context.storeFactory();
        final var channelStore = storeFactory.writableStore(WritableChannelStore.class);
        final var commitmentStore = storeFactory.writableStore(WritablePendingCommitmentStore.class);

        final var existingChannel = channelStore.getChannel(op.channelId());

        if (existingChannel != null) {
            final var status = existingChannel.status();
            if (status == ClprChannelStatus.PENDING) {
                channelStore.remove(op.channelId());
                commitmentStore.remove(existingChannel.ownershipCommitment());
                return;
            }
            if (status == ClprChannelStatus.CLOSING || status == ClprChannelStatus.CLOSED) {
                throw new HandleException(CLPR_INVALID_CHANNEL_STATUS);
            }

            final ClprChannelStatus newStatus;
            if (status == ClprChannelStatus.DRAINED) {
                newStatus = ClprChannelStatus.CLOSED;
            } else {
                newStatus = queueFullyAcked(existingChannel) ? ClprChannelStatus.DRAINED : ClprChannelStatus.CLOSING;
            }

            channelStore.put(existingChannel.copyBuilder().status(newStatus).build());

            if (newStatus == ClprChannelStatus.CLOSED) {
                channelLifecycle.onChannelClosed(op.channelId());
            }
        } else {
            // No Channel record — attempt to clean up an abandoned PENDING commitment.
            final Bytes commitment = op.ownershipCommitment();
            if (commitment.length() != COMMITMENT_LENGTH || !commitmentStore.contains(commitment)) {
                throw new HandleException(CLPR_CHANNEL_NOT_FOUND);
            }
            commitmentStore.remove(commitment);
        }
    }

    private static boolean queueFullyAcked(@NonNull final ClprChannel existingChannel) {
        return existingChannel.nextMessageId() == 0
                || existingChannel.ackedMessageId() >= existingChannel.nextMessageId() - 1;
    }
}
