// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.config;

/**
 * This class holds the configuration for the creation of smart contracts
 * and the method execution of smart contracts.
 */
public final class SmartContractConfig {

    private int minKeyValuePairsDuringCreation;

    private int maxKeyValuePairsDuringCreation;

    private int minByteCodeSize;

    private int maxByteCodeSize;

    private int readsDuringMethodExecution;

    private int writesDuringMethodExecution;

    private int addsDuringMethodExecution;

    /**
     * @return The minimum number of key value pairs created for a
     * 		smart contract.
     */
    public int getMinKeyValuePairsDuringCreation() {
        return minKeyValuePairsDuringCreation;
    }

    /**
     * Sets the minimum number of key value pairs created for a smart contract.
     *
     * @param minKeyValuePairsDuringCreation
     * 		The minimum number of key value pairs created for a
     * 		smart contract.
     */
    public void setMinKeyValuePairsDuringCreation(final int minKeyValuePairsDuringCreation) {
        this.minKeyValuePairsDuringCreation = minKeyValuePairsDuringCreation;
    }

    /**
     * @return The maximum number of key value pairs created for a smart contract.
     */
    public int getMaxKeyValuePairsDuringCreation() {
        return maxKeyValuePairsDuringCreation;
    }

    /**
     * Sets the maximum number of key value pairs created for a smart contract.
     *
     * @param maxKeyValuePairsDuringCreation
     * 		The maximum number of key value pairs created for a smart contract.
     */
    public void setMaxKeyValuePairsDuringCreation(final int maxKeyValuePairsDuringCreation) {
        this.maxKeyValuePairsDuringCreation = maxKeyValuePairsDuringCreation;
    }

    /**
     * @return The number of reads to be performed during the execution of a method
     * 		from a smart contract.
     */
    public int getReadsDuringMethodExecution() {
        return readsDuringMethodExecution;
    }

    /**
     * Sets The number of reads to be performed during the execution of a method from a smart contract.
     *
     * @param readsDuringMethodExecution
     * 		The number of reads to be performed during the execution of a method
     * 		from a smart contract.
     */
    public void setReadsDuringMethodExecution(final int readsDuringMethodExecution) {
        this.readsDuringMethodExecution = readsDuringMethodExecution;
    }

    /**
     * @return The number of writes to be performed during the execution of a method
     * 		from a smart contract.
     */
    public int getWritesDuringMethodExecution() {
        return writesDuringMethodExecution;
    }

    /**
     * Sets the number of writes to be performed during the execution of a method
     * from a smart contract.
     *
     * @param writesDuringMethodExecution
     * 		The number of writes to be performed during the execution of a method
     * 		from a smart contract.
     */
    public void setWritesDuringMethodExecution(final int writesDuringMethodExecution) {
        this.writesDuringMethodExecution = writesDuringMethodExecution;
    }

    /**
     * @return The number of adds to be performed during the execution of a method
     * 		from a smart contract.
     */
    public int getAddsDuringMethodExecution() {
        return addsDuringMethodExecution;
    }

    /**
     * Sets the number of adds to be performed during the execution of a method
     * from a smart contract.
     *
     * @param addsDuringMethodExecution
     * 		The number of adds to be performed during the execution of a method
     * 		from a smart contract.
     */
    public void setAddsDuringMethodExecution(final int addsDuringMethodExecution) {
        this.addsDuringMethodExecution = addsDuringMethodExecution;
    }

    /**
     * @return The minimum size in bytes for the bytecode of a smart contract.
     */
    public int getMinByteCodeSize() {
        return minByteCodeSize;
    }

    /**
     * Sets the minimum size in bytes for the bytecode of a smart contract.
     *
     * @param minByteCodeSize
     * 		The minimum size in bytes for the bytecode of a smart contract.
     */
    public void setMinByteCodeSize(final int minByteCodeSize) {
        this.minByteCodeSize = minByteCodeSize;
    }

    /**
     * @return The maximum size in bytes for the bytecode of a smart contract.
     */
    public int getMaxByteCodeSize() {
        return maxByteCodeSize;
    }

    /**
     * Sets the maximum size in bytes for the bytecode of a smart contract.
     *
     * @param maxByteCodeSize
     * 		The maximum size in bytes for the bytecode of a smart contract.
     */
    public void setMaxByteCodeSize(final int maxByteCodeSize) {
        this.maxByteCodeSize = maxByteCodeSize;
    }
}
