// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ApiPermissionConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * An implementation of {@link Authorizer}.
 */
@Singleton
public class AuthorizerImpl implements Authorizer {

    private final ConfigProvider configProvider;
    private final AccountsConfig accountsConfig;
    private final long shard;
    private final long realm;
    private final PrivilegesVerifier privilegedTransactionChecker;

    @Inject
    public AuthorizerImpl(
            @NonNull final ConfigProvider configProvider, @NonNull PrivilegesVerifier privilegedTransactionChecker) {
        this.configProvider = requireNonNull(configProvider);
        final var configuration = configProvider.getConfiguration();
        this.accountsConfig = configuration.getConfigData(AccountsConfig.class);
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        this.shard = hederaConfig.shard();
        this.realm = hederaConfig.realm();
        this.privilegedTransactionChecker = requireNonNull(privilegedTransactionChecker);
    }

    @Override
    public boolean isAuthorized(@NonNull final AccountID id, @NonNull final HederaFunctionality function) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(function);
        return permissibilityOf(id, function) == OK;
    }

    @Override
    public boolean isSuperUser(@NonNull final AccountID accountID) {
        if (!isOnThisLedger(accountID)) return false;
        final long num = accountID.accountNumOrThrow();
        return num == accountsConfig.treasury() || num == accountsConfig.systemAdmin();
    }

    @Override
    public boolean isTreasury(@NonNull final AccountID accountID) {
        if (!isOnThisLedger(accountID)) return false;
        return accountID.accountNumOrThrow() == accountsConfig.treasury();
    }

    /**
     * Returns whether the given id is a number-based account scoped to this ledger's shard and realm. Privilege
     * checks must consider the full {@code shard.realm.num} triplet rather than the account number alone, so that a
     * foreign-shard or foreign-realm id such as {@code 1.0.2} is never treated as the treasury account {@code 0.0.2}.
     *
     * @param accountID the account id to check
     * @return true if the id has an account number in this ledger's shard and realm
     */
    private boolean isOnThisLedger(@NonNull final AccountID accountID) {
        return accountID.hasAccountNum() && accountID.shardNum() == shard && accountID.realmNum() == realm;
    }

    @Override
    public SystemPrivilege hasPrivilegedAuthorization(
            @NonNull final AccountID payerId,
            @NonNull final HederaFunctionality functionality,
            @NonNull final TransactionBody txBody) {
        return privilegedTransactionChecker.hasPrivileges(payerId, functionality, txBody);
    }

    private ResponseCodeEnum permissibilityOf(
            @NonNull final AccountID givenPayer, @NonNull final HederaFunctionality function) {
        if (isSuperUser(givenPayer)) {
            return OK;
        }
        if (!givenPayer.hasAccountNum()) {
            return AUTHORIZATION_FAILED;
        }
        final long num = givenPayer.accountNumOrThrow();
        final var permissionConfig = configProvider.getConfiguration().getConfigData(ApiPermissionConfig.class);
        final var permission = permissionConfig.getPermission(function);
        return permission != null && permission.contains(num) ? OK : NOT_SUPPORTED;
    }
}
