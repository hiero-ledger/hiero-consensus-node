/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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
        final var relation = nativeOperations().getTokenRelation(ownerNum, tokenNum);
        final var balance = relation == null ? 0 : relation.balance();
        final var output =
                BalanceOfTranslator.BALANCE_OF.getOutputs().encode(Tuple.singleton(BigInteger.valueOf(balance)));

        return gasOnly(successResult(output, gasCalculator.viewGasRequirement()), SUCCESS, true);
    }
}
