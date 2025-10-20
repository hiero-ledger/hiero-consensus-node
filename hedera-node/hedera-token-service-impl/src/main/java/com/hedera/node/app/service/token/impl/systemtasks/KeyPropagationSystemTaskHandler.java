// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.systemtasks;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.state.systemtask.KeyPropagation;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.IndirectKeyUsersKey;
import com.hedera.hapi.node.state.token.IndirectKeyUsersValue;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.records.CryptoUpdateStreamBuilder;
import com.hedera.node.app.spi.systemtasks.SystemTaskContext;
import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * SystemTaskHandler for processing KeyPropagation tasks for the Token Service.
 *
 * <p>See task description for the full algorithm. In brief, for key_account_id=A,
 * this handler will attempt to propagate A's materialized key to the next-in-line
 * indirect key user U, updating U's materialized key via a synthetic CryptoUpdate,
 * and schedule cascading propagation if needed.
 */
@Singleton
public class KeyPropagationSystemTaskHandler implements SystemTaskHandler {
    @Inject
    public KeyPropagationSystemTaskHandler() {
        // Dagger2
    }

    @Override
    public boolean supports(@NonNull final SystemTask task) {
        requireNonNull(task);
        return task.hasKeyPropagation();
    }

    @Override
    public void handle(@NonNull final SystemTaskContext context) {
        requireNonNull(context);
        final var task = context.currentTask();
        final KeyPropagation kp = task.keyPropagationOrThrow();
        final AccountID keyAccountId = kp.keyAccountIdOrThrow();
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final Account A = accountStore.get(keyAccountId);
        if (A == null || A.deleted()) {
            return; // Missing or deleted A; nothing to do
        }
        final AccountID uId = A.nextInLineKeyUserId();
        if (uId == null || uId.equals(AccountID.DEFAULT)) {
            return; // No user to process currently
        }
        final Account U = accountStore.get(uId);
        if (U != null && !U.deleted()) {
            final Key newMatForU = replaceIndirectRefsTo(
                    A.accountIdOrThrow(), A.materializedKeyOrThrow(), U.keyOrThrow(), U.materializedKeyOrThrow());
            if (newMatForU != null && !Objects.equals(newMatForU, U.materializedKey())) {
                final var originalU = U;
                // Tentatively update U, then submit synthetic update; rollback on failure
                final var uBuilder = U.copyBuilder().materializedKey(newMatForU);
                accountStore.put(uBuilder.build());
                //                final boolean ok = submitter.submit(uId, newMatForU);
                final var streamBuilder = context.dispatch(
                        b -> {
                            // Since this is an internal dispatch, the key will go straight to materialized, not
                            // template
                            b.cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                                    .accountIDToUpdate(U.accountIdOrThrow())
                                    .key(newMatForU));
                        },
                        CryptoUpdateStreamBuilder.class);
                validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
                // If U has indirect key users after update, schedule its own propagation
                final var updatedU = accountStore.get(uId);
                if (updatedU != null && updatedU.numIndirectKeyUsers() > 0) {
                    final var cascaded = updatedU.copyBuilder()
                            .maxRemainingPropagations(updatedU.numIndirectKeyUsers())
                            .nextInLineKeyUserId(updatedU.firstKeyUserId())
                            .build();
                    accountStore.put(cascaded);
                    final var cascadeTask = SystemTask.newBuilder()
                            .keyPropagation(KeyPropagation.newBuilder().keyAccountId(uId))
                            .build();
                    context.offer(cascadeTask);
                }
            }
        }
        // Advance A's propagation window
        int remaining = Math.max(0, A.maxRemainingPropagations() - 1);
        AccountID nextInLine = null;
        if (remaining > 0) {
            final var indirects = accountStore.indirectKeyUsers();
            nextInLine = nextUserInList(indirects, A.accountId(), uId, A.firstKeyUserId());
        }
        final var aBuilder = A.copyBuilder().maxRemainingPropagations(remaining);
        if (remaining == 0) {
            aBuilder.nextInLineKeyUserId(AccountID.DEFAULT);
        } else {
            aBuilder.nextInLineKeyUserId(nextInLine);
        }
        accountStore.put(aBuilder.build());
        if (remaining > 0) {
            // Re-enqueue propagation for A
            final var repeatTask = SystemTask.newBuilder()
                    .keyPropagation(KeyPropagation.newBuilder().keyAccountId(A.accountId()))
                    .build();
            context.offer(repeatTask);
        }
    }

    /**
     * Returns the next user in A's indirect users list after current, or wraps to first if current has no next.
     */
    private static AccountID nextUserInList(
            @NonNull final WritableKVState<IndirectKeyUsersKey, IndirectKeyUsersValue> state,
            @NonNull final AccountID aId,
            @NonNull final AccountID current,
            @NonNull final AccountID first) {
        final var value = state.get(IndirectKeyUsersKey.newBuilder()
                .keyAccountId(aId)
                .indirectUserId(current)
                .build());
        final var next = (value == null) ? AccountID.DEFAULT : value.nextUserId();
        return (next == null || next.equals(AccountID.DEFAULT)) ? first : next;
    }

    /**
     * Creates a new materialized key for U by walking U.template in parallel with U.materialized_key and replacing any
     * subtree where the template contains an IndirectKey referencing {@code target} with {@code replacement}.
     * If no replacements are needed, returns the original materialized key.
     */
    private static Key replaceIndirectRefsTo(
            @NonNull final AccountID target,
            @NonNull final Key replacement,
            @NonNull final Key template,
            @NonNull final Key currentMat) {
        if (template == null || template.key() == null) {
            return currentMat; // Nothing to replace
        }
        return switch (template.key().kind()) {
            case INDIRECT_KEY -> {
                final var ik = template.indirectKey();
                if (ik != null && ik.hasAccountId() && target.equals(ik.accountId())) {
                    yield replacement;
                } else {
                    yield currentMat;
                }
            }
            case KEY_LIST -> replaceInKeyList(target, replacement, template, currentMat);
            case THRESHOLD_KEY -> replaceInThreshold(target, replacement, template, currentMat);
            case ED25519, ECDSA_SECP256K1, RSA_3072, ECDSA_384, CONTRACT_ID, DELEGATABLE_CONTRACT_ID, UNSET ->
                currentMat;
        };
    }

    private static Key replaceInKeyList(
            final AccountID target, final Key replacement, final Key template, final Key currentMat) {
        final var tList = template.keyList();
        final var mList = (currentMat != null) ? currentMat.keyList() : null;
        if (tList == null) return currentMat;
        final var tKeys = tList.keys();
        final var mKeys = (mList != null) ? mList.keys() : null;
        boolean changed = false;
        final var out = new java.util.ArrayList<Key>(tKeys == null ? 0 : tKeys.size());
        for (int i = 0; i < (tKeys == null ? 0 : tKeys.size()); i++) {
            final var tChild = tKeys.get(i);
            final var mChild = (mKeys != null && i < mKeys.size()) ? mKeys.get(i) : null;
            final var newChild = replaceIndirectRefsTo(target, replacement, tChild, mChild);
            changed |= !Objects.equals(newChild, mChild);
            out.add(newChild);
        }
        if (!changed) return currentMat;
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(out)).build();
    }

    private static Key replaceInThreshold(
            final AccountID target, final Key replacement, final Key template, final Key currentMat) {
        final ThresholdKey t = template.thresholdKey();
        if (t == null) return currentMat;
        final var m = (currentMat != null) ? currentMat.thresholdKey() : null;
        final var tKeys = (t.keys() != null) ? t.keys().keys() : null;
        final var mKeys = (m != null && m.keys() != null) ? m.keys().keys() : null;
        boolean changed = false;
        final var out = new java.util.ArrayList<Key>(tKeys == null ? 0 : tKeys.size());
        for (int i = 0; i < (tKeys == null ? 0 : tKeys.size()); i++) {
            final var tChild = tKeys.get(i);
            final var mChild = (mKeys != null && i < mKeys.size()) ? mKeys.get(i) : null;
            final var newChild = replaceIndirectRefsTo(target, replacement, tChild, mChild);
            changed |= !Objects.equals(newChild, mChild);
            out.add(newChild);
        }
        if (!changed) return currentMat;
        final var newT = ThresholdKey.newBuilder()
                .threshold(t.threshold())
                .keys(KeyList.newBuilder().keys(out))
                .build();
        return Key.newBuilder().thresholdKey(newT).build();
    }
}
