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
