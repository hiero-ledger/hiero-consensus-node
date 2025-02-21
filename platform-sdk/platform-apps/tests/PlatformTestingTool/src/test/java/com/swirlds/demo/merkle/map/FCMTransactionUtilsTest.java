// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.Crypto;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.FCQ;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Append;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Transfer;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.demo.platform.Triple;
import com.swirlds.demo.platform.fs.stresstest.proto.Activity;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccountFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalance;
import com.swirlds.demo.platform.fs.stresstest.proto.TransferBalanceFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccountFCQ;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class FCMTransactionUtilsTest {

    private static AtomicInteger id = new AtomicInteger();

    private static List<Triple<TransactionType, EntityType, MapKey>> triples = buildTriples();

    private static List<FCMTransaction> transactions = buildTransactions(triples);
    // the last FCMTransaction is an Activity
    private static final FCMTransaction activity = transactions.get(transactions.size() - 1);

    @Test
    public void getTransactionTypeTest() {
        // for all FCMTransactions except Activity,
        // extracted TransactionType should match the original TransactionType
        for (int i = 0; i < transactions.size() - 1; i++) {
            assertEquals(triples.get(i).left(), FCMTransactionUtils.getTransactionType(transactions.get(i)));
        }

        assertEquals(null, FCMTransactionUtils.getTransactionType(activity));
    }

    @Test
    public void getEntityTypeTest() {
        // for all FCMTransactions except Activity,
        // extracted EntityType should match the original EntityType
        for (int i = 0; i < transactions.size() - 1; i++) {
            assertEquals(triples.get(i).middle(), FCMTransactionUtils.getEntityType(transactions.get(i)));
        }

        assertEquals(null, FCMTransactionUtils.getEntityType(activity));
    }

    @Test
    public void getMapKeyTest() {
        // for all FCMTransactions except Activity,
        // extracted MapKey should match the original MapKey
        for (int i = 0; i < transactions.size() - 1; i++) {
            final List<MapKey> mapKeys = FCMTransactionUtils.getMapKeys(transactions.get(i));
            // for transfer transactions, the list size should be 2
            if (FCMTransactionUtils.getTransactionType(transactions.get(i)).equals(Transfer)) {
                assertEquals(2, mapKeys.size());
                // compare sender
                assertEquals(triples.get(i).right(), mapKeys.get(0));
                // compare receiver
                MapKey receiver = mapKeys.get(1);
                assertEquals(triples.get(i).right().getShardId() + 1, receiver.getShardId());
                assertEquals(triples.get(i).right().getRealmId() + 1, receiver.getRealmId());
                assertEquals(triples.get(i).right().getAccountId() + 1, receiver.getAccountId());
            } else {
                // for other transactions, the list size should be 1
                assertEquals(1, mapKeys.size());
                assertEquals(triples.get(i).right(), mapKeys.get(0));
            }
        }

        assertEquals(0, FCMTransactionUtils.getMapKeys(activity).size());
    }

    /**
     * Build triples which would be used for generating FCMTransaction and
     * validating the methods
     *
     * @return
     */
    private static List<Triple<TransactionType, EntityType, MapKey>> buildTriples() {
        List<Triple<TransactionType, EntityType, MapKey>> triples = new ArrayList<>();
        triples.add(Triple.of(Create, Crypto, mapKey()));
        triples.add(Triple.of(Update, Crypto, mapKey()));
        triples.add(Triple.of(Transfer, Crypto, mapKey()));
        triples.add(Triple.of(Delete, Crypto, mapKey()));
        triples.add(Triple.of(Create, FCQ, mapKey()));
        triples.add(Triple.of(Update, FCQ, mapKey()));
        triples.add(Triple.of(Transfer, FCQ, mapKey()));
        triples.add(Triple.of(Delete, FCQ, mapKey()));
        return triples;
    }

    private static List<FCMTransaction> buildTransactions(List<Triple<TransactionType, EntityType, MapKey>> triples) {
        List<FCMTransaction> transactions = new ArrayList<>();
        for (Triple<TransactionType, EntityType, MapKey> triple : triples) {
            transactions.add(generateFCMTransaction(triple.left(), triple.middle(), triple.right()));
        }
        transactions.add(generateActivity());
        return transactions;
    }

    private static MapKey mapKey() {
        return new MapKey(id.getAndIncrement(), id.getAndIncrement(), id.getAndIncrement());
    }

    /**
     * Generate FCMTransaction with given transactionType, entityType, and key
     *
     * @param transactionType
     * @param entityType
     * @param key
     * @return
     */
    private static FCMTransaction generateFCMTransaction(
            TransactionType transactionType, EntityType entityType, MapKey key) {
        FCMTransaction.Builder builder = FCMTransaction.newBuilder();
        if (transactionType.equals(Create)) {
            switch (entityType) {
                case Crypto:
                    builder.setCreateAccount(CreateAccount.newBuilder()
                            .setShardID(key.getShardId())
                            .setRealmID(key.getRealmId())
                            .setAccountID(key.getAccountId())
                            .build());
                    break;
                case FCQ:
                    builder.setCreateAccountFCQ(CreateAccountFCQ.newBuilder()
                            .setShardID(key.getShardId())
                            .setRealmID(key.getRealmId())
                            .setAccountID(key.getAccountId())
                            .build());
                    break;
                default:
                    System.out.println("Create: Invalid EntityType" + entityType);
            }
        } else if (transactionType.equals(Update)) {
            switch (entityType) {
                case Crypto:
                    builder.setUpdateAccount(UpdateAccount.newBuilder()
                            .setShardID(key.getShardId())
                            .setRealmID(key.getRealmId())
                            .setAccountID(key.getAccountId())
                            .build());
                    break;
                case FCQ:
                    builder.setUpdateAccountFCQ(UpdateAccountFCQ.newBuilder()
                            .setShardID(key.getShardId())
                            .setRealmID(key.getRealmId())
                            .setAccountID(key.getAccountId())
                            .build());
                    break;
                default:
                    System.out.println("Update: Invalid EntityType" + entityType);
            }
        } else if (transactionType.equals(Delete)) {
            switch (entityType) {
                case Crypto:
                    builder.setDeleteAccount(DeleteAccount.newBuilder()
                            .setShardID(key.getShardId())
                            .setRealmID(key.getRealmId())
                            .setAccountID(key.getAccountId())
                            .build());
                    break;
                case FCQ:
                    builder.setDeleteFCQ(DeleteFCQ.newBuilder()
                            .setShardID(key.getShardId())
                            .setRealmID(key.getRealmId())
                            .setAccountID(key.getAccountId())
                            .build());
                    break;
                default:
                    System.out.println("Delete: Invalid EntityType" + entityType);
            }
        } else if (transactionType.equals(Append)) {
            switch (entityType) {
                default:
                    System.out.println("Append: Invalid EntityType" + entityType);
            }
        } else if (transactionType.equals(Transfer)) {
            switch (entityType) {
                case Crypto:
                    builder.setTransferBalance(TransferBalance.newBuilder()
                            .setFromShardID(key.getShardId())
                            .setFromRealmID(key.getRealmId())
                            .setFromAccountID(key.getAccountId())
                            .setToShardID(key.getShardId() + 1)
                            .setToRealmID(key.getRealmId() + 1)
                            .setToAccountID(key.getAccountId() + 1)
                            .build());
                    break;
                case FCQ:
                    builder.setTransferBalanceFCQ(TransferBalanceFCQ.newBuilder()
                            .setFromShardID(key.getShardId())
                            .setFromRealmID(key.getRealmId())
                            .setFromAccountID(key.getAccountId())
                            .setToShardID(key.getShardId() + 1)
                            .setToRealmID(key.getRealmId() + 1)
                            .setToAccountID(key.getAccountId() + 1)
                            .build());
                    break;
                default:
                    System.out.println("Transfer: Invalid EntityType" + entityType);
            }
        }
        return builder.build();
    }

    /**
     * generate a FCMTransaction with Activity type
     *
     * @return
     */
    private static FCMTransaction generateActivity() {
        return FCMTransaction.newBuilder()
                .setActivity(Activity.newBuilder()
                        .setType(Activity.ActivityType.SAVE_EXPECTED_MAP)
                        .build())
                .build();
    }
}
