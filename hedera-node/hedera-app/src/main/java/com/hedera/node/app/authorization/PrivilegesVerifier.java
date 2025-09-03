// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.AUTHORIZED;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.IMPERMISSIBLE;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.UNAUTHORIZED;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.UNNECESSARY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Checks whether an account is authorized to perform a system transaction that requires
 * privileged access.
 *
 * <p>The checks in this class do not require access to state, and thus can be performed at any time.
 */
public class PrivilegesVerifier {
    private static final long FIRST_SYSTEM_FILE_ENTITY = 101L;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;

    private final AccountsConfig accountsConfig;
    private final FilesConfig filesConfig;
    private final long numReservedSystemEntities;

    @Inject
    public PrivilegesVerifier(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider, "configProvider cannot be null");

        final var configuration = configProvider.getConfiguration();
        this.accountsConfig = configuration.getConfigData(AccountsConfig.class);
        this.filesConfig = configuration.getConfigData(FilesConfig.class);
        this.numReservedSystemEntities =
                configuration.getConfigData(LedgerConfig.class).numReservedSystemEntities();
    }

    /**
     * Checks whether an account is exempt from paying fees.
     *
     * @param payerId the payer {@link AccountID} for the transaction
     * @param functionality the {@link HederaFunctionality} of the transaction
     * @param txBody the {@link TransactionBody} of the transaction
     * @return {@code true} if the account is exempt from paying fees, otherwise {@code false}
     */
    public SystemPrivilege hasPrivileges(
            @NonNull final AccountID payerId,
            @NonNull final HederaFunctionality functionality,
            @NonNull final TransactionBody txBody) {
        return switch (functionality) {
            // Authorization privileges for special transactions
            case FREEZE -> checkFreeze(payerId);
            case SYSTEM_DELETE -> checkSystemDelete(payerId, txBody.systemDeleteOrThrow());
            case SYSTEM_UNDELETE -> checkSystemUndelete(payerId, txBody.systemUndeleteOrThrow());
            case UNCHECKED_SUBMIT -> checkUncheckedSubmit(payerId);

            // Authorization privileges for file updates and appends
            case FILE_UPDATE ->
                checkFileChange(
                        payerId, txBody.fileUpdateOrThrow().fileIDOrThrow().fileNum());
            case FILE_APPEND ->
                checkFileChange(
                        payerId, txBody.fileAppendOrThrow().fileIDOrThrow().fileNum());
            // Authorization for crypto updates
            case CRYPTO_UPDATE -> checkCryptoUpdate(payerId, txBody.cryptoUpdateAccountOrThrow());

            // Authorization for deletes
            case FILE_DELETE ->
                checkEntityDelete(txBody.fileDeleteOrThrow().fileIDOrThrow().fileNum());
            case CRYPTO_DELETE ->
                checkCryptoDelete(
                        effectiveNumber(txBody.cryptoDeleteOrThrow().deleteAccountIDOrElse(AccountID.DEFAULT)));
            case NODE_CREATE -> checkNodeCreate(payerId);
            default -> SystemPrivilege.UNNECESSARY;
        };
    }

    private boolean isSuperUser(@NonNull final AccountID accountID) {
        final long accountNum = effectiveNumber(accountID);
        return accountNum == accountsConfig.treasury() || accountNum == accountsConfig.systemAdmin();
    }

    private boolean isTreasury(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.treasury();
    }

    private boolean hasSoftwareUpdatePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.softwareUpdateAdmin() || isSuperUser(accountID);
    }

    private boolean hasFreezePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.freezeAdmin() || isSuperUser(accountID);
    }

    private boolean hasSystemDeletePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.systemDeleteAdmin() || isSuperUser(accountID);
    }

    private boolean hasSystemUndeletePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.systemUndeleteAdmin() || isSuperUser(accountID);
    }

    private boolean hasAddressBookPrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.addressBookAdmin() || isSuperUser(accountID);
    }

    private boolean hasExchangeRatePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.exchangeRatesAdmin() || isSuperUser(accountID);
    }

    private boolean hasFeeSchedulePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.feeSchedulesAdmin() || isSuperUser(accountID);
    }

    private boolean hasNodeCreatePrivilege(@NonNull final AccountID accountID) {
        return effectiveNumber(accountID) == accountsConfig.addressBookAdmin() || isSuperUser(accountID);
    }

    private SystemPrivilege checkFreeze(@NonNull final AccountID accountID) {
        return hasFreezePrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
    }

    private SystemPrivilege checkSystemDelete(
            @NonNull final AccountID accountID, @NonNull final SystemDeleteTransactionBody op) {
        final var entityNum = op.hasFileID() ? op.fileIDOrThrow().fileNum() : effectiveNumber(op.contractIDOrThrow());
        if (isSystemEntity(entityNum)) {
            return IMPERMISSIBLE;
        }
        return hasSystemDeletePrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
    }

    private SystemPrivilege checkSystemUndelete(
            @NonNull final AccountID accountID, @NonNull final SystemUndeleteTransactionBody op) {
        final var entityNum = op.hasFileID() ? op.fileIDOrThrow().fileNum() : effectiveNumber(op.contractIDOrThrow());
        if (isSystemEntity(entityNum)) {
            return IMPERMISSIBLE;
        }
        return hasSystemUndeletePrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
    }

    private SystemPrivilege checkUncheckedSubmit(@NonNull final AccountID accountID) {
        return isSuperUser(accountID) ? AUTHORIZED : UNAUTHORIZED;
    }

    private SystemPrivilege checkFileChange(@NonNull final AccountID accountID, final long entityNum) {
        if (!isSystemEntity(entityNum)) {
            return UNNECESSARY;
        }
        if (entityNum == filesConfig.addressBook() || entityNum == filesConfig.nodeDetails()) {
            return hasAddressBookPrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
        } else if (entityNum == filesConfig.networkProperties() || entityNum == filesConfig.hapiPermissions()) {
            return hasAddressBookPrivilege(accountID) || hasExchangeRatePrivilege(accountID)
                    ? AUTHORIZED
                    : UNAUTHORIZED;
        } else if (entityNum == filesConfig.feeSchedules()) {
            return hasFeeSchedulePrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
        } else if (entityNum == filesConfig.exchangeRates()) {
            return hasExchangeRatePrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
        } else if (filesConfig.softwareUpdateRange().left() <= entityNum
                && entityNum <= filesConfig.softwareUpdateRange().right()) {
            return hasSoftwareUpdatePrivilege(accountID) ? AUTHORIZED : UNAUTHORIZED;
        } else if (entityNum == filesConfig.throttleDefinitions()) {
            return hasAddressBookPrivilege(accountID) || hasExchangeRatePrivilege(accountID)
                    ? AUTHORIZED
                    : UNAUTHORIZED;
        }
        return UNAUTHORIZED;
    }

    private SystemPrivilege checkCryptoUpdate(
            @NonNull final AccountID payerId, @NonNull final CryptoUpdateTransactionBody op) {
        // When dispatching a hollow account finalization, the target account id is set to DEFAULT
        final var targetId = op.accountIDToUpdateOrElse(AccountID.DEFAULT);
        final long targetNum = effectiveNumber(targetId);
        if (!isSystemEntity(targetNum)) {
            return UNNECESSARY;
        } else {
            final long payerNum = payerId.accountNumOrElse(0L);
            if (payerNum == accountsConfig.treasury()) {
                return AUTHORIZED;
            } else if (payerNum == accountsConfig.systemAdmin()) {
                return isTreasury(targetId) ? UNAUTHORIZED : AUTHORIZED;
            } else {
                return isTreasury(targetId) ? UNAUTHORIZED : UNNECESSARY;
            }
        }
    }

    private SystemPrivilege checkNodeCreate(@NonNull final AccountID payerId) {
        return hasNodeCreatePrivilege(payerId) ? AUTHORIZED : UNAUTHORIZED;
    }

    private SystemPrivilege checkEntityDelete(final long entityNum) {
        return isSystemEntity(entityNum) ? IMPERMISSIBLE : UNNECESSARY;
    }

    private SystemPrivilege checkCryptoDelete(final long entityNum) {
        return (isSystemEntity(entityNum) && !isSystemFile(entityNum)) ? IMPERMISSIBLE : UNNECESSARY;
    }

    private boolean isSystemFile(final long entityNum) {
        return FIRST_SYSTEM_FILE_ENTITY <= entityNum && entityNum < FIRST_POST_SYSTEM_FILE_ENTITY;
    }

    /**
     * Returns true iff the given entity number refers to an existing system entity.
     * @param entityNum the entity number to check
     * @return {@code true} if the entity number is a system entity, otherwise {@code false}
     */
    private boolean isSystemEntity(final long entityNum) {
        return 1 <= entityNum && entityNum <= numReservedSystemEntities;
    }

    /**
     * Given account id, returns the effective number for privileges checks.
     * @param accountID the {@link AccountID} to check
     * @return the effective number for privileges checks
     */
    private long effectiveNumber(@NonNull final AccountID accountID) {
        if (accountID.hasAlias()) {
            final var rawAlias = accountID.aliasOrThrow().toByteArray();
            if (isLongZeroAddress(rawAlias)) {
                return numberOfLongZero(rawAlias);
            } else {
                // Not a system entity, so number is irrelevant for privileges checks
                return 0L;
            }
        } else {
            return accountID.accountNumOrElse(0L);
        }
    }

    private long effectiveNumber(@NonNull final ContractID contractID) {
        if (contractID.hasEvmAddress()) {
            final var rawAlias = contractID.evmAddressOrThrow().toByteArray();
            if (isLongZeroAddress(rawAlias)) {
                return numberOfLongZero(rawAlias);
            } else {
                // Not a system entity, so number is irrelevant for privileges checks
                return 0L;
            }
        } else {
            return contractID.contractNumOrElse(0L);
        }
    }
}
