// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.decimals;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.HederaExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implements the token redirect {@code decimals()} call of the HTS system contract.
 */
public class DecimalsCall extends AbstractRevertibleTokenViewCall {
    private static final int MAX_REPORTABLE_DECIMALS = 0xFF;

    /**
     * @param enhancement the enhancement to use
     * @param gasCalculator the gas calculator to use
     * @param token the token against the call is executed
     */
    public DecimalsCall(
            @NonNull HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @Nullable final Token token) {
        super(gasCalculator, enhancement, token);
    }

    @Override
    public @NonNull PricedResult execute() {
        if (token != null && token.tokenType() != TokenType.FUNGIBLE_COMMON) {
            // For backwards compatibility, we need to halt here per issue #8746.
            return gasOnly(
                    haltResult(
                            HederaExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT,
                            gasCalculator.viewGasRequirement()),
                    INVALID_TOKEN_ID,
                    false);
        }
        return super.execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        final var decimals = Math.min(MAX_REPORTABLE_DECIMALS, token.decimals());
        return gasOnly(
                successResult(
                        DecimalsTranslator.DECIMALS.getOutputs().encode(Tuple.singleton(decimals)),
                        gasCalculator.viewGasRequirement()),
                SUCCESS,
                true);
    }
}
