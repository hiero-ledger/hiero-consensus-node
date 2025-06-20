// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implementation support for view calls that require an extant token.
 * ERC view function calls are generally revertible.
 */
public abstract class AbstractRevertibleTokenViewCall extends AbstractTokenViewCall {
    protected AbstractRevertibleTokenViewCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token) {
        super(gasCalculator, enhancement, token);
    }

    @NonNull
    @Override
    protected FullResult viewCallResultWith(@NonNull ResponseCodeEnum status, long gasRequirement) {
        return revertResult(status, gasRequirement);
    }
}
