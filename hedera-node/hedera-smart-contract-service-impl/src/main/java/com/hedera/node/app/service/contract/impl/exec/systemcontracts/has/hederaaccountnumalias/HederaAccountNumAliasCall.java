// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasTranslator.HEDERA_ACCOUNT_NUM_ALIAS;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implements the {@code hederaAccountNumAlias(address)} call of the HAS system contract.
 * This call will return the long zero Hedera account number of the given alias if it exists.
 */
public class HederaAccountNumAliasCall extends AbstractCall {
    private final Address address;

    public HederaAccountNumAliasCall(@NonNull final HasCallAttempt attempt, @NonNull final Address address) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), true);
        this.address = requireNonNull(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull PricedResult execute() {

        final long accountNum = accountNumberForEvmReference(address, nativeOperations());
        if (accountNum < 0) {
            // Invalid: not an alias for any account
            return gasOnly(fullResultsFor(INVALID_SOLIDITY_ADDRESS, ZERO_ADDRESS), INVALID_SOLIDITY_ADDRESS, true);
        }
        final var account = enhancement
                .nativeOperations()
                .getAccount(nativeOperations().entityIdFactory().newAccountId(accountNum));
        if (account == null) {
            return gasOnly(fullResultsFor(INVALID_SOLIDITY_ADDRESS, ZERO_ADDRESS), INVALID_SOLIDITY_ADDRESS, true);
        }
        requireNonNull(account.accountId());
        if (!account.accountIdOrElse(AccountID.DEFAULT).hasAccountNum()) {
            return gasOnly(fullResultsFor(INVALID_SOLIDITY_ADDRESS, ZERO_ADDRESS), INVALID_SOLIDITY_ADDRESS, true);
        }
        final var accountAsAddress = asHeadlongAddress(
                asEvmAddress(account.accountIdOrElse(AccountID.DEFAULT).accountNumOrElse(0L)));
        return gasOnly(fullResultsFor(SUCCESS, accountAsAddress), SUCCESS, true);
    }

    private @NonNull FullResult fullResultsFor(final ResponseCodeEnum responseCode, final Address address) {
        return successResult(
                HEDERA_ACCOUNT_NUM_ALIAS.getOutputs().encode(Tuple.of((long) responseCode.protoOrdinal(), address)),
                gasCalculator.viewGasRequirement());
    }
}
