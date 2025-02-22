// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged after a reconnect is completed.
 */
public class ReconnectDataUsagePayload extends AbstractLogPayload {

    private double dataMegabytes;

    public ReconnectDataUsagePayload() {}

    /**
     * @param message
     * 		the human readable message
     * @param dataMegabytes
     * 		the amount of data transmitted during execution of the reconnect
     */
    public ReconnectDataUsagePayload(final String message, final double dataMegabytes) {
        super(message);
        this.dataMegabytes = dataMegabytes;
    }

    public double getDataMegabytes() {
        return dataMegabytes;
    }

    public void setDataMegabytes(double dataMegabytes) {
        this.dataMegabytes = dataMegabytes;
    }
}
