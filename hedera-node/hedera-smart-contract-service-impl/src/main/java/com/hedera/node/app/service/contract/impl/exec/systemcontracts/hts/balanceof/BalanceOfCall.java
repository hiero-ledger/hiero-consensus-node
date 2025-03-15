// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.balanceof;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;

/**
 * Implements the token redirect {@code balanceOf()} call of the HTS system contract.
 */
public class BalanceOfCall extends AbstractRevertibleTokenViewCall {
    private final Address owner;

    /**
     * @param enhancement the enhancement to use
     * @param gasCalculator the gas calculator to use
     * @param token the target token of this call
     * @param owner the owner of the token
     */
    public BalanceOfCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @Nullable final Token token,
            @NonNull final Address owner) {
        super(gasCalculator, enhancement, token);
        this.owner = requireNonNull(owner);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull Token token) {
        final var ownerNum = accountNumberForEvmReference(owner, nativeOperations());
        if (ownerNum < 0) {
            return gasOnly(
                    revertResult(INVALID_ACCOUNT_ID, gasCalculator.viewGasRequirement()), INVALID_ACCOUNT_ID, true);
        }

        final var tokenNum = token.tokenIdOrThrow().tokenNum();
        final var relation = nativeOperations()
                .getTokenRelation(
                        nativeOperations().entityIdFactory().newAccountId(ownerNum),
                        nativeOperations().entityIdFactory().newTokenId(tokenNum));
        final var balance = relation == null ? 0 : relation.balance();
        final var output =
                BalanceOfTranslator.BALANCE_OF.getOutputs().encode(Tuple.singleton(BigInteger.valueOf(balance)));

        return gasOnly(successResult(output, gasCalculator.viewGasRequirement()), SUCCESS, true);
    }
}
