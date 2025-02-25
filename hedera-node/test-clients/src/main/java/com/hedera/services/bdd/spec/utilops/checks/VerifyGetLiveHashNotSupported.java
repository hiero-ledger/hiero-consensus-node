// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.checks;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoGetLiveHash;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.CryptoGetLiveHashQuery;
import com.hederahashgraph.api.proto.java.Query;
import org.junit.jupiter.api.Assertions;

public class VerifyGetLiveHashNotSupported extends UtilOp {
    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        var shard = spec.startupProperties().getLong("hedera.shard");
        var realm = spec.startupProperties().getLong("hedera.realm");

        CryptoGetLiveHashQuery.Builder op =
                CryptoGetLiveHashQuery.newBuilder().setAccountID(asAccount(String.format("%d.%d.2", shard, realm)));
        Query query = Query.newBuilder().setCryptoGetLiveHash(op).build();
        final var response = spec.targetNetworkOrThrow().send(query, CryptoGetLiveHash, targetNodeFor(spec));
        Assertions.assertEquals(
                NOT_SUPPORTED, response.getCryptoGetLiveHash().getHeader().getNodeTransactionPrecheckCode());
        return false;
    }
}
