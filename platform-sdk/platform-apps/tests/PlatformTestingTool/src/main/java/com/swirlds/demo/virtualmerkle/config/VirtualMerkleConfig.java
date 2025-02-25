// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.config;

import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE_SMART_CONTRACT;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_SMART_CONTRACT_METHOD_EXECUTION;

import com.swirlds.demo.platform.PAYLOAD_TYPE;
import java.util.List;

/**
 * This class holds the configuration for Virtual Merkle tests.
 */
public class VirtualMerkleConfig {

    private List<TransactionRequestConfig> sequential;

    private boolean assorted = false;

    private double samplingProbability = 0.0;

    private SmartContractConfig smartContractConfig;

    private long firstSmartContractId;

    private long firstAccountId;

    // Temporarily disable all virtual merkle delete transaction
    private boolean bypassDeleteTransaction = true;

    /**
     * @return The array of transactions to be executed.
     */
    public List<TransactionRequestConfig> getSequential() {
        return sequential;
    }

    /**
     * Sets the array of transactions to be executed.
     *
     * @param sequential
     * 		The array of transactions to be executed.
     */
    public void setSequential(final List<TransactionRequestConfig> sequential) {
        this.sequential = sequential;
    }

    /**
     * Sets the probability of an entity (account or smart contract) being being sampled
     * and have its lifecycle recorded inside of an expected map.
     *
     * @param samplingProbability
     * 		The probability of an entity (account or smart contract) being being sampled
     * 		and have its lifecycle recorded inside of an expected map.
     */
    public void setSamplingProbability(final double samplingProbability) {
        this.samplingProbability = samplingProbability;
    }

    /**
     * @return The probability of an entity (account or smart contract) being being sampled
     * 		and have its lifecycle recorded inside of an expected map.
     */
    public double getSamplingProbability() {
        return this.samplingProbability;
    }

    /**
     * @return {@code true} if the transactions from {@link #sequential} are going to be selected
     * 		in an assorted fashion. {@code false}, otherwise.
     */
    public boolean isAssorted() {
        return assorted;
    }

    /**
     * Configures if the transactions are going to be created in an assorted fashion or not.
     *
     * @param assorted
     * 		If the transactions from {@link #sequential} are going to be selected
     * 		in an assorted fashion or not.
     */
    public void setAssorted(final boolean assorted) {
        this.assorted = assorted;
    }

    /**
     * @return The number of smart contracts that should be created during the execution
     * 		of the transactions
     */
    public long getTotalSmartContractCreations() {
        return computeTotalAmountOfTransactionsFromType(TYPE_VIRTUAL_MERKLE_CREATE_SMART_CONTRACT);
    }

    /**
     * @return The number of smart contract method executions.
     */
    public long getTotalMethodExecutions() {
        return computeTotalAmountOfTransactionsFromType(TYPE_VIRTUAL_MERKLE_SMART_CONTRACT_METHOD_EXECUTION);
    }

    /**
     * @return The number accounts that should be created during the execution
     * 		of the transactions
     */
    public long getTotalAccountCreations() {
        return computeTotalAmountOfTransactionsFromType(TYPE_VIRTUAL_MERKLE_CREATE);
    }

    /**
     * @return The maximum number of smart contract key value pairs that can be created
     * 		during the execution of a test using this configuration.
     */
    public long getMaximumNumberOfKeyValuePairsCreation() {
        if (smartContractConfig == null) {
            return 0;
        }
        final long totalSmartContractCreations = getTotalSmartContractCreations();
        final long totalMethodExecutions = getTotalMethodExecutions();

        final SmartContractConfig smartContractConfig = getSmartContractConfig();

        return totalSmartContractCreations * smartContractConfig.getMaxKeyValuePairsDuringCreation()
                + totalMethodExecutions * smartContractConfig.getAddsDuringMethodExecution();
    }

    private long computeTotalAmountOfTransactionsFromType(final PAYLOAD_TYPE transactionType) {
        return sequential.stream()
                .filter(config -> config.getType().equals(transactionType))
                .map(TransactionRequestConfig::getAmount)
                .reduce(0L, Long::sum);
    }

    /**
     * @return The configuration for smart contract specific transactions.
     */
    public SmartContractConfig getSmartContractConfig() {
        return smartContractConfig;
    }

    /**
     * Sets the configuration for smart contract specific transactions.
     *
     * @param smartContractConfig
     * 		The configuration for smart contract specific transactions.
     */
    public void setSmartContractConfig(final SmartContractConfig smartContractConfig) {
        this.smartContractConfig = smartContractConfig;
    }

    /**
     * @return A long value to be used as the first id of the generated accounts.
     */
    public long getFirstAccountId() {
        return firstAccountId;
    }

    /**
     * Sets the {@code firstAccountId} to be used as the first id of the generated accounts.
     *
     * @param firstAccountId
     * 		A long value to be used as the first id of the generated accounts.
     */
    public void setFirstAccountId(final long firstAccountId) {
        this.firstAccountId = firstAccountId;
    }

    /**
     * @return A long value to be used as the first id of the generated smart contracts.
     */
    public long getFirstSmartContractId() {
        return firstSmartContractId;
    }

    /**
     * Sets the {@code firstSmartContractId} to be used as the first id of the generated smart contracts.
     *
     * @param firstSmartContractId
     * 		A long value to be used as the first id of the generated smart contracts.
     */
    public void setFirstSmartContractId(final long firstSmartContractId) {
        this.firstSmartContractId = firstSmartContractId;
    }

    public boolean isBypassDeleteTransaction() {
        return bypassDeleteTransaction;
    }

    public void setBypassDeleteTransaction(boolean bypassDeleteTransaction) {
        this.bypassDeleteTransaction = bypassDeleteTransaction;
    }
}
