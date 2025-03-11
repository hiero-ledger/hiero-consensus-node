// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.util;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class that computes the expected results of a pending airdrops removal and then commit the needed changes.
 * This way it will save several state updates of pending airdrop's linked list pointers {@code previousAirdrop()}
 * and {@code nextAirdrop()}, also sender's account updates of {@code headPendingAirdropId()} and
 * {@code numberPendingAirdrops()}
 */
@Singleton
public class PendingAirdropUpdater {
    private static final Logger log = LogManager.getLogger(PendingAirdropUpdater.class);

    @Inject
    public PendingAirdropUpdater() {}

    /**
     * Removes provided pending airdrops from the state.
     * Updates sender accounts ({@code headPendingAirdropId()} and {@code numberPendingAirdrops()}).
     * Update neighbour pending airdrops linked list pointers ({@code previousAirdrop()} and {@code nextAirdrop()}).
     *
     * @param airdropsToRemove set of PendingAirdropIds to be removed
     */
    public void removePendingAirdrops(
            @NonNull final Set<PendingAirdropId> airdropsToRemove,
            @NonNull final WritableAirdropStore pendingAirdropStore,
            @NonNull final WritableAccountStore accountStore) {
        for (final var id : airdropsToRemove) {
            removePendingAirdrops(id, pendingAirdropStore, accountStore);
        }
    }

    /**
     * Removes the given airdrop from the given stores, including updating the prev/next pointers.
     *
     * @param airdropId pending airdrop to remove
     * @param pendingAirdropStore store containing the pending airdrop
     * @param accountStore store containing the account
     */
    private void removePendingAirdrops(
            @NonNull final PendingAirdropId airdropId,
            @NonNull final WritableAirdropStore pendingAirdropStore,
            @NonNull final WritableAccountStore accountStore) {
        final var airdrop = pendingAirdropStore.get(airdropId);
        validateTrue(airdrop != null, INVALID_PENDING_AIRDROP_ID);

        // update pending airdrops links
        final var prevAirdropId = airdrop.previousAirdrop();
        final var nextAirdropId = airdrop.nextAirdrop();
        if (prevAirdropId != null) {
            final var prevAirdrop = pendingAirdropStore.get(prevAirdropId);
            if (prevAirdrop == null) {
                log.error("Failed to find pending airdrop with id {}", prevAirdropId);
            } else {
                final var updatedAirdrop =
                        prevAirdrop.copyBuilder().nextAirdrop(nextAirdropId).build();
                pendingAirdropStore.put(prevAirdropId, updatedAirdrop);
            }
        }
        if (nextAirdropId != null) {
            final var nextAirdrop = pendingAirdropStore.get(nextAirdropId);
            if (nextAirdrop == null) {
                log.error("Failed to find pending airdrop with id {}", nextAirdropId);
            } else {
                final var updatedAirdrop =
                        nextAirdrop.copyBuilder().previousAirdrop(prevAirdropId).build();
                pendingAirdropStore.put(nextAirdropId, updatedAirdrop);
            }
        }
        final var senderAccount = accountStore.getAccountById(airdropId.senderIdOrThrow());
        if (senderAccount == null) {
            log.error("Failed to find sender account with id {}", airdropId.senderIdOrThrow());
        } else {
            final var updatedSender =
                    senderAccount.copyBuilder().numberPendingAirdrops(senderAccount.numberPendingAirdrops() - 1);
            if (airdropId.equals(senderAccount.headPendingAirdropId())) {
                updatedSender.headPendingAirdropId(airdrop.nextAirdrop());
            }
            accountStore.put(updatedSender.build());
        }
        pendingAirdropStore.remove(airdropId);
    }
}
