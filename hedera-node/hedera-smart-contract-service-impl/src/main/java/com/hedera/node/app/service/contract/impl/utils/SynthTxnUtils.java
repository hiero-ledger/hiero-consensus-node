// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Some utilities related to synthetic transaction bodies.
 */
public class SynthTxnUtils {
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    public static final Duration DEFAULT_AUTO_RENEW_PERIOD =
            Duration.newBuilder().seconds(THREE_MONTHS_IN_SECONDS).build();

    private SynthTxnUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given a validated {@link ContractCreateTransactionBody} and its pending id, returns the
     * corresponding {@link CryptoCreateTransactionBody} to dispatch.
     *
     * @param pendingId the pending id
     * @param body the {@link ContractCreateTransactionBody}
     * @return the corresponding {@link CryptoCreateTransactionBody}
     */
    public static CryptoCreateTransactionBody synthAccountCreationFromHapi(
            @NonNull final ContractID pendingId,
            @Nullable final com.hedera.pbj.runtime.io.buffer.Bytes evmAddress,
            @NonNull final ContractCreateTransactionBody body) {
        requireNonNull(body);
        requireNonNull(pendingId);
        final var builder = CryptoCreateTransactionBody.newBuilder()
                .maxAutomaticTokenAssociations(body.maxAutomaticTokenAssociations())
                .declineReward(body.declineReward())
                .memo(body.memo());
        if (body.hasAutoRenewPeriod()) {
            builder.autoRenewPeriod(body.autoRenewPeriodOrThrow());
        }
        if (body.hasStakedNodeId()) {
            builder.stakedNodeId(body.stakedNodeIdOrThrow());
        } else if (body.hasStakedAccountId()) {
            builder.stakedAccountId(body.stakedAccountIdOrThrow());
        }
        if (body.hasAdminKey() && !isEmpty(body.adminKeyOrThrow())) {
            builder.key(body.adminKeyOrThrow());
        } else {
            builder.key(Key.newBuilder().contractID(pendingId));
        }
        if (evmAddress != null) {
            builder.alias(evmAddress);
        }
        return builder.build();
    }

    /**
     * Given the "parent" {@link Account} creating a contract and the contract's pending id,
     * returns the corresponding {@link ContractCreateTransactionBody} to dispatch.
     *
     * @param pendingId the pending id
     * @param parent the {@link Account} creating the contract
     * @return the corresponding {@link CryptoCreateTransactionBody}
     */
    public static ContractCreateTransactionBody synthContractCreationFromParent(
            @NonNull final ContractID pendingId, @NonNull final Account parent) {
        requireNonNull(parent);
        requireNonNull(pendingId);
        final var builder = ContractCreateTransactionBody.newBuilder()
                .maxAutomaticTokenAssociations(parent.maxAutoAssociations())
                .declineReward(parent.declineReward())
                .memo(parent.memo())
                .autoRenewPeriod(Duration.newBuilder().seconds(parent.autoRenewSeconds()));
        if (hasNonDegenerateAutoRenewAccountId(parent)) {
            builder.autoRenewAccountId(parent.autoRenewAccountIdOrThrow());
        }
        if (parent.hasStakedNodeId()) {
            builder.stakedNodeId(parent.stakedNodeIdOrThrow());
        } else if (parent.hasStakedAccountId()) {
            builder.stakedAccountId(parent.stakedAccountIdOrThrow());
        }

        if (!parent.hasKey() || isSelfAdmin(parent)) {
            // The new contract will manage itself as well, which we indicate via self-referential admin key
            builder.adminKey(Key.newBuilder().contractID(pendingId));
        } else {
            final var parentAdminKey = parent.keyOrThrow();
            builder.adminKey(parentAdminKey);
        }
        return builder.build();
    }

    /**
     * Create a new empty {@link ContractCreateTransactionBody} with only the key set for externalization.
     *
     * @param pendingId the pending id
     * @return the corresponding {@link CryptoCreateTransactionBody}
     */
    public static ContractCreateTransactionBody synthContractCreationForExternalization(
            @NonNull final ContractID pendingId) {
        requireNonNull(pendingId);
        return ContractCreateTransactionBody.newBuilder()
                .adminKey(Key.newBuilder().contractID(pendingId).build())
                .build();
    }

    /**
     * Returns whether the given account has an auto-renew account id that is not
     * {@code 0.0.0}.
     *
     * @param account the account
     * @return whether the given account has an auto-renew account id that is not
     */
    public static boolean hasNonDegenerateAutoRenewAccountId(@NonNull final Account account) {
        return account.hasAutoRenewAccountId()
                && account.autoRenewAccountIdOrThrow().accountNumOrElse(0L) != 0L;
    }

    /**
     * Given an EVM address being lazy-created, returns the corresponding {@link CryptoCreateTransactionBody}
     * to dispatch.
     *
     * @param evmAddress the EVM address
     * @return the corresponding {@link CryptoCreateTransactionBody}
     */
    public static CryptoCreateTransactionBody synthHollowAccountCreation(@NonNull final Bytes evmAddress) {
        requireNonNull(evmAddress);
        return CryptoCreateTransactionBody.newBuilder()
                .initialBalance(0L)
                .alias(evmAddress)
                .key(IMMUTABILITY_SENTINEL_KEY)
                .autoRenewPeriod(DEFAULT_AUTO_RENEW_PERIOD)
                .build();
    }

    /**
     * Given an {@link com.hedera.node.app.hapi.utils.ethereum.EthTxData} representing a {@code CONTRACT_CREATION}
     * call, returns the implied {@link ContractCreateTransactionBody} with the request auto-renew period.
     *
     * @param autoRenewPeriod the auto-renew period
     * @param ethTxData the {@link com.hedera.node.app.hapi.utils.ethereum.EthTxData}
     * @return the corresponding {@link CryptoCreateTransactionBody}
     */
    public static ContractCreateTransactionBody synthEthTxCreation(
            final long autoRenewPeriod, @NonNull final EthTxData ethTxData) {
        requireNonNull(ethTxData);
        return ContractCreateTransactionBody.newBuilder()
                .gas(ethTxData.gasLimit())
                .initialBalance(ethTxData.effectiveTinybarValue())
                .autoRenewPeriod(Duration.newBuilder().seconds(autoRenewPeriod))
                .initcode(Bytes.wrap(ethTxData.callData()))
                .build();
    }

    private static boolean isSelfAdmin(@NonNull final Account parent) {
        final long adminNum =
                parent.keyOrThrow().contractIDOrElse(ContractID.DEFAULT).contractNumOrElse(0L);
        return parent.accountIdOrThrow().accountNumOrThrow() == adminNum;
    }
}
