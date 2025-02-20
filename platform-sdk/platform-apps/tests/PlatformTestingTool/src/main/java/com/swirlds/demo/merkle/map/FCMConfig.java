// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_FCM_CREATE;
import static com.swirlds.demo.platform.PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.Crypto;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.FCQ;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.VIRTUAL_MERKLE_ACCOUNT;

import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.virtualmerkle.config.TransactionRequestConfig;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class FCMConfig implements Serializable {
    private boolean disableExpectedMap = false;
    private boolean sequentialTest = true;
    private ArrayList<Long>[] nodeList = null;
    private ArrayList<Long>[] tpsList = null;
    private FCMQueryConfig fcmQueryConfig;

    private FCMSequential[] sequentials;

    /**
     * The fraction of NFTs to track (out of 1.0).
     * Using values that are not small may have significant performance impacts.
     */
    private double nftTrackingFraction = 0.0001;

    /** update in millisecond */
    private int csvUpdatePeriod = 1000;

    /**
     * Expected amount of create transactions for each EntityType to be submitted by each weighted node
     */
    private Map<EntityType, Integer> expectedEntityAmountForTypePerNode;

    /**
     * Expected total amount of create transactions to be submitted by each weighted node
     */
    private int expectedEntityAmountTotalPerNode;

    /**
     * define how many records we shall insert into FCQ when handling TYPE_FCM_CREATE_FCQ_WITH_RECORDS
     */
    private int initialRecordNum;

    public int getInitialRecordNum() {
        return initialRecordNum;
    }

    public void setInitialRecordNum(int initialRecordNum) {
        this.initialRecordNum = initialRecordNum;
    }

    public boolean isSequentialTest() {
        return sequentialTest;
    }

    public void setSequentialTest(boolean sequentialTest) {
        this.sequentialTest = sequentialTest;
    }

    public boolean isDisableExpectedMap() {
        return disableExpectedMap;
    }

    public void setDisableExpectedMap(final boolean disableExpectedMap) {
        this.disableExpectedMap = disableExpectedMap;
    }

    public int getCsvUpdatePeriod() {
        return csvUpdatePeriod;
    }

    public void setCsvUpdatePeriod(int csvUpdatePeriod) {
        this.csvUpdatePeriod = csvUpdatePeriod;
    }

    public long getTranAmountByType(PAYLOAD_TYPE type) {
        if (sequentials == null) {
            return 0;
        } else {
            return Arrays.stream(sequentials)
                    .filter(o -> o.getSequentialType() == type)
                    .mapToInt(FCMSequential::getSequentialAmount)
                    .sum();
        }
    }

    public ArrayList<Long>[] getNodeList() {
        return nodeList;
    }

    public void setNodeList(ArrayList<Long>[] nodeList) {
        this.nodeList = nodeList;
    }

    public ArrayList<Long>[] getTpsList() {
        return tpsList;
    }

    public void setTpsList(ArrayList<Long>[] tpsList) {
        this.tpsList = tpsList;
    }

    public FCMSequential[] getSequentials() {
        if (sequentials == null) {
            sequentials = new FCMSequential[0];
        }

        return sequentials;
    }

    public void setSequentials(FCMSequential[] sequentials) {
        this.sequentials = sequentials;
    }

    public FCMQueryConfig getFcmQueryConfig() {
        return fcmQueryConfig;
    }

    public void setFcmQueryConfig(final FCMQueryConfig fcmQueryConfig) {
        this.fcmQueryConfig = fcmQueryConfig;
    }

    /**
     * Load sequential configs, and calculate expected amount of each EntityType to be created by each node
     */
    public void loadSequentials() {
        expectedEntityAmountForTypePerNode = new HashMap<>();

        if (this.sequentials == null || this.sequentials.length < 1) {
            return;
        }
        for (final FCMSequential sequential : this.sequentials) {
            // add expected entity amount in expectedEntityAmountPerNode
            addExpectedEntityAmount(sequential.getSequentialType(), sequential.getSequentialAmount());
        }
    }

    /**
     * Check if payloadType is for Creating entities;
     * if so, add amount to corresponding EntityType's expected total amount
     *
     * @param payloadType
     * @param amount
     */
    public void addExpectedEntityAmount(final PAYLOAD_TYPE payloadType, final int amount) {
        if (!payloadType.name().contains("CREATE")) {
            return;
        }
        EntityType entityType = null;
        if (payloadType.name().contains("FCQ")) {
            entityType = FCQ;
        } else if (payloadType.equals(TYPE_FCM_CREATE)) {
            entityType = Crypto;
        } else if (payloadType.equals(TYPE_VIRTUAL_MERKLE_CREATE)) {
            entityType = VIRTUAL_MERKLE_ACCOUNT;
        } else {
            return;
        }
        int currentAmount = expectedEntityAmountForTypePerNode.getOrDefault(entityType, 0);
        expectedEntityAmountForTypePerNode.put(entityType, currentAmount + amount);
        expectedEntityAmountTotalPerNode += amount;
    }

    /**
     * Adds the maximum number of created accounts at a given time
     * for virtual merkle tests inside the expected map.
     *
     * @param virtualMerkleConfig
     * 		The configuration for virtual merkle tests.
     */
    public void loadVirtualMerkleSequentials(final VirtualMerkleConfig virtualMerkleConfig) {
        if (virtualMerkleConfig == null) {
            return;
        }
        long maxAccounts = 0;
        long accumulatedAccounts = 0;
        for (final TransactionRequestConfig t : virtualMerkleConfig.getSequential()) {
            if (t.getType() == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE) {
                accumulatedAccounts += t.getAmount();
                maxAccounts = Math.max(maxAccounts, accumulatedAccounts);
            } else if (t.getType() == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_DELETE) {
                accumulatedAccounts -= t.getAmount();
            }
        }

        // although maxAccounts can only fit on an long, the result of
        // (maxAccounts * virtualMerkleConfig.getSamplingProbability() + 1) must fit on an integer.
        final int expectedNumberOfEntities = (int) (maxAccounts * virtualMerkleConfig.getSamplingProbability() + 1);
        addExpectedEntityAmount(PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE, expectedNumberOfEntities);
    }

    /**
     * get expected amount of given entityType to be created by each node
     *
     * @param entityType
     * @return
     */
    public int getExpectedEntityAmountForTypePerNode(final EntityType entityType) {
        return expectedEntityAmountForTypePerNode.getOrDefault(entityType, 0);
    }

    /**
     * get expected amount of all entities to be created by each node
     */
    public int getExpectedEntityAmountTotalPerNode() {
        return expectedEntityAmountTotalPerNode;
    }

    public PAYLOAD_TYPE getSequentialType(int index) {
        if (index >= sequentials.length) {
            return PAYLOAD_TYPE.TYPE_RANDOM_BYTES;
        } else {
            return sequentials[index].getSequentialType();
        }
    }

    public int getSequentialSize(int index) {
        if (index >= sequentials.length) {
            return 100;
        } else {
            return sequentials[index].getSequentialSize();
        }
    }

    public int getSequentialAmount(int index) {
        if (index >= sequentials.length) {
            return 1;
        } else {
            return sequentials[index].getSequentialAmount();
        }
    }

    /**
     * Get the fraction of NFTs that are tracked and validated.
     */
    public double getNftTrackingFraction() {
        return nftTrackingFraction;
    }

    /**
     * Set the fraction of NFTs that are tracked and validated.
     */
    public void setNftTrackingFraction(final double nftTrackingFraction) {
        this.nftTrackingFraction = nftTrackingFraction;
    }

    public static class FCMQueryConfig {
        private int qps;
        private int numberOfThreads = 1;

        public int getQps() {
            return qps;
        }

        public void setQps(final int tps) {
            this.qps = tps;
        }

        public int getNumberOfThreads() {
            return numberOfThreads;
        }

        public void setNumberOfThreads(final int numberOfThreads) {
            this.numberOfThreads = numberOfThreads;
        }
    }
}
