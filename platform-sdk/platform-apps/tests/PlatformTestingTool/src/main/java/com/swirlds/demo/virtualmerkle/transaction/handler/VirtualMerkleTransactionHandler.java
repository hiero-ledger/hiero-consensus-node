// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.transaction.handler;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.HANDLED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.CreateExistingAccount;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.DeleteNotExistentAccount;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.UpdateNotExistentAccount;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.demo.merkle.map.MapValueData;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.Triple;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateSmartContract;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.SmartContractMethodExecution;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.virtualmerkle.map.account.AccountVirtualMapKey;
import com.swirlds.demo.virtualmerkle.map.account.AccountVirtualMapValue;
import com.swirlds.demo.virtualmerkle.map.account.AccountVirtualMapValueCodec;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapKey;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValue;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValueCodec;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapKey;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapValue;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapValueCodec;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

/**
 * This class handles all the transactions for virtual merkle tests.
 */
public class VirtualMerkleTransactionHandler {

    private static final Logger logger = LogManager.getLogger(VirtualMerkleTransactionHandler.class);
    private static final Marker LOGM_DEMO_INFO = LogMarker.DEMO_INFO.getMarker();

    /**
     * This method handles all the transactions that perform changes on the {@link VirtualMap} instances
     * inside the state.
     *
     * Transactions to create, update, and delete accounts are being supported, as well as transactions
     * to create smart contracts, and execute smart contract methods.
     *
     * @param consensusTimestamp
     * 		The consensus timestamp for {@code virtualMerkleTransaction}.
     * @param virtualMerkleTransaction
     * 		A wrapper around the transaction that needs to be handled.
     * @param expectedFCMFamily
     * 		The instance of {@link ExpectedFCMFamily} currently being used by PTT to store the lifecycle of entities.
     * @param accountVirtualMap
     * 		A {@link VirtualMap} instance storing accounts inside platform state.
     * @param smartContractVirtualMap
     * 		A {@link VirtualMap} instance storing smart contracts inside platform state.
     * @param smartContractByteCodeVirtualMap
     * 		A {@link VirtualMap} instance storing smart contract byte code inside platform state.
     */
    public static void handle(
            final Instant consensusTimestamp,
            final VirtualMerkleTransaction virtualMerkleTransaction,
            final ExpectedFCMFamily expectedFCMFamily,
            final VirtualMap accountVirtualMap,
            final VirtualMap smartContractVirtualMap,
            final VirtualMap smartContractByteCodeVirtualMap) {
        final Triple<TransactionType, MapKey, MapValueData> expectedMapInfo;
        if (virtualMerkleTransaction.hasCreateAccount()) {
            expectedMapInfo =
                    handleCreateAccountTransaction(virtualMerkleTransaction.getCreateAccount(), accountVirtualMap);
        } else if (virtualMerkleTransaction.hasDeleteAccount()) {
            expectedMapInfo =
                    handleDeleteAccountTransaction(virtualMerkleTransaction.getDeleteAccount(), accountVirtualMap);
        } else if (virtualMerkleTransaction.hasUpdateAccount()) {
            expectedMapInfo =
                    handleUpdateAccountTransaction(virtualMerkleTransaction.getUpdateAccount(), accountVirtualMap);
        } else {
            expectedMapInfo = null;
        }

        if (expectedMapInfo != null) {
            saveExpectedMapInfo(
                    consensusTimestamp,
                    virtualMerkleTransaction,
                    expectedFCMFamily,
                    expectedMapInfo.left(),
                    expectedMapInfo.middle(),
                    expectedMapInfo.right());
            return;
        }

        if (virtualMerkleTransaction.hasSmartContract()) {
            handleCreateSmartContractTransaction(
                    virtualMerkleTransaction.getSmartContract(),
                    smartContractVirtualMap,
                    smartContractByteCodeVirtualMap);
        } else if (virtualMerkleTransaction.hasMethodExecution()) {
            handleMethodExecutionTransaction(
                    virtualMerkleTransaction.getMethodExecution(),
                    smartContractVirtualMap,
                    smartContractByteCodeVirtualMap);
        }
    }

