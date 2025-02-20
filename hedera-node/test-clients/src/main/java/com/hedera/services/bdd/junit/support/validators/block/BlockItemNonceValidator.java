// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that ensures nonces are properly incremented for related transactions.
 * For transactions with the same accountID and validStart, nonces should increment by 1.
 */
public class BlockItemNonceValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(BlockItemNonceValidator.class);

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return new BlockItemNonceValidator();
        }
    };

    private TransactionID getTransactionIdFromBlockItem(BlockItem item) throws ParseException {
        if (item.hasEventTransaction()) {
            Bytes txnBytes = Objects.requireNonNull(item.eventTransaction()).applicationTransactionOrThrow();
            Transaction txn = Transaction.PROTOBUF.parse(txnBytes);
            // Skip if no transaction body, but still fail on parse errors
            if (!txn.hasBody()) {
                return null;
            }
            TransactionBody body = txn.bodyOrThrow();
            return body.hasTransactionID() ? body.transactionIDOrThrow() : null;
        }
        return null;
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Beginning validation of BlockItem nonces");

        // Track highest nonce seen for each account+validStart combination
        Map<String, Integer> highestNonces = new HashMap<>();

        for (Block block : blocks) {
            for (BlockItem item : block.items()) {
                TransactionID txnId = null;
                try {
                    txnId = getTransactionIdFromBlockItem(item);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }

                if (txnId != null && txnId.hasAccountID() && txnId.hasTransactionValidStart()) {
                    // Create key from accountID and validStart
                    String key = txnId.accountID().toString() + ":"
                            + txnId.transactionValidStart().toString();
                    int nonce = txnId.nonce();

                    // If we've seen this account+validStart before, verify nonce increment
                    if (highestNonces.containsKey(key)) {
                        int prevNonce = highestNonces.get(key);
                        if (nonce != prevNonce + 1) {
                            Assertions.fail(String.format(
                                    "Invalid nonce increment for transaction with account=%s, validStart=%s: previous=%d, current=%d",
                                    txnId.accountID(), txnId.transactionValidStart(), prevNonce, nonce));
                        }
                    }

                    // Update highest nonce seen for this account+validStart
                    highestNonces.put(key, nonce);
                }
            }
        }

        logger.info("BlockItem nonce validation completed successfully");
    }
}
