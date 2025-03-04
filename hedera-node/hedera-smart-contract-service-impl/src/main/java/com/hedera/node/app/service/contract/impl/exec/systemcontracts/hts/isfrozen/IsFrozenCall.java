// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isfrozen;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.defaultfreezestatus.DefaultFreezeStatusTranslator.DEFAULT_FREEZE_STATUS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractNonRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public class IsFrozenCall extends AbstractNonRevertibleTokenViewCall {
    private final Address account;
    private final boolean isStaticCall;

    public IsFrozenCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final boolean isStaticCall,
            @Nullable final Token token,
            @NonNull final Address account) {
        super(gasCalculator, enhancement, token);
        this.account = requireNonNull(account);
        this.isStaticCall = isStaticCall;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);

        final var accountNum = accountNumberForEvmReference(account, nativeOperations());
        if (accountNum < 0) {
            return gasOnly(
                    fullResultsFor(INVALID_ACCOUNT_ID, gasCalculator.viewGasRequirement(), false),
                    INVALID_ACCOUNT_ID,
                    true);
        }
        var tokenRel = nativeOperations()
                .getTokenRelation(
                        nativeOperations().entityIdFactory().newAccountId(accountNum),
                        nativeOperations()
                                .entityIdFactory()
                                .newTokenId(token.tokenIdOrThrow().tokenNum()));
        var result = tokenRel != null && tokenRel.frozen();
        return gasOnly(fullResultsFor(SUCCESS, gasCalculator.viewGasRequirement(), result), SUCCESS, true);
    }

    @Override
    protected @NonNull FullResult viewCallResultWith(
            @NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return fullResultsFor(status, gasRequirement, false);
    }

    private @NonNull FullResult fullResultsFor(
            @NonNull final ResponseCodeEnum status, final long gasRequirement, final boolean isFrozen) {
        // For backwards compatibility, we need to revert here per issue #8746.
        if (isStaticCall && status != SUCCESS) {
            return revertResult(status, gasRequirement);
        }
        return successResult(
                DEFAULT_FREEZE_STATUS.getOutputs().encode(Tuple.of(status.protoOrdinal(), isFrozen)), gasRequirement);
    }
}
