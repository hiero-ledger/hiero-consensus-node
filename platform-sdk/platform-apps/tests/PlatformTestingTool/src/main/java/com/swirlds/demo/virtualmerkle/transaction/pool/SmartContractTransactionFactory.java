// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.transaction.pool;

import com.swirlds.demo.platform.HotspotConfiguration;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateSmartContract;
import com.swirlds.demo.platform.fs.stresstest.proto.SmartContractMethodExecution;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.virtualmerkle.config.SmartContractConfig;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValue;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;

/**
 * This class builds transactions for smart contracts.
 */
public class SmartContractTransactionFactory {
    private static final int SIZE_AFTER_PADDING = 100;
    private final PTTRandom random;
    private final SmartContractConfig smartContractConfig;

    private final long firstSmartContractId;
    private int smartContractsCreated;

    /**
     * Creates a new {@link SmartContractTransactionFactory} instance.
     *
     * @param random
     * 		An instance of {@link PTTRandom} that will be used to decide which method must be ushed for a method
     * 		execution, and generate seeds for transaction handling.
     * @param nodeId
     * 		The id of the current node.
     * @param smartContractsPerNode
     * 		The total number smart contracts that must be created per node.
     * @param smartContractConfig
     * 		The configuration specific for smart contracts.
     * @param firstSmartContractId
     * 		The id of the first smart contract id that should be used ( without taking into consideration the node
     * 		offset ).
     */
    public SmartContractTransactionFactory(
            final PTTRandom random,
            final long nodeId,
            final long smartContractsPerNode,
            final SmartContractConfig smartContractConfig,
            final long firstSmartContractId) {
        this.random = random;
        this.firstSmartContractId = firstSmartContractId + nodeId * smartContractsPerNode;
        this.smartContractsCreated = 0;
        this.smartContractConfig = smartContractConfig;
    }

    /**
     * This creates a {@link CreateSmartContract} transaction.
     *
     * @return A {@link VirtualMerkleTransaction.Builder} instance to obtain
     * 		the {@link CreateSmartContract} transaction created by this method.
     */
    public VirtualMerkleTransaction.Builder buildCreateSmartContractTransaction() {
        final CreateSmartContract smartContract = CreateSmartContract.newBuilder()
                .setContractId(generateNewContractId())
                .setTotalValuePairs(getTotalValuePairs())
                .setByteCodeSize(getByteCodeSize())
                .setSeed(random.nextLong())
                .build();
        final VirtualMerkleTransaction.Builder builder = VirtualMerkleTransaction.newBuilder()
                .setSmartContract(smartContract)
                .setSampled(false);
        return builder;
    }

    /**
     * This creates a {@link SmartContractMethodExecution} transaction.
     *
     * @param hotspot
     * 		Nullable hotspot configuration
     * @return A {@link VirtualMerkleTransaction.Builder} instance to obtain
     * 		the {@link SmartContractMethodExecution} transaction created by this method.
     */
    public VirtualMerkleTransaction.Builder buildMethodExecutionTransaction(final HotspotConfiguration hotspot) {
        final SmartContractMethodExecution methodExecution = SmartContractMethodExecution.newBuilder()
                .setSeed(random.nextLong())
                .setContractId(getRandomContractToBeUpdated(hotspot))
                .setReads(smartContractConfig.getReadsDuringMethodExecution())
                .setWrites(smartContractConfig.getWritesDuringMethodExecution())
                .setAdds(smartContractConfig.getAddsDuringMethodExecution())
                .build();
        return VirtualMerkleTransaction.newBuilder()
                .setMethodExecution(methodExecution)
                .setSampled(false);
    }

    private int getByteCodeSize() {
        return random.nextInt(
                smartContractConfig.getMinByteCodeSize(),
                Math.min(
                        smartContractConfig.getMaxByteCodeSize() + 1,
                        SmartContractByteCodeMapValue.MAX_BYTE_CODE_BYTES));
    }

    private long getTotalValuePairs() {
        return random.nextLong(
                smartContractConfig.getMinKeyValuePairsDuringCreation(),
                smartContractConfig.getMaxKeyValuePairsDuringCreation() + 1);
    }

    private long generateNewContractId() {
        return firstSmartContractId + (smartContractsCreated++);
    }

    private long getRandomContractToBeUpdated(final HotspotConfiguration hotspot) {
        if (smartContractsCreated == 0) {
            return firstSmartContractId;
        }

        if (hotspot == null) {
            return random.nextLong(firstSmartContractId, firstSmartContractId + smartContractsCreated);
        }

        final long limit = firstSmartContractId + hotspot.getSize();
        return random.nextLong(firstSmartContractId, limit);
    }

    public static int getSizeAfterPadding() {
        return SIZE_AFTER_PADDING;
    }
}
