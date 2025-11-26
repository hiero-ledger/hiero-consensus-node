// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public class BlockItemGeneratorUtil {
    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);
    /**
     * Generate realistic block items for benchmarking.
     * Uses patterns similar to real transactions/state changes for realistic compression testing.
     */
    public static List<byte[]> generateBlockItems(int itemsPerBlock, int avgItemSizeBytes) {
        List<byte[]> serializedItems = new ArrayList<>(itemsPerBlock);

        for (int i = 0; i < itemsPerBlock; i++) {
            BlockItem item = generateRealisticBlockItem(avgItemSizeBytes, i);
            byte[] serialized = BlockItem.PROTOBUF.toBytes(item).toByteArray();
            serializedItems.add(serialized);
        }
        return serializedItems;
    }

    /**
     * Generate a realistic BlockItem with approximately the specified size.
     * Uses patterns that mimic real blockchain data for accurate compression testing.
     */
    private static BlockItem generateRealisticBlockItem(int targetSize, int index) {
        // Randomly choose item type
        int type = RANDOM.nextInt(4);

        return switch (type) {
            case 0 -> generateTransactionItem(targetSize, index);
            case 1 -> generateStateChangeItem(targetSize, index);
            case 2 -> generateEventHeaderItem(index); // No targetSize needed - naturally small
            default -> generateRoundHeaderItem(index); // No targetSize needed - naturally small
        };
    }

    private static BlockItem generateTransactionItem(int targetSize, int index) {
        // Generate a REAL protobuf-serialized Hedera transaction

        // 1. Create TransactionID with realistic patterns
        TransactionID transactionID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(1000 + (index % 1000)) // Repeating account IDs
                        .build())
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(1700000000L + (index * 10)) // Sequential timestamps
                        .nanos(0)
                        .build())
                .build();

        // 2. Create TransactionBody (most common: crypto transfer)
        // Pad memo to reach target size
        String memo = "tx_" + index;
        int estimatedBaseSize = 200; // Rough estimate of other fields
        int neededMemoSize = Math.max(0, targetSize - estimatedBaseSize);
        if (neededMemoSize > 0) {
            memo = memo + "_".repeat(Math.min(neededMemoSize, 1000)); // Max 1000 chars
        }

        TransactionBody transactionBody = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .nodeAccountID(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(3 + (index % 10)) // Node accounts from small set
                        .build())
                .transactionFee(100000 + (index % 1000)) // Similar fees
                .transactionValidDuration(Duration.newBuilder().seconds(120).build())
                .memo(memo)
                .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT) // Empty transfer for benchmarking
                .build();

        // 3. Serialize TransactionBody to bytes
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(transactionBody);

        // 4. Create SignedTransaction with signature map
        SignedTransaction signedTransaction = SignedTransaction.newBuilder()
                .bodyBytes(bodyBytes)
                .sigMap(SignatureMap.DEFAULT) // Empty sig map for benchmarking
                .build();

        // 5. Serialize SignedTransaction to bytes
        Bytes signedTxBytes = SignedTransaction.PROTOBUF.toBytes(signedTransaction);

        // 6. Wrap in BlockItem
        return BlockItem.newBuilder().signedTransaction(signedTxBytes).build();
    }

    private static BlockItem generateStateChangeItem(int targetSize, int index) {
        int numChanges = Math.max(1, targetSize / 300);
        StateChange[] changes = new StateChange[numChanges];

        for (int i = 0; i < numChanges; i++) {
            // State ID for ACCOUNTS virtual map (common state change)
            int stateId = 100 + ((index + i) % 50); // Repeating pattern

            // Create a realistic account state value with repeating patterns
            Account accountValue = Account.newBuilder()
                    .accountId(AccountID.newBuilder()
                            .shardNum(0)
                            .realmNum(0)
                            .accountNum(1000 + ((index + i) % 5000)) // Repeating account numbers
                            .build())
                    .tinybarBalance(1000000L + ((index + i) * 100)) // Sequential balances
                    .memo("account_" + ((index + i) % 100)) // Repeating memos
                    // Most other fields default to zero
                    .build();

            // Create map update change
            MapUpdateChange mapUpdate = MapUpdateChange.newBuilder()
                    .key(MapChangeKey.newBuilder()
                            .accountIdKey(AccountID.newBuilder()
                                    .shardNum(0)
                                    .realmNum(0)
                                    .accountNum(1000 + ((index + i) % 5000))
                                    .build())
                            .build())
                    .value(MapChangeValue.newBuilder()
                            .accountValue(accountValue)
                            .build())
                    .build();

            changes[i] = StateChange.newBuilder()
                    .stateId(stateId)
                    .mapUpdate(mapUpdate)
                    .build();
        }

        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .consensusTimestamp(Timestamp.newBuilder()
                                .seconds(1700000000L + (index * 10)) // Sequential timestamps
                                .nanos(0)
                                .build())
                        .stateChanges(changes)
                        .build())
                .build();
    }

    private static BlockItem generateEventHeaderItem(int index) {
        EventCore eventCore = EventCore.newBuilder()
                .birthRound(1_000_000 + (index / 100)) // Sequential rounds
                .creatorNodeId(index % 10) // Rotating node IDs
                .timeCreated(Timestamp.newBuilder()
                        .seconds(1700000000L + (index * 10))
                        .nanos(0)
                        .build())
                .build();

        return BlockItem.newBuilder()
                .eventHeader(EventHeader.newBuilder().eventCore(eventCore).build())
                .build();
    }

    private static BlockItem generateRoundHeaderItem(int index) {
        return BlockItem.newBuilder()
                .roundHeader(RoundHeader.newBuilder()
                        .roundNumber(1_000_000 + (index / 1000)) // Sequential round numbers
                        .build())
                .build();
    }
}
