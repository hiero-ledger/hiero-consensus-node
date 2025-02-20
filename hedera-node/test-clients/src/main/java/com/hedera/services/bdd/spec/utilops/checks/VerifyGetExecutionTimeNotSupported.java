// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.checks;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NetworkGetExecutionTimeQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.List;

public class VerifyGetExecutionTimeNotSupported extends UtilOp {
    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        NetworkGetExecutionTimeQuery.Builder op = NetworkGetExecutionTimeQuery.newBuilder()
                .addAllTransactionIds(List.of(TransactionID.getDefaultInstance()));
        Query query = Query.newBuilder().setNetworkGetExecutionTime(op).build();
        final var response = spec.targetNetworkOrThrow()
                .send(query, HederaFunctionality.NetworkGetExecutionTime, targetNodeFor(spec));
        assertEquals(
                NOT_SUPPORTED, response.getNetworkGetExecutionTime().getHeader().getNodeTransactionPrecheckCode());
        return false;
    }
}
