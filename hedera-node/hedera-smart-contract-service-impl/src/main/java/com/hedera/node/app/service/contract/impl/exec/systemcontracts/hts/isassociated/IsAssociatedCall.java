/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
                .getTokenRelation(sender.accountNum(), token.tokenIdOrThrow().tokenNum());
        var result = tokenRel != null;
        return gasOnly(
                successResult(
                        IS_ASSOCIATED.getOutputs().encode(Tuple.singleton(result)), gasCalculator.viewGasRequirement()),
                SUCCESS,
                true);
    }
}
