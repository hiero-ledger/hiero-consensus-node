// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_EVICT_PEER_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.clpr.ClprEvictPeerEndpointTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BlockTransactionalUnitTranslatorTest {
    @ParameterizedTest
    @EnumSource(
            value = ResponseCodeEnum.class,
            names = {"SUCCESS", "CLPR_CHANNEL_NOT_FOUND"})
    void translatesPeerEndpointEviction(final ResponseCodeEnum status) {
        final var transactionId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().shardNum(11).realmNum(12).accountNum(2))
                .transactionValidStart(Timestamp.newBuilder().seconds(1))
                .build();
        final var body = TransactionBody.newBuilder()
                .transactionID(transactionId)
                .memo("evict peer endpoint")
                .clprEvictPeerEndpoint(ClprEvictPeerEndpointTransactionBody.DEFAULT)
                .build();
        final var transaction = Transaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();
        final var consensusTimestamp = Timestamp.newBuilder().seconds(2).build();
        final var result = TransactionResult.newBuilder()
                .status(status)
                .consensusTimestamp(consensusTimestamp)
                .transactionFeeCharged(123)
                .transferList(TransferList.DEFAULT)
                .build();
        final var parts = new BlockTransactionParts(
                new TransactionParts(transaction, body, CLPR_EVICT_PEER_ENDPOINT),
                result,
                null,
                null,
                true,
                false,
                false);
        final var translator = new BlockTransactionalUnitTranslator(11, 12);

        final var records = translator.translate(new BlockTransactionalUnit(List.of(parts), List.of()));

        assertEquals(1, records.size());
        final var translated = records.getFirst();
        assertEquals(transaction, translated.transaction());
        assertEquals(transactionId, translated.transactionRecord().transactionID());
        assertEquals(consensusTimestamp, translated.transactionRecord().consensusTimestamp());
        assertEquals(status, translated.transactionRecord().receiptOrThrow().status());
        assertEquals(123, translated.transactionRecord().transactionFee());
        assertEquals(body.memo(), translated.transactionRecord().memo());
    }
}
