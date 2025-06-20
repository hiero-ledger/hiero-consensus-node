// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.utility.throttle.Throttle;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class SubmitConfig {
    private static final Logger logger = LogManager.getLogger(PlatformTestingToolMain.class);
    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");

    private TransactionSubmitter.SUBMIT_GOAL systemMetric = TransactionSubmitter.SUBMIT_GOAL.BYTES_PER_SECOND_PER_NODE;
    private float metricThreshold = 1000;
    private long pauseAfter = 0;
    private int pauseSeconds = 15;
    private boolean allowRunSubmit = true;
    private int C2CDelayThreshold = 5;

    private boolean singleNodeSubmit = false; // only node 0 can submit transactions
    private boolean submitInTurn = false; // each node submit transactions in turn
    private int inTurnIntervalSecond = 0; // interval in seconds between node turns

    private boolean enableThrottling = false; // default to be false to be backward compatible

    /**
     * Once all transactions have been submitted, sleep for this many milliseconds before calling {@link
     * System#exit(int)}.
     */
    private int sleepAfterTestMs = 90_000;

    private Map<String, String> tpsMap = null;
    private Map<PAYLOAD_TYPE, Throttle> throttleForTxTypes = null;
    private Map<String, String> categoryTpsMap = null;
    private Map<PAYLOAD_CATEGORY, Throttle> throttleForTxCategory = null;

    public Map<String, String> getTpsMap() {
        return tpsMap;
    }

    public void setTpsMap(Map<String, String> tpsMap) {
        this.tpsMap = tpsMap;
    }

    public Map<String, String> getCategoryTpsMap() {
        return categoryTpsMap;
    }

    public void setCategoryTpsMap(Map<String, String> categoryTpsMap) {
        this.categoryTpsMap = categoryTpsMap;
    }

    public TransactionSubmitter.SUBMIT_GOAL getSystemMetric() {
        return systemMetric;
    }

    public void setSystemMetric(TransactionSubmitter.SUBMIT_GOAL systemMetric) {
        this.systemMetric = systemMetric;
    }

    public float getMetricThreshold() {
        return metricThreshold;
    }

    public void setMetricThreshold(float metricThreshold) {
        this.metricThreshold = metricThreshold;
    }

    public long getPauseAfter() {
        return pauseAfter;
    }

    public void setPauseAfter(long pauseAfter) {
        this.pauseAfter = pauseAfter;
    }

    public int getPauseSeconds() {
        return pauseSeconds;
    }

    public void setPauseSeconds(int pauseSeconds) {
        this.pauseSeconds = pauseSeconds;
    }

    public boolean isAllowRunSubmit() {
        return allowRunSubmit;
    }

    public void setAllowRunSubmit(boolean allowRunSubmit) {
        this.allowRunSubmit = allowRunSubmit;
    }

    public int getC2CDelayThreshold() {
        return C2CDelayThreshold;
    }

    public void setC2CDelayThreshold(int c2cDelayThreshold) {
        C2CDelayThreshold = c2cDelayThreshold;
    }

    public boolean isSingleNodeSubmit() {
        return singleNodeSubmit;
    }

    public void setSingleNodeSubmit(boolean singleNodeSubmit) {
        this.singleNodeSubmit = singleNodeSubmit;
    }

    public boolean isSubmitInTurn() {
        return submitInTurn;
    }

    public void setSubmitInTurn(boolean submitInTurn) {
        this.submitInTurn = submitInTurn;
    }

    public int getInTurnIntervalSecond() {
        return inTurnIntervalSecond;
    }

    public void setInTurnIntervalSecond(int inTurnIntervalSecond) {
        this.inTurnIntervalSecond = inTurnIntervalSecond;
    }

    public boolean isEnableThrottling() {
        return enableThrottling;
    }

    public void setEnableThrottling(boolean enableThrottling) {
        this.enableThrottling = enableThrottling;
    }

    public int getSleepAfterTestMs() {
        return sleepAfterTestMs;
    }

    public void setSleepAfterTestMs(final int sleepAfterTestMs) {
        this.sleepAfterTestMs = sleepAfterTestMs;
    }

    public Map<PAYLOAD_TYPE, Throttle> getThrottleForTxTypes() {
        return throttleForTxTypes;
    }

    public Map<PAYLOAD_CATEGORY, Throttle> getThrottleForTxCategory() {
        return throttleForTxCategory;
    }

    public void initThrottling() {
        if (categoryTpsMap != null) {
            loadCategoryTpsMap();
        }

        if (tpsMap != null) {
            loadTpsMap();
        }
    }

    void loadCategoryTpsMap() {
        throttleForTxCategory = new HashMap<>(categoryTpsMap.size());
        categoryTpsMap.forEach((txCategoryName, tps) -> {
            try {
                PAYLOAD_CATEGORY txCategory = PAYLOAD_CATEGORY.valueOf(txCategoryName);
                Throttle throttle = new Throttle(Double.parseDouble(tps));
                throttleForTxCategory.put(txCategory, throttle);
            } catch (IllegalArgumentException ex) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Name {} is not a valid transaction category or {} is not a valid value.",
                        txCategoryName,
                        tps);
            }
        });
    }

    void loadTpsMap() {
        throttleForTxTypes = new HashMap<>(tpsMap.size());
        tpsMap.forEach((txTypeName, tps) -> {
            try {
                PAYLOAD_TYPE txType = PAYLOAD_TYPE.valueOf(txTypeName);
                Throttle throttle = new Throttle(Double.parseDouble(tps));
                throttleForTxTypes.put(txType, throttle);
            } catch (IllegalArgumentException ex) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Name {} is not a valid transaction type or {} is not a valid value.",
                        txTypeName,
                        tps);
            }
        });
    }
}
