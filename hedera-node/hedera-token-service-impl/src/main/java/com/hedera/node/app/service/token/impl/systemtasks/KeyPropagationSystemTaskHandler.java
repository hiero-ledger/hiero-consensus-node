// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.systemtasks;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.concreteKeyOf;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.state.systemtask.KeyPropagation;
import com.hedera.hapi.node.state.systemtask.SystemTask;
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
        final var op = task.keyPropagationOrThrow();
        final var keyAccountId = op.keyAccountIdOrThrow();
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final var keyAccount = accountStore.get(keyAccountId);
        if (keyAccount == null || keyAccount.deleted()) {
            return;
        }
        final var nextUserId = keyAccount.nextInLineKeyUserId();
        if (nextUserId == null || nextUserId.equals(AccountID.DEFAULT)) {
            return;
        }
        final var userAccount = accountStore.get(nextUserId);
        if (userAccount != null && !userAccount.deleted()) {
            final var userConcreteKey = userAccount.materializedKeyOrThrow();
            final var keyToPropagate = replaceIndirectRefsTo(
                    keyAccount.accountIdOrThrow(),
                    concreteKeyOf(keyAccount),
                    userAccount.keyOrThrow(),
                    userConcreteKey);
            if (!Objects.equals(keyToPropagate, userConcreteKey)) {
                // Note the user receiving the key propagation pays for this dispatch
                final var streamBuilder = context.dispatch(
                        userAccount.accountIdOrThrow(),
                        b -> {
                            // System task dispatch will make the key will go straight to materialized, not template
                            b.cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                                    .accountIDToUpdate(userAccount.accountIdOrThrow())
                                    .key(keyToPropagate));
                        },
                        CryptoUpdateStreamBuilder.class, CRYPTO_UPDATE);
                validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
                // If the user has indirect key users after update, schedule its own propagation
                final var updatedUserAccount = requireNonNull(accountStore.get(nextUserId));
                if (updatedUserAccount.numIndirectKeyUsers() > 0) {
                    accountStore.put(updatedUserAccount
                            .copyBuilder()
                            .maxRemainingPropagations(updatedUserAccount.numIndirectKeyUsers())
                            .nextInLineKeyUserId(updatedUserAccount.firstKeyUserId())
                            .build());
                    context.offer(SystemTask.newBuilder()
                            .keyPropagation(KeyPropagation.newBuilder().keyAccountId(nextUserId))
                            .build());
                }
            }
        }
        // Advance A's propagation window
        int remaining = Math.max(0, keyAccount.maxRemainingPropagations() - 1);
        AccountID nextInLineId = null;
        if (remaining > 0) {
            nextInLineId = nextUserInList(
                    accountStore.indirectKeyUsers(),
                    keyAccount.accountIdOrThrow(),
                    nextUserId,
                    keyAccount.firstKeyUserIdOrThrow());
        }
        final var builder = keyAccount.copyBuilder().maxRemainingPropagations(remaining);
        if (remaining == 0) {
            builder.nextInLineKeyUserId((AccountID) null);
        } else {
            builder.nextInLineKeyUserId(nextInLineId);
        }
        accountStore.put(builder.build());
        if (remaining > 0) {
            // Re-enqueue propagation for A
            context.offer(SystemTask.newBuilder()
                    .keyPropagation(KeyPropagation.newBuilder().keyAccountId(keyAccount.accountId()))
                    .build());
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
            @NonNull final Key targetReplacement,
            @NonNull final Key templateKey,
            @NonNull final Key concreteKey) {
        return switch (templateKey.key().kind()) {
            case INDIRECT_KEY -> {
                final var indirectKey = templateKey.indirectKey();
                if (indirectKey != null && indirectKey.hasAccountId() && target.equals(indirectKey.accountId())) {
                    yield targetReplacement;
                } else {
                    yield concreteKey;
                }
            }
            case KEY_LIST -> replaceInKeyList(target, targetReplacement, templateKey, concreteKey);
            case THRESHOLD_KEY -> replaceInThreshold(target, targetReplacement, templateKey, concreteKey);
            default -> concreteKey;
        };
    }

    private static Key replaceInKeyList(
            @NonNull final AccountID target,
            @NonNull final Key targetReplacement,
            @NonNull final Key templateKey,
            @NonNull final Key concreteKey) {
        final var tList = templateKey.keyList();
        if (tList == null) {
            return concreteKey;
        }
        final var mList = concreteKey.keyListOrThrow();
        final var tKeys = tList.keys();
        final var mKeys = mList.keys();
        boolean changed = false;
        final var out = new java.util.ArrayList<Key>(tKeys.size());
        for (int i = 0, n = tKeys.size(); i < n; i++) {
            final var tChild = tKeys.get(i);
            final var mChild = requireNonNull(mKeys.get(i));
            final var newChild = replaceIndirectRefsTo(target, targetReplacement, tChild, mChild);
            changed |= !Objects.equals(newChild, mChild);
            out.add(newChild);
        }
        if (!changed) {
            return concreteKey;
        }
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(out)).build();
    }

    private static Key replaceInThreshold(
            @NonNull final AccountID target,
            @NonNull final Key targetReplacement,
            @NonNull final Key templateKey,
            @NonNull final Key concreteKey) {
        final var t = templateKey.thresholdKey();
        if (t == null) {
            return concreteKey;
        }
        final var m = concreteKey.thresholdKeyOrThrow();
        final var tKeys = t.keysOrThrow().keys();
        final var mKeys = m.keysOrThrow().keys();
        boolean changed = false;
        final var out = new java.util.ArrayList<Key>(tKeys.size());
        for (int i = 0, n = tKeys.size(); i < n; i++) {
            final var tChild = tKeys.get(i);
            final var mChild = requireNonNull(mKeys.get(i));
            final var newChild = replaceIndirectRefsTo(target, targetReplacement, tChild, mChild);
            changed |= !Objects.equals(newChild, mChild);
            out.add(newChild);
        }
        if (!changed) {
            return concreteKey;
        }
        return Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(t.threshold())
                        .keys(KeyList.newBuilder().keys(out))
                        .build())
                .build();
    }
}
