// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.workflows.handle.record;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.SingleTransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Predicate;
import org.assertj.core.api.Condition;

/**
 * A collection of {@link Condition} objects for asserting the state of {@link PreCheckException} objects.
 */
public class SingleTransactionRecordConditions {

    private SingleTransactionRecordConditions() {}

    /**
     * Returns a {@link Condition} that asserts that the contained in the {@link SingleTransactionRecord}
     * has the given {@link ResponseCodeEnum}.
     *
     * @param status the expected {@link ResponseCodeEnum}
     * @return the {@link Condition}
     */
    @NonNull
    public static Condition<SingleTransactionRecord> status(@NonNull final ResponseCodeEnum status) {
        return new Condition<>(getStatusCheck(status), "status " + status);
    }

    @NonNull
    private static Predicate<SingleTransactionRecord> getStatusCheck(@NonNull final ResponseCodeEnum status) {
        return transactionRecord ->
                transactionRecord.transactionRecord().receipt().status() == status;
    }
}