    private static void saveExpectedMapInfo(
            final Instant consensusTimestamp,
            final VirtualMerkleTransaction virtualMerkleTransaction,
            final ExpectedFCMFamily expectedFCMFamily,
            final TransactionType transactionType,
            final MapKey key,
            final MapValueData value) {
        if (virtualMerkleTransaction.getSampled()) {
            expectedFCMFamily.setLatestHandledStatusForKey(
                    key,
                    EntityType.VIRTUAL_MERKLE_ACCOUNT,
                    value,
                    HANDLED,
                    transactionType,
                    consensusTimestamp.getEpochSecond(),
                    virtualMerkleTransaction.getOriginNode(),
                    false);
        }
    }

    private static void handleMethodExecutionTransaction(
            final SmartContractMethodExecution methodExecution,
            final VirtualMap smartContractVirtualMap,
            final VirtualMap smartContractByteCodeVirtualMap) {
        // Perform the read of the bytecode of the smart contract executing the method.
        final Bytes byteCodeKey = new SmartContractByteCodeMapKey(methodExecution.getContractId()).toBytes();
        final SmartContractByteCodeMapValue smartContractByteCodeMapValue =
                smartContractByteCodeVirtualMap.get(byteCodeKey, SmartContractByteCodeMapValueCodec.INSTANCE);

        if (smartContractByteCodeMapValue == null) {
            logger.warn(
                    DEMO_INFO.getMarker(),
                    "Value for key {} was not found inside smart contract bytecode map.",
                    byteCodeKey);
            return;
        }

        final Bytes contractKey = new SmartContractMapKey(methodExecution.getContractId(), 0).toBytes();

        final SmartContractMapValue smartContractKeyValuePairsCounter =
                smartContractVirtualMap.get(contractKey, SmartContractMapValueCodec.INSTANCE);
        if (smartContractKeyValuePairsCounter == null) {
            logger.warn(
                    DEMO_INFO.getMarker(), "Value for key {} was not found inside smart contract map.", contractKey);
            return;
        }
        final long totalKeyValuePairs = smartContractKeyValuePairsCounter.getValueAsLong();

        final PTTRandom pttRandom = new PTTRandom(methodExecution.getSeed());
        for (int i = 0; i < methodExecution.getReads(); i++) {
            final long keyValuePairIdx = pttRandom.nextLong(1, totalKeyValuePairs + 1);
            final Bytes smartContractMapKey =
                    new SmartContractMapKey(methodExecution.getContractId(), keyValuePairIdx).toBytes();
            final SmartContractMapValue smartContractMapValue =
                    smartContractVirtualMap.get(smartContractMapKey, SmartContractMapValueCodec.INSTANCE);
            if (smartContractMapValue == null) {
                logger.warn(
                        DEMO_INFO.getMarker(),
                        "Value for key {} was not found inside smart contract map.",
                        smartContractMapKey);
                return;
            }
        }

        for (int i = 0; i < methodExecution.getWrites(); i++) {
            final long keyValuePairIdx = pttRandom.nextLong(1, totalKeyValuePairs + 1);
            final Bytes smartContractMapKey =
                    new SmartContractMapKey(methodExecution.getContractId(), keyValuePairIdx).toBytes();
            final SmartContractMapValue smartContractMapValue =
                    smartContractVirtualMap.get(smartContractMapKey, SmartContractMapValueCodec.INSTANCE);
            if (smartContractMapValue == null) {
                logger.warn(
                        DEMO_INFO.getMarker(),
                        "Value for key {} was not found inside smart contract map.",
                        smartContractMapKey);
                return;
            }

            final SmartContractMapValue mutableSmartContractMapValue = smartContractMapValue.copy();
            mutableSmartContractMapValue.changeValue(pttRandom);
            smartContractVirtualMap.put(
                    smartContractMapKey, mutableSmartContractMapValue, SmartContractMapValueCodec.INSTANCE);
        }

        for (long i = totalKeyValuePairs + 1; i <= totalKeyValuePairs + methodExecution.getAdds(); i++) {
            final Bytes smartContractMapKey = new SmartContractMapKey(methodExecution.getContractId(), i).toBytes();
            smartContractVirtualMap.put(
                    smartContractMapKey, new SmartContractMapValue(pttRandom), SmartContractMapValueCodec.INSTANCE);
        }

        if (!smartContractVirtualMap.containsKey(contractKey)) {
            throw new IllegalStateException("Contract not found for key=" + contractKey);
        }
        smartContractVirtualMap.put(
                contractKey,
                new SmartContractMapValue(totalKeyValuePairs + methodExecution.getAdds()),
                SmartContractMapValueCodec.INSTANCE);
    }

