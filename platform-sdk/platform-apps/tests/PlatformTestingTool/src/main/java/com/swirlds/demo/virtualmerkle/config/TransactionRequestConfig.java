// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.config;

import com.swirlds.demo.platform.HotspotConfiguration;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import java.util.Objects;

/**
 * This class has the responsibility to hold the {@code type} of a transaction and how many times
 * the transaction must be submitted.
 */
public final class TransactionRequestConfig {

    private PAYLOAD_TYPE type;

    private long amount;

    private HotspotConfiguration hotspot;

    public TransactionRequestConfig() {}

    public TransactionRequestConfig(TransactionRequestConfig source) {
        this.type = source.type;
        this.amount = source.amount;
        this.hotspot = source.hotspot;
    }

    /**
     * @return The type of the transaction from this request.
     */
    public PAYLOAD_TYPE getType() {
        return type;
    }

    /**
     * Sets the new type of the transaction for this request.
     *
     * @param type
     * 		The type of the transaction from this request.
     */
    public void setType(final PAYLOAD_TYPE type) {
        this.type = type;
    }

    /**
     * @return The amount of times this transaction must be submitted.
     */
    public long getAmount() {
        return amount;
    }

    /**
     * Sets the new amount of times this transaction must be submitted.
     *
     * @param amount
     * 		The amount of times this transaction must be submitted.
     */
    public void setAmount(final long amount) {
        this.amount = amount;
    }

    /**
     * Decrements and return the number of times this transaction must be submitted.
     *
     * @return The new number of times this transaction must be submitted.
     */
    public long decrementAndGetAmount() {
        return --amount;
    }

    /**
     * Get the hotspot configuration for this group of transactions, or null if there is no configured hotspot.
     *
     * @return hotspot configuration
     */
    public HotspotConfiguration getHotspot() {
        return hotspot;
    }

    /**
     * Set the hotspot configuration for this group of transactions.
     *
     * @param hotspot
     * 			hotspot configuration
     */
    public void setHotspot(final HotspotConfiguration hotspot) {
        this.hotspot = hotspot;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final TransactionRequestConfig that = (TransactionRequestConfig) other;
        return amount == that.amount && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, amount);
    }

    @Override
    public String toString() {
        return "TransactionRequestConfig{" + "type=" + type + ", amount=" + amount + '}';
    }
}
