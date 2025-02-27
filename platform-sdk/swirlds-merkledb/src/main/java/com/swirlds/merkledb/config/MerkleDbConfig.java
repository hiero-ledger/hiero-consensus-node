// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.api.validation.annotation.Positive;
import com.swirlds.config.extensions.validators.DefaultConfigViolation;

/**
 * Instance-wide config for {@code MerkleDbDataSource}.
 *
 * @param maxNumOfKeys
 * 		Get the maximum number of unique keys we expect to be stored in this database. This is used for
 * 		calculating in memory index sizes. IMPORTANT: This can only be set before a new database is created, changing
 * 		on an existing database will break it.
 * @param hashesRamToDiskThreshold
 * 		Get threshold where we switch from storing node hashes in ram to
 * 		storing them on disk. If it is 0 then everything is on disk, if it is Long.MAX_VALUE then everything is in ram.
 * 		Any value in the middle is the path value at
 * 		which we swap from ram to disk. This allows a tree where the lower levels of the tree nodes hashes are in ram
 * 		and the upper larger less changing layers are on disk. IMPORTANT: This can only be set before a new database is
 * 		created, changing on an existing database will break it.
 * @param minNumberOfFilesInCompaction
 * 		The minimum number of files before we do a compaction. If there are less than this number then it is
 * 		acceptable to not do a compaction.
 * @param iteratorInputBufferBytes
 *      Size of buffer used by data file iterators, in bytes.
 * @param reconnectKeyLeakMitigationEnabled
 * 		There currently exists a bug when a virtual map is reconnected that can
 * 		cause some deleted keys to leak into the datasource. If this method returns true then a mitigation strategy is
 * 		used when a leaked key is encountered, which hides the problem from the perspective of the application. This
 * 		setting exists so that we can test behavior with and without this mitigation enabled. This mitigation should
 * 		always be enabled in production environments.
 * @param indexRebuildingEnforced
 * 		Configuration used to avoid reading stored indexes from a saved state and enforce rebuilding those indexes from
 * 		data files.
 * @param percentHalfDiskHashMapFlushThreads
 *      Percentage, from 0.0 to 100.0, of available processors to use for half disk hash map background flushing
 *      threads.
 * @param numHalfDiskHashMapFlushThreads
 *      Number of threads to use for half disk hash map background flushing. If set to a negative value, the number of
 *      threads to use is calculated based on {@link #percentHalfDiskHashMapFlushThreads}
 * @param reservedBufferLengthForLeafList
 *      Length of a reserved buffer in a LongList used to store leafs. Value in bytes.
 * @param leafRecordCacheSize
 *      Cache size in bytes for reading virtual leaf records. Initialized in data source creation time from MerkleDb config.
 *      If the value is zero, leaf records cache isn't used.
 * @param maxFileChannelsPerFileReader
 *     Maximum number of file channels per file reader.
 * @param maxThreadsPerFileChannel
 *    Maximum number of threads per file channel.
 */
@ConfigData("merkleDb")
public record MerkleDbConfig(
        @Positive @ConfigProperty(defaultValue = "500000000") long maxNumOfKeys,
        @Min(0) @ConfigProperty(defaultValue = "8388608") long hashesRamToDiskThreshold,
        @Min(1) @ConfigProperty(defaultValue = "3") int compactionThreads,
        @ConstraintMethod("minNumberOfFilesInCompactionValidation") @ConfigProperty(defaultValue = "8")
                int minNumberOfFilesInCompaction,
        @Min(3) @ConfigProperty(defaultValue = "5") int maxCompactionLevel,
        /* FUTURE WORK - https://github.com/hashgraph/hedera-services/issues/5178 */
        @Positive @ConfigProperty(defaultValue = "16777216") int iteratorInputBufferBytes,
        @ConfigProperty(defaultValue = "false") boolean reconnectKeyLeakMitigationEnabled,
        @ConfigProperty(defaultValue = "false") boolean indexRebuildingEnforced,
        @ConfigProperty(defaultValue = "75.0") double percentHalfDiskHashMapFlushThreads,
        @ConfigProperty(defaultValue = "-1") int numHalfDiskHashMapFlushThreads,
        @ConfigProperty(defaultValue = "262144") int reservedBufferLengthForLeafList,
        @ConfigProperty(defaultValue = "1048576") int leafRecordCacheSize,
        @Min(1) @ConfigProperty(defaultValue = "8") int maxFileChannelsPerFileReader,
        @Min(1) @ConfigProperty(defaultValue = "8") int maxThreadsPerFileChannel) {

    static double UNIT_FRACTION_PERCENT = 100.0;

    public ConfigViolation minNumberOfFilesInCompactionValidation(final Configuration configuration) {
        final long minNumberOfFilesInCompaction =
                configuration.getConfigData(MerkleDbConfig.class).minNumberOfFilesInCompaction();
        if (minNumberOfFilesInCompaction < 2) {
            return new DefaultConfigViolation(
                    "minNumberOfFilesInCompaction",
                    "%d".formatted(minNumberOfFilesInCompaction),
                    true,
                    "Cannot configure minNumberOfFilesInCompaction to " + minNumberOfFilesInCompaction
                            + ", it must be >= 2");
        }
        return null;
    }

    public int getNumHalfDiskHashMapFlushThreads() {
        final int numProcessors = Runtime.getRuntime().availableProcessors();
        final int threads = (numHalfDiskHashMapFlushThreads() == -1)
                ? (int) (numProcessors * (percentHalfDiskHashMapFlushThreads() / UNIT_FRACTION_PERCENT))
                : numHalfDiskHashMapFlushThreads();
        return Math.max(1, threads);
    }
}