    private static void handleCreateSmartContractTransaction(
            final CreateSmartContract smartContract,
            final VirtualMap smartContractVirtualMap,
            final VirtualMap smartContractByteCodeVirtualMap) {
        // Create the key value pairs for the new smart contract.
        final PTTRandom random = new PTTRandom(smartContract.getSeed());
        for (int i = 1; i <= smartContract.getTotalValuePairs(); i++) {
            smartContractVirtualMap.put(
                    new SmartContractMapKey(smartContract.getContractId(), i).toBytes(),
                    new SmartContractMapValue(random),
                    SmartContractMapValueCodec.INSTANCE);
        }

        // Store the number of key value pairs as the value of the special key
        // created with the id of the smart contract and the keyValuePairIndex = 0
        smartContractVirtualMap.put(
                new SmartContractMapKey(smartContract.getContractId(), 0).toBytes(),
                new SmartContractMapValue(smartContract.getTotalValuePairs()),
                SmartContractMapValueCodec.INSTANCE);

        // Insert the bytecode of the smart contract.
        final SmartContractByteCodeMapKey byteCodeKey = new SmartContractByteCodeMapKey(smartContract.getContractId());
        final SmartContractByteCodeMapValue byteCodeValue =
                new SmartContractByteCodeMapValue(random, smartContract.getByteCodeSize());

        smartContractByteCodeVirtualMap.put(
                byteCodeKey.toBytes(), byteCodeValue, SmartContractByteCodeMapValueCodec.INSTANCE);
    }

    private static Triple<TransactionType, MapKey, MapValueData> handleUpdateAccountTransaction(
            final UpdateAccount updateAccount, final VirtualMap accountVirtualMap) {
        final AccountVirtualMapKey accountVirtualMapKey = new AccountVirtualMapKey(
                updateAccount.getRealmID(), updateAccount.getShardID(), updateAccount.getAccountID());

        final TransactionType transactionType;
        final MapValueData value;
        final Bytes accountVirtualMapKeyBytes = accountVirtualMapKey.toBytes();
        if (accountVirtualMap.containsKey(accountVirtualMapKeyBytes)) {
            final AccountVirtualMapValue currentValue =
                    accountVirtualMap.get(accountVirtualMapKeyBytes, AccountVirtualMapValueCodec.INSTANCE);
            final AccountVirtualMapValue newValue = new AccountVirtualMapValue(
                    updateAccount.getBalance(),
                    updateAccount.getSendThreshold(),
                    updateAccount.getReceiveThreshold(),
                    updateAccount.getRequireSignature(),
                    currentValue.getUid());

            accountVirtualMap.put(accountVirtualMapKeyBytes, newValue, AccountVirtualMapValueCodec.INSTANCE);

            value = new MapValueData(
                    newValue.getBalance(),
                    newValue.getSendThreshold(),
                    newValue.getReceiveThreshold(),
                    newValue.isRequireSignature(),
                    newValue.getUid());

            transactionType = Update;
        } else {
            value = new MapValueData(
                    updateAccount.getBalance(),
                    updateAccount.getSendThreshold(),
                    updateAccount.getReceiveThreshold(),
                    updateAccount.getRequireSignature(),
                    -1L);
            transactionType = UpdateNotExistentAccount;
        }

        final MapKey key =
                new MapKey(updateAccount.getShardID(), updateAccount.getRealmID(), updateAccount.getAccountID());

        value.setKey(key);

        return Triple.of(transactionType, key, value);
    }

    private static Triple<TransactionType, MapKey, MapValueData> handleDeleteAccountTransaction(
            final DeleteAccount deleteAccount, final VirtualMap accountVirtualMap) {
        final AccountVirtualMapKey accountVirtualMapKey = new AccountVirtualMapKey(
                deleteAccount.getRealmID(), deleteAccount.getShardID(), deleteAccount.getAccountID());

        final AccountVirtualMapValue removedValue =
                accountVirtualMap.remove(accountVirtualMapKey.toBytes(), AccountVirtualMapValueCodec.INSTANCE);
        final MapKey key =
                new MapKey(deleteAccount.getShardID(), deleteAccount.getRealmID(), deleteAccount.getAccountID());

        final TransactionType transactionType;
        if (removedValue == null) {
            transactionType = DeleteNotExistentAccount;
        } else {
            transactionType = Delete;
        }

        return Triple.of(transactionType, key, null);
    }

