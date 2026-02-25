// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.NON_CANONICAL_REFERENCE_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasTranslator.IS_VALID_ALIAS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implements the {@code isValidAlias(address)} call of the HAS system contract.
 * This call will return true if the address exists and false otherwise.
 */
public class IsValidAliasCall extends AbstractCall {

    private final Address address;

    public IsValidAliasCall(@NonNull final HasCallAttempt attempt, @NonNull final Address address) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), true);
        this.address = requireNonNull(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull PricedResult execute() {

        // It is OK if given address is long zero whether it does or doesn't have an EVM alias

        final var aliasKind = getAliasKindForAddressWithAccount(address, nativeOperations());
        final boolean isValidAlias = aliasKind != AliasKind.EVM_ADDRESS_WITH_NO_ASSOCIATED_ACCOUNT;
        return gasOnly(fullResultsFor(isValidAlias), SUCCESS, true);
    }

    // FUTURE: Consider moving `AliasKind` and `getAliasKindForAddressWithAccount` to
    // `com.hedera.node.app.service.token.AliasUtils`

    public enum AliasKind {
        ACCOUNT_NUM_ALIAS_OF_ACCOUNT_WITH_EVM_ALIAS,
        ACCOUNT_NUM_ALIAS_OF_ACCOUNT_WITHOUT_EVM_ALIAS,
        EVM_ALIAS_OF_ACCOUNT,
        EVM_ADDRESS_WITH_NO_ASSOCIATED_ACCOUNT
    }

    /**
     * Determine what kind of alias the {@link Address} is, w.r.t. current accounts known to the system
     * @param address An EVM address
     * @param nativeOperations Provides access to state (for Hedera accounts)
     * @return an {@link AliasKind} describing the kind of alias this address is
     */
    public static @NonNull AliasKind getAliasKindForAddressWithAccount(
            @NonNull final Address address, @NonNull final HederaNativeOperations nativeOperations) {
        final boolean isAccountNumAlias /*aka long-zero*/ = isLongZero(address);
        final long accountNum = accountNumberForEvmReference(address, nativeOperations);

        if (accountNum == MISSING_ENTITY_NUMBER) {
            // No account for this alias (accountNumberForEvmReference)
            // Key alias given as protobuf but not EC key
            // Not an EVM address (wrong size)
            return AliasKind.EVM_ADDRESS_WITH_NO_ASSOCIATED_ACCOUNT;
        } else if (accountNum == NON_CANONICAL_REFERENCE_NUMBER) {
            // Account doesn't have an EVM address alias
            return AliasKind.ACCOUNT_NUM_ALIAS_OF_ACCOUNT_WITHOUT_EVM_ALIAS;
        } else {
            // valid account with EVM address alias (possibly hollow?)
            return isAccountNumAlias
                    ? AliasKind.ACCOUNT_NUM_ALIAS_OF_ACCOUNT_WITH_EVM_ALIAS
                    : AliasKind.EVM_ALIAS_OF_ACCOUNT;
        }
    }

    private @NonNull FullResult fullResultsFor(final boolean result) {
        return successResult(
                IS_VALID_ALIAS.getOutputs().encode(Tuple.singleton(result)), gasCalculator.viewGasRequirement());
    }
}
