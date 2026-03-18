// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.blocks.utils;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Generates realistic transactions for benchmarking and testing.
 * Uses CryptoTransfer with memo padding to achieve target size.
 * Follows object reuse pattern for performance.
 */
public final class TransactionGeneratorUtil {

    private static final int DEFAULT_TX_SIZE = 200; // Bytes for typical transfer

    // Realistic test accounts
    private static final AccountID PAYER =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID NODE = AccountID.newBuilder().accountNum(3).build();
    private static final AccountID SENDER =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID RECEIVER =
            AccountID.newBuilder().accountNum(1002).build();

    // Cached reusable transaction for default size
    private static volatile Bytes cachedTransaction_200B = null;

    private TransactionGeneratorUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Generates a single transaction with default size.
     *
     * @return Serialized transaction as Bytes
     */
    public static Bytes generateTransaction() {
        return generateTransaction(DEFAULT_TX_SIZE);
    }

    /**
     * Generates a single transaction of approximately the specified size.
     *
     * @param targetSizeBytes Target size in bytes
     * @return Serialized transaction as Bytes
     */
    public static Bytes generateTransaction(int targetSizeBytes) {
        // Use cached transaction for default size
        if (targetSizeBytes == DEFAULT_TX_SIZE) {
            if (cachedTransaction_200B == null) {
                synchronized (TransactionGeneratorUtil.class) {
                    if (cachedTransaction_200B == null) {
                        cachedTransaction_200B = createTransaction(targetSizeBytes);
                    }
                }
            }
            return cachedTransaction_200B;
        }

        return createTransaction(targetSizeBytes);
    }

    /**
     * Generates a list of transactions with default size.
     * Reuses same transaction instance for performance.
     *
     * @param count Number of transactions to generate
     * @return List of transactions
     */
    public static List<Bytes> generateTransactions(int count) {
        return generateTransactions(count, DEFAULT_TX_SIZE);
    }

    /**
     * Generates a list of transactions, all the same size.
     * Reuses same transaction instance for performance.
     *
     * @param count Number of transactions to generate
     * @param sizeBytes Size per transaction in bytes
     * @return List of transactions
     */
    public static List<Bytes> generateTransactions(int count, int sizeBytes) {
        List<Bytes> transactions = new ArrayList<>(count);
        Bytes template = generateTransaction(sizeBytes);

        // Reuse same reference for performance gain
        for (int i = 0; i < count; i++) {
            transactions.add(template);
        }

        return transactions;
    }

    /**
     * Spams transactions at a specified rate with default size.
     * Executes consumer for each transaction, respecting the target TPS.
     *
     * @param consumer Handler for each generated transaction
     * @param totalTransactions Total number of transactions to generate
     * @param transactionsPerSecond Target rate (TPS)
     */
    public static void spamTransactions(Consumer<Bytes> consumer, int totalTransactions, int transactionsPerSecond) {
        spamTransactions(consumer, totalTransactions, transactionsPerSecond, DEFAULT_TX_SIZE);
    }

    /**
     * Spams transactions at a specified rate and size.
     * Executes consumer for each transaction, respecting the target TPS.
     *
     * @param consumer Handler for each generated transaction
     * @param totalTransactions Total number of transactions to generate
     * @param transactionsPerSecond Target rate (TPS)
     * @param transactionSizeBytes Size per transaction
     */
    public static void spamTransactions(
            Consumer<Bytes> consumer, int totalTransactions, int transactionsPerSecond, int transactionSizeBytes) {

        // Pre-generate transaction once (reuse pattern)
        Bytes transaction = generateTransaction(transactionSizeBytes);

        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / transactionsPerSecond;
        long startTime = System.nanoTime();

        for (int i = 0; i < totalTransactions; i++) {
            long targetTime = startTime + (i * intervalNanos);

            // Send transaction
            consumer.accept(transaction);

            // Sleep until next transaction is due
            long now = System.nanoTime();
            long sleepNanos = targetTime - now;

            if (sleepNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Creates a CryptoTransfer transaction with memo padding to achieve target size.
     */
    private static Bytes createTransaction(int targetSizeBytes) {
        // Simple HBAR transfer (1000 tinybars from sender to receiver)
        var cryptoTransfer = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(SENDER)
                                        .amount(-1000)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(RECEIVER)
                                        .amount(1000)
                                        .build())
                        .build())
                .build();

        // Build base transaction body without memo
        var baseBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(PAYER)
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds(System.currentTimeMillis() / 1000)
                                .build())
                        .build())
                .nodeAccountID(NODE)
                .transactionFee(100_000)
                .transactionValidDuration(Duration.newBuilder().seconds(120).build())
                .cryptoTransfer(cryptoTransfer)
                .build();

        long baseSize = TransactionBody.PROTOBUF.measureRecord(baseBody);

        // Add memo padding to reach target size
        int memoSize = Math.max(0, (int) (targetSizeBytes - baseSize));
        String memo = generatePaddingMemo(memoSize);

        var finalBody = baseBody.copyBuilder().memo(memo).build();

        // Serialize transaction body
        return TransactionBody.PROTOBUF.toBytes(finalBody);
    }

    /**
     * Generates a memo string of the specified size for padding.
     */
    private static String generatePaddingMemo(int targetSize) {
        if (targetSize <= 0) {
            return "";
        }

        // Use repeating pattern for efficiency
        StringBuilder memo = new StringBuilder(targetSize);
        String pattern = "BENCH_PAD_"; // 10 chars

        while (memo.length() < targetSize) {
            memo.append(pattern);
        }

        return memo.substring(0, Math.min(targetSize, memo.length()));
    }

    /**
     * Returns the actual size of a transaction in bytes.
     *
     * @param transaction Transaction to measure
     * @return Size in bytes
     */
    public static long getTransactionSize(Bytes transaction) {
        return transaction.length();
    }
}
