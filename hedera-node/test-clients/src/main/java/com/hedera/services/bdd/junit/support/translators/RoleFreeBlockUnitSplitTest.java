// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.services.bdd.junit.TestTags;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// TODO: run with './gradlew :test-clients:testEmbedded --tests "*RoleFreeBlockUnitSplitTest*" --no-configuration-cache --stacktrace'
/**
 * Tests for {@link RoleFreeBlockUnitSplit}.
 */
@Tag(TestTags.INTEGRATION)
class RoleFreeBlockUnitSplitTest {

    /**
     * Regression test: verifies no NPE when batch inner results appear with
     * interleaved
     * SIGNED_TRANSACTION items. Previously, calling functionality() on parts with
     * null
     * transactionParts caused NPE.
     */
    @Test
    void shouldNotThrowNpeWhenBatchInnerItemsContainSignedTransaction() {
        final var split = new RoleFreeBlockUnitSplit();
        final List<BlockItem> items = new ArrayList<>();

        // Index 0: Parent SIGNED_TRANSACTION
        items.add(createSignedTransactionItem(1, 0));

        // Index 1: First result (inner 1) - has preceding tx, so NOT a batch inner
        items.add(createTransactionResultItem());

        // Index 2: Second result (inner 2) - prevResultIndex (1) > prevTxIndex (0), so
        // IS a batch inner
        items.add(createTransactionResultItem());

        // Index 3: Third result (inner 3) - prevResultIndex (2) > prevTxIndex (0), so
        // IS a batch inner
        items.add(createTransactionResultItem());

        // Index 4: A SIGNED_TRANSACTION that appears after the batch inner results
        items.add(createSignedTransactionItem(2, 0));

        // Index 5: Result for the tx at index 4
        items.add(createTransactionResultItem());

        // Index 6: Another result - prevResultIndex (5) > prevTxIndex (4), so IS a
        // batch inner
        items.add(createTransactionResultItem());

        // Index 7: STATE_CHANGES to mark end of a transaction unit
        items.add(createStateChangesItem());

        assertDoesNotThrow(() -> split.split(items));
    }

    @Test
    void shouldNotThrowNpeWhenIndexPointsToResultButItemIsSignedTransaction() {
        final var split = new RoleFreeBlockUnitSplit();
        final List<BlockItem> items = new ArrayList<>();

        // Index 0: SIGNED_TX (tx 1)
        items.add(createSignedTransactionItem(1, 0));

        // Index 1: RESULT for tx 1
        items.add(createTransactionResultItem());

        // Index 2: RESULT - this is "batch inner" because prevResultIndex(1) >
        // prevTxIndex(0)
        items.add(createTransactionResultItem());

        // Index 3: STATE_CHANGES
        items.add(createStateChangesItem());

        // Index 4: SIGNED_TX (tx 2)
        items.add(createSignedTransactionItem(2, 0));

        // Index 5: RESULT for tx 2
        items.add(createTransactionResultItem());

        // Index 6: STATE_CHANGES
        items.add(createStateChangesItem());

        assertDoesNotThrow(() -> split.split(items));
    }

    /**
     * Regression test: verifies no NPE with multiple consecutive results (batch
     * inner scenario).
     */
    @Test
    void shouldHandleMultipleConsecutiveResults() {
        final var split = new RoleFreeBlockUnitSplit();
        final List<BlockItem> items = new ArrayList<>();

        // Index 0: SIGNED_TX (batch parent)
        items.add(createSignedTransactionItem(1, 0));

        // Index 1-3: Three consecutive results (inner transactions of the batch)
        items.add(createTransactionResultItem()); // index 1: NOT batch inner
        items.add(createTransactionResultItem()); // index 2: IS batch inner
        items.add(createTransactionResultItem()); // index 3: IS batch inner

        // Index 4: STATE_CHANGES for the whole batch
        items.add(createStateChangesItem());

        // Index 5: Next SIGNED_TX
        items.add(createSignedTransactionItem(2, 0));

        // Index 6: Result
        items.add(createTransactionResultItem());

        // Index 7: STATE_CHANGES
        items.add(createStateChangesItem());

        assertDoesNotThrow(() -> split.split(items));
    }

    private BlockItem createSignedTransactionItem(long accountNum, int nonce) {
        final var txId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(accountNum).build())
                .transactionValidStart(Timestamp.newBuilder().seconds(1000).nanos(nonce).build())
                .nonce(nonce)
                .build();

        final var body = TransactionBody.newBuilder()
                .transactionID(txId)
                .cryptoTransfer(com.hedera.hapi.node.token.CryptoTransferTransactionBody.DEFAULT)
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .sigMap(SignatureMap.DEFAULT)
                .build();

        return BlockItem.newBuilder()
                .signedTransaction(SignedTransaction.PROTOBUF.toBytes(signedTx))
                .build();
    }

    private BlockItem createTransactionResultItem() {
        final var result = TransactionResult.newBuilder()
                .status(ResponseCodeEnum.SUCCESS)
                .build();
        return BlockItem.newBuilder().transactionResult(result).build();
    }

    private BlockItem createStateChangesItem() {
        final var stateChanges = StateChanges.newBuilder()
                .stateChanges(List.of(StateChange.newBuilder()
                        .mapUpdate(MapUpdateChange.DEFAULT)
                        .build()))
                .build();
        return BlockItem.newBuilder().stateChanges(stateChanges).build();
    }
}
