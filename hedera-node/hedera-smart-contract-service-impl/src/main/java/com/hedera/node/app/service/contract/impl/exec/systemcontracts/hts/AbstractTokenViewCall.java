// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Abstract class to support token view calls
 */
public abstract class AbstractTokenViewCall extends AbstractCall {
    protected final Token token;

    protected AbstractTokenViewCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token) {
        super(gasCalculator, enhancement, true);
        this.token = token;
    }

    @Override
    public @NonNull PricedResult execute() {
        if (token == null) {
            return failedViewResult(INVALID_TOKEN_ID, gasCalculator.viewGasRequirement());
        } else {
            return resultOfViewingToken(token);
        }
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    protected PricedResult failedViewResult(ResponseCodeEnum responseCode, long gasRequirement) {
        return gasOnly(viewCallResultWith(responseCode, gasRequirement), responseCode, true);
    }

    /**
     * Returns the result of viewing the given {@code token}.
     *
     * @param token the token to view
     * @return the result of viewing the given {@code token}
     */
    @NonNull
    protected abstract PricedResult resultOfViewingToken(@NonNull Token token);

    /**
     * Returns the result of viewing the given {@code token} given the {@code status}.
     * Currently, the only usage for this method is to return an INVALID_TOKEN_ID status
     * if the token is null.
     * @param status - ResponseCodeEnum status
     * @return the results to return to the caller
     */
    @NonNull
    protected abstract FullResult viewCallResultWith(@NonNull ResponseCodeEnum status, long gasRequirement);
}
