/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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
