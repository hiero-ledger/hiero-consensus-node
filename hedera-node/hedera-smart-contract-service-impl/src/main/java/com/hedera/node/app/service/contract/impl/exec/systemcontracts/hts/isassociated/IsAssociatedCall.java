// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedTranslator.IS_ASSOCIATED;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import edu.umd.cs.findbugs.annotations.NonNull;

public class IsAssociatedCall extends AbstractRevertibleTokenViewCall {
    private final AccountID sender;

    public IsAssociatedCall(
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final Enhancement enhancement,
            @NonNull final AccountID sender,
            @NonNull final Token token) {
        super(systemContractGasCalculator, enhancement, token);
        this.sender = sender;
    }

    @NonNull
    @Override
    protected PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);
        var tokenRel = nativeOperations()
                .getTokenRelation(
                        nativeOperations().entityIdFactory().newAccountId(sender.accountNum()),
                        nativeOperations()
                                .entityIdFactory()
                                .newTokenId(token.tokenIdOrThrow().tokenNum()));
        var result = tokenRel != null;
        return gasOnly(
                successResult(
                        IS_ASSOCIATED.getOutputs().encode(Tuple.singleton(result)), gasCalculator.viewGasRequirement()),
                SUCCESS,
                true);
    }
}
