// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.defaultkycstatus;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.defaultkycstatus.DefaultKycStatusTranslator.DEFAULT_KYC_STATUS;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractNonRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implements the token redirect {@code getTokenDefaultKycStatus()} call of the HTS system contract.
 */
public class DefaultKycStatusCall extends AbstractNonRevertibleTokenViewCall {
    private final boolean isStaticCall;

    /**
     * @param gasCalculator the gas calculator to use
     * @param enhancement the enhancement to use
     * @param isStaticCall whether this is a static call
     * @param token the token against the call is executed
     */
    public DefaultKycStatusCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final boolean isStaticCall,
            @Nullable final Token token) {
        super(gasCalculator, enhancement, token);
        this.isStaticCall = isStaticCall;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@Nullable final Token token) {
        requireNonNull(token);
        final boolean kycStatus = !token.hasKycKey() || token.accountsKycGrantedByDefault();
        return gasOnly(fullResultsFor(SUCCESS, gasCalculator.viewGasRequirement(), kycStatus), SUCCESS, true);
    }

    @Override
    protected @NonNull FullResult viewCallResultWith(
            @NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return fullResultsFor(status, gasRequirement, false);
    }

    private @NonNull FullResult fullResultsFor(
            @NonNull final ResponseCodeEnum status, final long gasRequirement, final boolean kycStatus) {
        // For backwards compatibility, we need to revert here per issue #8746.
        if (isStaticCall && status != SUCCESS) {
            return revertResult(status, 0);
        }
        return successResult(
                DEFAULT_KYC_STATUS.getOutputs().encode(Tuple.of(status.protoOrdinal(), kycStatus)), gasRequirement);
    }
}
