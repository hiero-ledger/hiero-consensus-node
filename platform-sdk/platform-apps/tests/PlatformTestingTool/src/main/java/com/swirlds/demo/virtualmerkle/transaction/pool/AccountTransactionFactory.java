// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.transaction.pool;

import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;

import com.swirlds.base.utility.Pair;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.HotspotConfiguration;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.time.Instant;
import java.util.ArrayList;

/**
 * This class is responsible to generate all the account transactions
 * for virtual merkle tests.
 */
class AccountTransactionFactory {

    private final PTTRandom random;
    private final double samplingProbability;
    private final CreatedAccountIds createdAccountIds;
    private final long firstAccountId;
    private long idOfLatestCreatedAccount;

    private final long nodeId;

    private final ExpectedFCMFamily expectedFCMFamily;

    private static final long UPDATED_BALANCE = 1_000_000;

    private static final int SIZE_AFTER_PADDING = 100;

    /**
     * Creates a new {@link AccountTransactionFactory} instance.
     *
     * @param random
     * 		An instance of {@link PTTRandom} that will be used to select account ids randomly.
     * @param samplingProbability
     * 		The probability of saving one created account into the expected map.
     * @param expectedFCMFamily
     * 		The {@link ExpectedFCMFamily} instance currently being used by the platform.
     * @param nodeId
     * 		The id of the current node.
     * @param totalAccountsToBeCreated
     * 		The total number of accounts to be created by this node.
     * @param firstAccountId
     * 		The first account id to be used ( without taking into consideration the node offset ).
     */
    AccountTransactionFactory(
            final PTTRandom random,
            final double samplingProbability,
            final ExpectedFCMFamily expectedFCMFamily,
            final long nodeId,
            final long totalAccountsToBeCreated,
            final long firstAccountId) {
        this.random = random;
        this.samplingProbability = samplingProbability;
        this.expectedFCMFamily = expectedFCMFamily;
        this.nodeId = nodeId;
        this.firstAccountId = firstAccountId + nodeId * totalAccountsToBeCreated;
        this.idOfLatestCreatedAccount = this.firstAccountId;
        this.createdAccountIds = new CreatedAccountIds(random);
    }

    /**
     * This method creates a {@link CreateAccount} transaction.
     *
     * @return A {@code Pair<MapKey, VirtualMerkleTransaction.Builder>} instance
     * 		where the key is a {@link MapKey} instance that must be used to store
     * 		the transaction built by the builder stored as the value.
     */
    public Pair<MapKey, VirtualMerkleTransaction.Builder> buildCreateAccountTransaction() {
        long accountId;
        MapKey key;
        // make sure the account not already existed in case FCMTransactionPool create an FCQ or FCM
        // account for the same key already
        do {
            accountId = getNextAccountId();
            key = new MapKey(nodeId, nodeId, accountId);
        } while (this.expectedFCMFamily.getExpectedMap().containsKey(key));

        final CreateAccount createAccount = CreateAccount.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .setBalance(key.getAccountId())
                .setSendThreshold(key.getAccountId())
                .setReceiveThreshold(key.getAccountId())
                .setRequireSignature(false)
                .setUid(this.random.nextLong())
                .build();

        final VirtualMerkleTransaction.Builder virtualMerkleTransactionBuilder = VirtualMerkleTransaction.newBuilder()
                .setCreateAccount(createAccount)
                .setOriginNode(nodeId);

        if (this.random.nextDouble() <= this.samplingProbability) {
            // Useful for debugging.
            final ExpectedValue expectedValue = new ExpectedValue(
                    EntityType.VIRTUAL_MERKLE_ACCOUNT,
                    new LifecycleStatus(
                            TransactionState.INITIALIZED, Create, Instant.now().getEpochSecond(), nodeId));

            createdAccountIds.addAccountId(accountId);

            this.expectedFCMFamily.addEntityToExpectedMap(key, expectedValue);

            virtualMerkleTransactionBuilder.setSampled(true);
        } else {
            virtualMerkleTransactionBuilder.setSampled(false);
        }

        return Pair.of(key, virtualMerkleTransactionBuilder);
    }

