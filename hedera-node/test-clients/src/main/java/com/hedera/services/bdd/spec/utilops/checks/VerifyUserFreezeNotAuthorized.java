// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.checks;

import static com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget.NA;
import static com.hedera.services.bdd.spec.HapiPropertySource.realm;
import static com.hedera.services.bdd.spec.HapiPropertySource.shard;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.getUniqueTimestampPlusSecs;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.Freeze;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;

public class VerifyUserFreezeNotAuthorized extends UtilOp {
    private static final long USER_PAYER_NUM = 1001L;

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        final var validStart = getUniqueTimestampPlusSecs(spec.setup().txnStartOffsetSecs());
        final var txnId = TransactionID.newBuilder()
                .setTransactionValidStart(validStart)
                .setAccountID(AccountID.newBuilder()
                        .setShardNum(shard)
                        .setRealmNum(realm)
                        .setAccountNum(USER_PAYER_NUM)
                        .build())
                .build();
        final var body = TransactionBody.newBuilder()
                .setTransactionID(txnId)
                .setNodeAccountID(AccountID.newBuilder()
                        .setShardNum(shard)
                        .setRealmNum(realm)
                        .setAccountNum(3)
                        .build())
                .setTransactionValidDuration(
                        Duration.newBuilder().setSeconds(120).build())
                .setFreeze(FreezeTransactionBody.getDefaultInstance())
                .build();
        final var txn = Transaction.newBuilder()
                .setSignedTransactionBytes(SignedTransaction.newBuilder()
                        .setBodyBytes(body.toByteString())
                        .setSigMap(SignatureMap.getDefaultInstance())
                        .build()
                        .toByteString())
                .build();
        final var response = spec.targetNetworkOrThrow().submit(txn, Freeze, NA, targetNodeFor(spec));
        assertEquals(NOT_SUPPORTED, response.getNodeTransactionPrecheckCode());
        return false;
    }
}
