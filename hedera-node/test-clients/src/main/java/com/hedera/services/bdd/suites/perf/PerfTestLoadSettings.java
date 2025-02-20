// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.perf;

import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiPropertySource;
import java.util.Arrays;

public class PerfTestLoadSettings {
    public static final int DEFAULT_TPS = 500;
    public static final int DEFAULT_MINS = 1;
    public static final int DEFAULT_THREADS = 50;
    public static final int DEFAULT_SUBMIT_MESSAGE_SIZE = 256;
    public static final int DEFAULT_SUBMIT_MESSAGE_SIZE_VAR = 64;
    // By default, it will fall back to original test scenarios
    public static final int DEFAULT_TOTAL_TEST_ACCOUNTS = 2;
    public static final int DEFAULT_TOTAL_TEST_TOPICS = 1;
    public static final int DEFAULT_TOTAL_TEST_TOKENS = 1;
    public static final int DEFAULT_TOTAL_TEST_TOKEN_ACCOUNTS = 2;
    public static final int DEFAULT_TEST_TREASURE_START_ACCOUNT = 1001;
    public static final int DEFAULT_TOTAL_TOKEN_ASSOCIATIONS = 0;
    public static final int DEFAULT_TOTAL_SCHEDULED = 0;
    public static final int DEFAULT_MEMO_LENGTH = 25;
    public static final int DEFAULT_DURATION_CREATE_TOKEN_ASSOCIATION = 60; // in seconds
    public static final int DEFAULT_DURATION_TOKEN_TRANSFER = 60; // in seconds
    public static final long DEFAULT_INITIAL_BALANCE = ONE_MILLION_HBARS;
    public static final int DEFAULT_TEST_TOPIC_ID = 30_000;
    public static final int DEFAULT_BALANCES_EXPORT_PERIOD_SECS = 60;
    public static final boolean DEFAULT_EXPORT_BALANCES_ON_CLIENT_SIDE = false;
    private static final int DEFAULT_NODE_TO_STAKE = 0;

    private int tps = DEFAULT_TPS;
    private int mins = DEFAULT_MINS;
    private int threads = DEFAULT_THREADS;
    private int hcsSubmitMessageSizeVar = DEFAULT_SUBMIT_MESSAGE_SIZE_VAR;

    /**
     * totalTestAccounts specifies how many Crypto accounts in the state file. All of them
     * participate random crypto transfer perf test
     */
    private int totalTestAccounts = DEFAULT_TOTAL_TEST_ACCOUNTS;

    /**
     * totalTestTopics specifies total topics in the state file. They are all used in random HCS
     * submitMessage perf test
     */
    private int totalTestTopics = DEFAULT_TOTAL_TEST_TOPICS;

    /** Initial balance for the Crypto accounts created during LoadTest */
    private long initialBalance = DEFAULT_INITIAL_BALANCE;

    private int nodeToStake = DEFAULT_NODE_TO_STAKE;
    private int[] extraNodesToStake = {};
    private HapiPropertySource ciProps = null;

    public PerfTestLoadSettings() {}

    public PerfTestLoadSettings(int tps, int mins, int threads) {
        this.tps = tps;
        this.mins = mins;
        this.threads = threads;
    }

    public long getInitialBalance() {
        return initialBalance;
    }

    public int getTps() {
        return tps;
    }

    public int getMins() {
        return mins;
    }

    public int getThreads() {
        return threads;
    }

    public int getHcsSubmitMessageSizeVar() {
        return hcsSubmitMessageSizeVar;
    }

    public int getTotalAccounts() {
        return totalTestAccounts;
    }

    public int getNodeToStake() {
        return nodeToStake;
    }

    public int[] getExtraNodesToStake() {
        return extraNodesToStake;
    }

    public int getTotalTopics() {
        return totalTestTopics;
    }

    public String getProperty(String property, String defaultValue) {
        if (null != ciProps && ciProps.has(property)) {
            return ciProps.get(property);
        }
        return defaultValue;
    }

    public int getIntProperty(String property, int defaultValue) {
        if (null != ciProps && ciProps.has(property)) {
            return ciProps.getInteger(property);
        }
        return defaultValue;
    }

    public boolean getBooleanProperty(String property, boolean defaultValue) {
        if (null != ciProps && ciProps.has(property)) {
            return ciProps.getBoolean(property);
        }
        return defaultValue;
    }

    public void setFrom(HapiPropertySource ciProps) {
        this.ciProps = ciProps;
        if (ciProps.has("tps")) {
            tps = ciProps.getInteger("tps");
        }
        if (ciProps.has("mins")) {
            mins = ciProps.getInteger("mins");
        }
        if (ciProps.has("threads")) {
            threads = ciProps.getInteger("threads");
        }
        if (ciProps.has("totalTestAccounts")) {
            totalTestAccounts = ciProps.getInteger("totalTestAccounts");
        }
        if (ciProps.has("totalTestTopics")) {
            totalTestTopics = ciProps.getInteger("totalTestTopics");
        }
        if (ciProps.has("messageSizeVar")) {
            hcsSubmitMessageSizeVar = ciProps.getInteger("messageSizeVar");
        }
        if (ciProps.has("initialBalance")) {
            initialBalance = ciProps.getLong("initialBalance");
        }
        if (ciProps.has("nodeToStake")) {
            nodeToStake = ciProps.getInteger("nodeToStake");
        }
        if (ciProps.has("extraNodesToStake")) {
            extraNodesToStake = Arrays.stream(ciProps.get("extraNodesToStake").split("[+]"))
                    .mapToInt(Integer::parseInt)
                    .toArray();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("tps", tps)
                .add("mins", mins)
                .add("threads", threads)
                .add("totalTestAccounts", totalTestAccounts)
                .add("totalTestTopics", totalTestTopics)
                .add("submitMessageSizeVar", hcsSubmitMessageSizeVar)
                .add("initialBalance", initialBalance)
                .add("nodeToStake", nodeToStake)
                .add("extraNodesToStake", extraNodesToStake)
                .toString();
    }
}