    /**
     * This method creates a {@link UpdateAccount} transaction.
     *
     * @param hotspot
     * 		Nullable hotspot configuration
     * @return A {@code Pair<MapKey, VirtualMerkleTransaction.Builder>} instance
     * 		where the key is a {@link MapKey} instance that must be used to store
     * 		the transaction built by the builder stored as the value.
     */
    public Pair<MapKey, VirtualMerkleTransaction.Builder> buildUpdateAccountTransaction(
            final HotspotConfiguration hotspot) {
        final long idOfAccountToBeUpdate = getIdOfAccountToBeUpdated(hotspot);
        final MapKey key = new MapKey(nodeId, nodeId, idOfAccountToBeUpdate);

        final UpdateAccount updateAccount = UpdateAccount.newBuilder()
                .setShardID(nodeId)
                .setRealmID(nodeId)
                .setAccountID(idOfAccountToBeUpdate)
                .setBalance(UPDATED_BALANCE)
                .setSendThreshold(idOfAccountToBeUpdate)
                .setReceiveThreshold(idOfAccountToBeUpdate)
                .setRequireSignature(false)
                .build();

        final VirtualMerkleTransaction.Builder virtualMerkleTransactionBuilder = VirtualMerkleTransaction.newBuilder()
                .setUpdateAccount(updateAccount)
                .setOriginNode(nodeId)
                .setSampled(false);

        expectedFCMFamily.getExpectedMap().computeIfPresent(key, (mapKey, expectedValue) -> {
            expectedValue.setLatestSubmitStatus(new LifecycleStatus(
                    TransactionState.INITIALIZED,
                    TransactionType.Update,
                    Instant.now().getEpochSecond(),
                    nodeId));
            virtualMerkleTransactionBuilder.setSampled(true);
            return expectedValue;
        });

        return Pair.of(key, virtualMerkleTransactionBuilder);
    }

    /**
     * This method creates a {@link DeleteAccount} transaction.
     *
     * @return A {@code Pair<MapKey, VirtualMerkleTransaction.Builder>} instance
     * 		where the key is a {@link MapKey} instance that must be used to store
     * 		the transaction built by the builder stored as the value.
     */
    public Pair<MapKey, VirtualMerkleTransaction.Builder> buildDeleteAccountTransaction() {
        final Long idOfAccountToBeDeleted =
                createdAccountIds.removeRandomId(this.firstAccountId, this.idOfLatestCreatedAccount);
        final MapKey key = new MapKey(nodeId, nodeId, idOfAccountToBeDeleted);

        final DeleteAccount deleteAccount = DeleteAccount.newBuilder()
                .setShardID(nodeId)
                .setRealmID(nodeId)
                .setAccountID(idOfAccountToBeDeleted)
                .build();

        final VirtualMerkleTransaction.Builder virtualMerkleTransactionBuilder = VirtualMerkleTransaction.newBuilder()
                .setDeleteAccount(deleteAccount)
                .setOriginNode(nodeId)
                .setSampled(false);

        expectedFCMFamily.getExpectedMap().computeIfPresent(key, (mapKey, expectedValue) -> {
            expectedValue.setLatestSubmitStatus(new LifecycleStatus(
                    TransactionState.INITIALIZED,
                    TransactionType.Delete,
                    Instant.now().getEpochSecond(),
                    nodeId));
            virtualMerkleTransactionBuilder.setSampled(true);
            return expectedValue;
        });

        return Pair.of(key, virtualMerkleTransactionBuilder);
    }

    private long getNextAccountId() {
        return this.idOfLatestCreatedAccount++;
    }

    public static int getSizeAfterPadding() {
        return SIZE_AFTER_PADDING;
    }

    private long getIdOfAccountToBeUpdated(final HotspotConfiguration hotspot) {
        if (hotspot == null || !hotspot.shouldBeUsed()) {
            return createdAccountIds.getRandomId(this.firstAccountId, this.idOfLatestCreatedAccount);
        }

        return createdAccountIds.getRandomId(hotspot.getSize());
    }

    private static class CreatedAccountIds {
        private final ArrayList<Long> createdAccountIds;
        private final PTTRandom random;

        public CreatedAccountIds(final PTTRandom random) {
            this.createdAccountIds = new ArrayList<>();
            this.random = random;
        }

        public void addAccountId(final long accountId) {
            createdAccountIds.add(accountId);
        }

        public Long getRandomId(final long lower, final long upper) {
            return this.random.nextLong(lower, upper);
        }

        public Long getRandomId(final int sizeLimit) {
            if (this.createdAccountIds.isEmpty()) {
                return this.random.nextLong();
            }

            final int idx = random.nextInt(sizeLimit);
            return this.createdAccountIds.get(idx);
        }

        public Long removeRandomId(final long firstAccountId, final long idOfLatestCreatedAccount) {
            if (createdAccountIds.isEmpty()) {
                return this.random.nextLong(firstAccountId, idOfLatestCreatedAccount);
            }

            if (createdAccountIds.size() == 1) {
                return createdAccountIds.remove(0);
            }

            // We pick one at random and put the last one at its place to avoid
            // shifting the entire array.
            final int idx = random.nextInt(this.createdAccountIds.size());
            if (idx == createdAccountIds.size() - 1) {
                return createdAccountIds.remove(idx);
            }

            final Long chosen = createdAccountIds.get(idx);

            final Long lastOne = createdAccountIds.remove(createdAccountIds.size() - 1);

            createdAccountIds.set(idx, lastOne);

            return chosen;
        }
    }
}
