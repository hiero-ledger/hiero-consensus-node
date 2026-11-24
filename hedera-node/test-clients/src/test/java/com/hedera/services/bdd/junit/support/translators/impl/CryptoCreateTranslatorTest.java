// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CryptoCreateTranslatorTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedCreationWithScheduledIdKeepsRatesOnlyForTheScheduledExecution(final boolean hasScheduleRef) {
        final var baseTranslator = new BaseTranslator(0, 0);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();
        final var rates =
                ExchangeRateSet.newBuilder().currentRate(rate).nextRate(rate).build();
        baseTranslator.updateActiveRates(StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .value(MapChangeValue.newBuilder()
                                .fileValue(File.newBuilder().contents(ExchangeRateSet.PROTOBUF.toBytes(rates)))))
                .build());
        // Synthetic children inherit the scheduled transaction ID flag.
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().scheduled(true).nonce(1))
                .cryptoCreateAccount(CryptoCreateTransactionBody.DEFAULT)
                .build();
        final var result = TransactionResult.newBuilder()
                .consensusTimestamp(Timestamp.newBuilder().seconds(2))
                .parentConsensusTimestamp(Timestamp.newBuilder().seconds(1))
                .status(THROTTLED_AT_CONSENSUS);
        if (hasScheduleRef) {
            result.scheduleRef(ScheduleID.newBuilder().scheduleNum(3));
        }
        final var parts = new BlockTransactionParts(
                new TransactionParts(Transaction.DEFAULT, body, CRYPTO_CREATE),
                result.build(),
                null,
                null,
                false,
                false,
                false);

        final var record =
                new CryptoCreateTranslator().translate(parts, baseTranslator, List.of(), null, List.of(), null, null);

        assertEquals(
                hasScheduleRef ? rates : null,
                record.transactionRecord().receiptOrThrow().exchangeRate());
    }
}
