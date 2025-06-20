// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.TokenTupleUtils.feesTupleFor;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees.TokenCustomFeesTranslator.TOKEN_CUSTOM_FEES;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractNonRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public class TokenCustomFeesCall extends AbstractNonRevertibleTokenViewCall {
    private final boolean isStaticCall;

    public TokenCustomFeesCall(
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final boolean isStaticCall,
            @Nullable final Token token) {
        super(systemContractGasCalculator, enhancement, token);
        this.isStaticCall = isStaticCall;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);
        return gasOnly(fullResultsFor(SUCCESS, gasCalculator.viewGasRequirement(), token), SUCCESS, true);
    }

    @Override
    protected @NonNull FullResult viewCallResultWith(
            @NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return fullResultsFor(status, gasRequirement, Token.DEFAULT);
    }

    private @NonNull FullResult fullResultsFor(
            @NonNull final ResponseCodeEnum status, final long gasRequirement, @NonNull final Token token) {
        if (isStaticCall) {
            return successResult(
                    TOKEN_CUSTOM_FEES.getOutputs().encode(feesTupleFor(SUCCESS.protoOrdinal(), token)), gasRequirement);
        }
        return successResult(
                TOKEN_CUSTOM_FEES.getOutputs().encode(feesTupleFor(status.protoOrdinal(), token)), gasRequirement);
    }
}
