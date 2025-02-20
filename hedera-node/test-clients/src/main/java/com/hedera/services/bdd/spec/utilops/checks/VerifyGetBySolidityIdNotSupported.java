// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.checks;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.GetBySolidityID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.GetBySolidityIDQuery;
import com.hederahashgraph.api.proto.java.Query;
import org.junit.jupiter.api.Assertions;

public class VerifyGetBySolidityIdNotSupported extends UtilOp {
    @Override
    protected boolean submitOp(HapiSpec spec) {
        GetBySolidityIDQuery.Builder op = GetBySolidityIDQuery.newBuilder();
        Query query = Query.newBuilder().setGetBySolidityID(op).build();
        final var response = spec.targetNetworkOrThrow().send(query, GetBySolidityID, targetNodeFor(spec));
        Assertions.assertEquals(
                NOT_SUPPORTED, response.getGetBySolidityID().getHeader().getNodeTransactionPrecheckCode());
        return false;
    }
}
