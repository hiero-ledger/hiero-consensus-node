// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is used when the active states in memory exceed one of the configured thresholds.
 */
public class ActiveStateThresholdPayload extends AbstractLogPayload {

    private String thresholdCategory;
    private double currentValue;
    private double maximumAllowable;

    public ActiveStateThresholdPayload() {}

    public enum ThresholdCategory {
        /**
         * A limit on the total number of State objects in memory.
         */
        TOTAL_NUMBER_OF_STATES,
        /**
         * A limit on the maximum age of a State in memory.
         */
        MAXIMUM_STATE_AGE
    }

    /**
     * @param message
     * 		a human readable message
     * @param thresholdCategory
     * 		a string describing the threshold that was violated
     * @param currentValue
     * 		the value of the metric that exceeded its threshold
     * @param maximumAllowable
     * 		the value of the threshold that was violated
     */
    public ActiveStateThresholdPayload(
            final String message,
            final String thresholdCategory,
            final double currentValue,
            final double maximumAllowable) {
        super(message);

        this.thresholdCategory = thresholdCategory;
        this.currentValue = currentValue;
        this.maximumAllowable = maximumAllowable;
    }

    /**
     * Get the name of the threshold that was violated.
     */
    public String getThresholdCategory() {
        return thresholdCategory;
    }

    /**
     * Set the name of the threshold that was violated.
     */
    public void setThresholdCategory(final String thresholdCategory) {
        this.thresholdCategory = thresholdCategory;
    }

    /**
     * Get the current value which exceeds the threshold.
     */
    public double getCurrentValue() {
        return currentValue;
    }

    /**
     * Set the current value which exceeds the threshold.
     */
    public void setCurrentValue(final double currentValue) {
        this.currentValue = currentValue;
    }

    /**
     * Get the configured threshold value.
     */
    public double getMaximumAllowable() {
        return maximumAllowable;
    }

    /**
     * Set the configured threshold value.
     */
    public void setMaximumAllowable(final double maximumAllowable) {
        this.maximumAllowable = maximumAllowable;
    }
}