    private static Triple<TransactionType, MapKey, MapValueData> handleCreateAccountTransaction(
            final CreateAccount createAccount, final VirtualMap accountVirtualMap) {

        final AccountVirtualMapKey accountVirtualMapKey = new AccountVirtualMapKey(
                createAccount.getRealmID(), createAccount.getShardID(), createAccount.getAccountID());
        final AccountVirtualMapValue accountVirtualMapValue = new AccountVirtualMapValue(
                createAccount.getBalance(),
                createAccount.getSendThreshold(),
                createAccount.getReceiveThreshold(),
                createAccount.getRequireSignature(),
                createAccount.getUid());

        final TransactionType transactionType;
        final Bytes accountVirtualMapKeyBytes = accountVirtualMapKey.toBytes();
        if (accountVirtualMap.containsKey(accountVirtualMapKeyBytes)) {
            transactionType = CreateExistingAccount;
        } else {
            transactionType = Create;
        }

        accountVirtualMap.put(accountVirtualMapKeyBytes, accountVirtualMapValue, AccountVirtualMapValueCodec.INSTANCE);

        final MapKey key =
                new MapKey(createAccount.getShardID(), createAccount.getRealmID(), createAccount.getAccountID());

        final MapValueData value = new MapValueData(
                createAccount.getBalance(),
                createAccount.getSendThreshold(),
                createAccount.getReceiveThreshold(),
                createAccount.getRequireSignature(),
                createAccount.getUid());

        value.setKey(key);

        return Triple.of(transactionType, key, value);
    }

    /**
     * This method verifies if the entities with type {@link EntityType#VIRTUAL_MERKLE_ACCOUNT} from the
     * {@code expectedFCMFamily} have the correct lifecycle status with respect to their
     * presence or not inside the given {@code virtualMap}.
     *
     * @param expectedFCMFamily
     * 		The instance of {@link ExpectedFCMFamily} currently being used by PTT to store the lifecycle of entities.
     * @param virtualMap
     * 		A {@link VirtualMap} instance storing accounts inside platform state.
     */
    public static void handleExpectedMapValidation(
            final ExpectedFCMFamily expectedFCMFamily, final VirtualMap virtualMap) {
        if (virtualMap == null) {
            return;
        }
        AtomicBoolean notMismatching = new AtomicBoolean(true);
        expectedFCMFamily.getExpectedMap().forEach((mapKey, expectedValue) -> {
            if (expectedValue.getEntityType() != EntityType.VIRTUAL_MERKLE_ACCOUNT) {
                return;
            }
            final AccountVirtualMapKey accountVirtualMapKey =
                    new AccountVirtualMapKey(mapKey.getRealmId(), mapKey.getShardId(), mapKey.getAccountId());
            final Bytes accountVirtualMapKeyBytes = accountVirtualMapKey.toBytes();
            final TransactionType lastTransactionType =
                    expectedValue.getLatestHandledStatus().getTransactionType();
            if (lastTransactionType == Create || lastTransactionType == Update) {
                final AccountVirtualMapValue virtualMapValue =
                        virtualMap.get(accountVirtualMapKeyBytes, AccountVirtualMapValueCodec.INSTANCE);
                if (virtualMapValue == null) {
                    notMismatching.set(false);
                    logger.warn(
                            DEMO_INFO.getMarker(), "An account from the expected map is not present inside the state.");
                    return;
                }
                final MapValueData mapValueData = new MapValueData(
                        virtualMapValue.getBalance(),
                        virtualMapValue.getSendThreshold(),
                        virtualMapValue.getReceiveThreshold(),
                        virtualMapValue.isRequireSignature(),
                        virtualMapValue.getUid());

                if (!mapValueData.calculateHash().equals(expectedValue.getHash())) {
                    notMismatching.set(false);
                }
            } else if (lastTransactionType == CreateExistingAccount) {
                if (!virtualMap.containsKey(accountVirtualMapKeyBytes)) {
                    notMismatching.set(false);
                    logger.warn(DEMO_INFO.getMarker(), "A created account does not exist inside the state.");
                }
            } else if (lastTransactionType == Delete
                    || lastTransactionType == DeleteNotExistentAccount
                    || lastTransactionType == UpdateNotExistentAccount) {
                if (virtualMap.containsKey(accountVirtualMapKeyBytes)) {
                    notMismatching.set(false);
                    logger.warn(DEMO_INFO.getMarker(), "A deleted account is still present inside the state.");
                }
            }
        });
        if (notMismatching.get()) {
            logger.info(LOGM_DEMO_INFO, "There was no mismatch between the expected map and state.");
        }
    }
}
