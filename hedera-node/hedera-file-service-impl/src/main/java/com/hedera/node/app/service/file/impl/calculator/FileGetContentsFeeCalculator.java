// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class FileGetContentsFeeCalculator implements QueryFeeCalculator {
    @Override
    public void accumulateNodePayment(
            @NonNull Query query,
            @Nullable QueryContext queryContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule,
            ServiceFeeCalculator.EstimationMode mode) {
        final var fileStore = queryContext.createStore(ReadableFileStore.class);
        final var op = query.fileGetContentsOrThrow();

        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.FILE_GET_CONTENTS);
        final var fileContents = fileStore.getFileLeaf(op.fileIDOrThrow()).contents();
        feeResult.addServiceFee(1, serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.BYTES, feeSchedule, fileContents.length());
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return Query.QueryOneOfType.FILE_GET_CONTENTS;
    }
}
