// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.stats;

/**
 * An interface that helps gather statistics about the reconnect of tree-like data structures, such as VirtualMaps.
 * <p>
 * An implementation could gather aggregate statistics for all maps, or it could gather the counters for a specific
 * map and then also optionally delegate to another instance of the interface that would compute aggregate stats
 * for all maps.
 * <p>
 * All the methods have default no-op implementations to help with stubbing of the instances until an implementation
 * is ready, or in tests.
 */
public interface ReconnectMapStats {
    /**
     * Increment a transfers from teacher counter.
     * <p>
     * Different reconnect algorithms may define the term "transfer" differently. Examples of a transfer from teacher: <br>
     * * a lesson from the teacher, <br>
     * * a response from the teacher per a prior request from the learner.
     */
    default void incrementTransfersFromTeacher() {}

    /**
     * Increment a transfers from learner counter.
     * <p>
     * Different reconnect algorithms may define the term "transfer" differently. Examples of a transfer from learner: <br>
     * * a query response to the teacher for a single hash, <br>
     * * a request from the learner.
     */
    default void incrementTransfersFromLearner() {}

    /**
     * Gather stats about internal nodes hashes transfers.
     * @param hashNum the number of hashes of internal nodes transferred
     * @param cleanHashNum the number of hashes transferred unnecessarily because they were clean
     */
    default void incrementInternalHashes(int hashNum, int cleanHashNum) {}

    /**
     * Gather stats about internal nodes data transfers.
     * @param dataNum the number of data payloads of internal nodes transferred (for non-VirtualMap trees)
     * @param cleanDataNum the number of data payloads transferred unnecessarily because they were clean
     */
    default void incrementInternalData(int dataNum, int cleanDataNum) {}

    /**
     * Gather stats about leaf nodes hashes transfers.
     * @param hashNum the number of hashes of leaf nodes transferred
     * @param cleanHashNum the number of hashes transferred unnecessarily because they were clean
     */
    default void incrementLeafHashes(int hashNum, int cleanHashNum) {}

    /**
     * Gather stats about leaf nodes data transfers.
     * @param dataNum the number of data payloads of leaf nodes transferred
     * @param cleanDataNum the number of data payloads transferred unnecessarily because they were clean
     */
    default void incrementLeafData(int dataNum, int cleanDataNum) {}

    /**
     * Formats a string with all the accumulated stats and any other useful information
     * maintained by the implementation of this interface, such as the map name and similar.
     * @return a string with the stats, useful for e.g. logging
     */
    default String format() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
