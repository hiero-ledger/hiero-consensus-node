// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.calculator;

import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

public class ContractGetByteCodeFeeCalculator implements QueryFeeCalculator {
    @Override
    public void accumulateNodePayment(
            @NonNull Query query,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final var serviceDef = requireNonNull(lookupServiceFee(feeSchedule, HederaFunctionality.CONTRACT_GET_BYTECODE));
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        if (simpleFeeContext.queryContext() != null) {
            final var contractStore = simpleFeeContext.queryContext().createStore(ContractStateStore.class);
            final var op = query.contractGetBytecodeOrThrow();
            final var bytecode = contractStore.getBytecode(op.contractIDOrThrow());
            if (bytecode != null) {
                addExtraFee(
                        feeResult,
                        serviceDef,
                        Extra.BYTES,
                        feeSchedule,
                        bytecode.code().length());
            }
        }
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return Query.QueryOneOfType.CONTRACT_GET_BYTECODE;
    }
}
