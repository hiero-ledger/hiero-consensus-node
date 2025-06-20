// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.transaction.pool;

import static com.swirlds.demo.platform.fs.stresstest.proto.Activity.ActivityType.SAVE_EXPECTED_MAP;
import static java.lang.String.format;

import com.google.protobuf.ByteString;
import com.swirlds.base.utility.Pair;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.platform.Triple;
import com.swirlds.demo.platform.fs.stresstest.proto.Activity;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.virtualmerkle.config.TransactionRequestConfig;
import com.swirlds.demo.virtualmerkle.config.TransactionRequestSupplierFactory;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.util.function.Supplier;

/**
 * This class builds the transactions for virtual merkle tests.
 */
public class VirtualMerkleTransactionPool {

    private final Supplier<TransactionRequestConfig> transactionRequestSupplier;
    private final AccountTransactionFactory accountTransactionFactory;
    private final SmartContractTransactionFactory smartContractTransactionFactory;
    private final long nodeId;
    private final VirtualMerkleConfig virtualMerkleConfig;

    /**
     * This class uses an instance of the factory {@link AccountTransactionFactory} to
     * build transactions for accounts, and the {@link SmartContractTransactionFactory} to
     * build transactions for smart contracts. Also, this class makes usage
     * of an instance from {@link TransactionRequestSupplierFactory} to get {@link TransactionRequestConfig} instances.
     *
     * @param nodeId
     * 		The id of the current node.
     * @param virtualMerkleConfig
     * 		The configurations for the virtual merkle tests.
     * @param expectedFCMFamily
     * 		The instance of {@link ExpectedFCMFamily} being used by platform.
     */
    public VirtualMerkleTransactionPool(
            final long nodeId,
            final VirtualMerkleConfig virtualMerkleConfig,
            final ExpectedFCMFamily expectedFCMFamily) {
        final PTTRandom random = new PTTRandom();

        this.nodeId = nodeId;

        this.accountTransactionFactory = new AccountTransactionFactory(
                random,
                virtualMerkleConfig.getSamplingProbability(),
                expectedFCMFamily,
                nodeId,
                virtualMerkleConfig.getTotalAccountCreations(),
                virtualMerkleConfig.getFirstAccountId());

        this.smartContractTransactionFactory = new SmartContractTransactionFactory(
                random,
                nodeId,
                virtualMerkleConfig.getTotalSmartContractCreations(),
                virtualMerkleConfig.getSmartContractConfig(),
                virtualMerkleConfig.getFirstSmartContractId());

        this.transactionRequestSupplier = TransactionRequestSupplierFactory.create(virtualMerkleConfig);

        this.virtualMerkleConfig = virtualMerkleConfig;
    }

    /**
     * Builds a new transaction based on the {@link VirtualMerkleConfig} instance
     * given during the creation of this object.
     *
     * @return A triple where {@link Triple#left()} is a byte array with the contents of the transaction,
     *        {@link Triple#middle()} is a {@link PAYLOAD_TYPE} showing what is the type of the transaction,
     *        {@link Triple#right()} is a {@link MapKey} to find the lifecycle of the entity produced by
     * 		the transaction inside {@link ExpectedFCMFamily} instance given during the creation of this object.
     */
    public Triple<byte[], PAYLOAD_TYPE, MapKey> getTransaction() {
        final TransactionRequestConfig currentTransactionConfig = transactionRequestSupplier.get();
        if (currentTransactionConfig == null) {
            return null;
        }

        return createNewTransaction(currentTransactionConfig);
    }

    private Triple<byte[], PAYLOAD_TYPE, MapKey> createNewTransaction(
            final TransactionRequestConfig currentTransactionConfig) {
        final PAYLOAD_TYPE generateType = currentTransactionConfig.getType();
        if (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE) {
            final Pair<MapKey, VirtualMerkleTransaction.Builder> mapAndTransaction =
                    accountTransactionFactory.buildCreateAccountTransaction();

            return buildTransactionTriple(
                    mapAndTransaction.value(),
                    generateType,
                    mapAndTransaction.key(),
                    AccountTransactionFactory.getSizeAfterPadding());
        }

        if (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_UPDATE
                ||
                // use virtual update test to replace delete transactions
                (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_DELETE
                        && virtualMerkleConfig.isBypassDeleteTransaction())) {
            final Pair<MapKey, VirtualMerkleTransaction.Builder> mapAndTransaction =
                    accountTransactionFactory.buildUpdateAccountTransaction(currentTransactionConfig.getHotspot());

            return buildTransactionTriple(
                    mapAndTransaction.value(),
                    generateType,
                    mapAndTransaction.key(),
                    AccountTransactionFactory.getSizeAfterPadding());
        }

        if (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_DELETE) {
            final Pair<MapKey, VirtualMerkleTransaction.Builder> mapAndTransaction =
                    accountTransactionFactory.buildDeleteAccountTransaction();

            return buildTransactionTriple(
                    mapAndTransaction.value(),
                    generateType,
                    mapAndTransaction.key(),
                    AccountTransactionFactory.getSizeAfterPadding());
        }

        if (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE_SMART_CONTRACT) {
            final VirtualMerkleTransaction.Builder builder =
                    smartContractTransactionFactory.buildCreateSmartContractTransaction();

            return buildTransactionTriple(
                    builder, generateType, null, SmartContractTransactionFactory.getSizeAfterPadding());
        }

        if (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_SMART_CONTRACT_METHOD_EXECUTION) {
            final VirtualMerkleTransaction.Builder builder =
                    smartContractTransactionFactory.buildMethodExecutionTransaction(
                            currentTransactionConfig.getHotspot());

            return buildTransactionTriple(
                    builder, generateType, null, SmartContractTransactionFactory.getSizeAfterPadding());
        }

        if (generateType == PAYLOAD_TYPE.SAVE_EXPECTED_MAP) {
            final FCMTransaction fcmTransaction = FCMTransaction.newBuilder()
                    .setActivity(Activity.newBuilder().setType(SAVE_EXPECTED_MAP))
                    .setOriginNode(nodeId)
                    .build();

            final byte[] transactionBytes = TestTransaction.newBuilder()
                    .setFcmTransaction(fcmTransaction)
                    .build()
                    .toByteArray();

            final PAYLOAD_TYPE payloadType = PAYLOAD_TYPE.BodyCase_TO_PAYLOAD_TYPE.get(fcmTransaction.getBodyCase());

            return Triple.of(transactionBytes, payloadType, null);
        }

        final String msg =
                format("The given type %s was not recognized as a Virtual Merkle transaction type.", generateType);
        throw new RuntimeException(msg);
    }

    private Triple<byte[], PAYLOAD_TYPE, MapKey> buildTransactionTriple(
            final VirtualMerkleTransaction.Builder virtualMerkleTransactionBuilder,
            final PAYLOAD_TYPE payloadType,
            final MapKey key,
            final int sizeAfterPadding) {

        final VirtualMerkleTransaction transaction = virtualMerkleTransactionBuilder.build();
        final int length = transaction.toByteArray().length;
        if (length < sizeAfterPadding) {
            virtualMerkleTransactionBuilder.setPaddingBytes(ByteString.copyFrom(new byte[sizeAfterPadding - length]));
        }

        final byte[] transactionBytes = TestTransaction.newBuilder()
                .setVirtualMerkleTransaction(virtualMerkleTransactionBuilder)
                .build()
                .toByteArray();

        return Triple.of(transactionBytes, payloadType, key);
    }
}
