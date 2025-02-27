// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This message is logged by the receiving synchronizer when synchronization has completed.
 */
public class SynchronizationCompletePayload extends AbstractLogPayload {

    private double timeInSeconds;
    private double hashTimeInSeconds;
    private double initializationTimeInSeconds;
    private int totalNodes;
    private int leafNodes;
    private int redundantLeafNodes;
    private int internalNodes;
    private int redundantInternalNodes;

    public SynchronizationCompletePayload() {}

    /**
     * @param message
     * 		the human readable message
     */
    public SynchronizationCompletePayload(final String message) {
        super(message);
    }

    /**
     * Get the time required to transmit the state over the network, measured in seconds.
     */
    public double getTimeInSeconds() {
        return timeInSeconds;
    }

    /**
     * Set the time required to transmit the state over the network, measured in seconds.
     *
     * @param timeInSeconds
     * 		the time required to transmit the state
     * @return this object
     */
    public SynchronizationCompletePayload setTimeInSeconds(double timeInSeconds) {
        this.timeInSeconds = timeInSeconds;
        return this;
    }

    /**
     * Get the time required to required to hash the state, in seconds.
     */
    public double getHashTimeInSeconds() {
        return hashTimeInSeconds;
    }

    /**
     * Set the time required to required to hash the state, in seconds.
     *
     * @param hashTimeInSeconds
     * 		the time required to hash the state
     * @return this object
     */
    public SynchronizationCompletePayload setHashTimeInSeconds(final double hashTimeInSeconds) {
        this.hashTimeInSeconds = hashTimeInSeconds;
        return this;
    }

    /**
     * Get the time required to required to initialize the state, in seconds.
     */
    public double getInitializationTimeInSeconds() {
        return initializationTimeInSeconds;
    }

    /**
     * Set the time required to required to initialize the state, in seconds.
     *
     * @param initializationTimeInSeconds
     * 		the time required to initialize the state
     * @return this object
     */
    public SynchronizationCompletePayload setInitializationTimeInSeconds(double initializationTimeInSeconds) {
        this.initializationTimeInSeconds = initializationTimeInSeconds;
        return this;
    }

    /**
     * Get the total number of merkel nodes that were sent during synchronization.
     */
    public int getTotalNodes() {
        return totalNodes;
    }

    /**
     * Set the total number of merkel nodes that were sent during synchronization.
     *
     * @param totalNodes
     * 		the number of nodes sent during synchronization
     * @return this object
     */
    public SynchronizationCompletePayload setTotalNodes(int totalNodes) {
        this.totalNodes = totalNodes;
        return this;
    }

    /**
     * Get the total number of merkel leaf nodes that were sent during synchronization.
     */
    public int getLeafNodes() {
        return leafNodes;
    }

    /**
     * Set the total number of merkel leaf nodes that were sent during synchronization.
     *
     * @param leafNodes
     * 		the number of leaf nodes sent during synchronization
     * @return this object
     */
    public SynchronizationCompletePayload setLeafNodes(int leafNodes) {
        this.leafNodes = leafNodes;
        return this;
    }

    /**
     * Get the total number of merkel leaf nodes that were sent unnecessarily during synchronization.
     */
    public int getRedundantLeafNodes() {
        return redundantLeafNodes;
    }

    /**
     * Set the total number of merkel leaf nodes that were sent unnecessarily during synchronization.
     *
     * @param redundantLeafNodes
     * 		the number of redundant leaf nodes sent
     * @return this object
     */
    public SynchronizationCompletePayload setRedundantLeafNodes(int redundantLeafNodes) {
        this.redundantLeafNodes = redundantLeafNodes;
        return this;
    }

    /**
     * Get the total number of internal merkle nodes that were sent during synchronization.
     */
    public int getInternalNodes() {
        return internalNodes;
    }

    /**
     * Set the total number of internal merkle nodes that were sent during synchronization.
     *
     * @param internalNodes
     * 		the number of internal nodes sent
     * @return this object
     */
    public SynchronizationCompletePayload setInternalNodes(int internalNodes) {
        this.internalNodes = internalNodes;
        return this;
    }

    /**
     * Get the total number of internal merkle nodes that were sent unnecessarily during synchronization.
     */
    public int getRedundantInternalNodes() {
        return redundantInternalNodes;
    }

    /**
     * Set the total number of internal merkle nodes that were sent unnecessarily during synchronization.
     *
     * @param redundantInternalNodes
     * 		the number of redundant internal nodes
     * @return this object
     */
    public SynchronizationCompletePayload setRedundantInternalNodes(int redundantInternalNodes) {
        this.redundantInternalNodes = redundantInternalNodes;
        return this;
    }
}
