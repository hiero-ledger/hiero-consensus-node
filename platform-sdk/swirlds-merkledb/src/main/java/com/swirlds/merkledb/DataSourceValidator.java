// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.crypto.Hash;

// Note: This class is intended to be used with a human in the loop who is watching standard in and standard err.

/**
 * Validator to read a data source and all its data and check the complete data set is valid.
 */
public class DataSourceValidator {

    private static final String WHITESPACE = " ".repeat(20);

    /** The data source we are validating */
    private final MerkleDbDataSource dataSource;

    /** Current progress percentage we are tracking in the range of 0 to 20 */
    private int progressPercentage = 0;

    /**
     * Open the data source and validate all its data
     *
     * @param dataSource
     * 		The data source to validate
     */
    public DataSourceValidator(final MerkleDbDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Validate all data in the data source
     *
     * @return true if all data was valid
     */
    public boolean validate() {
        try {
            final long firstLeafPath = dataSource.getFirstLeafPath();
            final long lastLeafPath = dataSource.getLastLeafPath();
            final int leafCount = Math.toIntExact((lastLeafPath - firstLeafPath) + 1);
            // iterate over internal nodes and get them all
            System.out.printf("Validating %,d internal node hashes...%n", firstLeafPath);
            progressPercentage = 0;
            for (long path = 0; path < firstLeafPath; path++) {
                final Hash hash = dataSource.loadHash(path);
                assertTrue(hash != null, "internal record's hash for path [" + path + "] was null");
                printProgress(path, firstLeafPath);
            }
            System.out.println("All internal node hashes are valid :-)" + WHITESPACE);
            // iterate over leaf nodes and get them all
            System.out.printf("Validating %,d leaf hashes...%n", firstLeafPath);
            progressPercentage = 0;
            for (long path = firstLeafPath; path <= lastLeafPath; path++) {
                Hash leafHash = dataSource.loadHash(path);
                assertTrue(leafHash == null, "leaf record's hash for path [" + path + "] was not null");
                printProgress(path - firstLeafPath, leafCount);
            }
            System.out.println("All leaf hashes are null :-)" + WHITESPACE);
            System.out.printf("Validating %,d leaf record by path...%n", firstLeafPath);
            List<Bytes> keys = new ArrayList<>(leafCount);
            progressPercentage = 0;
            for (long path = firstLeafPath; path <= lastLeafPath; path++) {
                VirtualLeafBytes leaf = dataSource.loadLeafRecord(path);
                assertTrue(leaf != null, "leaf record for path [" + path + "] was null");
                assertTrue(
                        leaf.path() == path,
                        "leaf record for path [" + path + "] had a bad path [" + leaf.path() + "]");
                assertTrue(leaf.keyBytes().length() > 0, "leaf record's key for path [" + path + "] was empty");
                keys.add(leaf.keyBytes());
                printProgress(path - firstLeafPath, leafCount);
            }
            System.out.println("All leaf record by path are valid :-)" + WHITESPACE);
            System.out.printf("Validating %,d leaf record by key...%n", leafCount);
            progressPercentage = 0;
            for (int i = 0; i < keys.size(); i++) {
                VirtualLeafBytes leaf = dataSource.loadLeafRecord(keys.get(i));
                assertTrue(leaf != null, "leaf record for key [" + keys.get(i) + "] was null");
                assertTrue(leaf.keyBytes().length() > 0, "leaf record's key for key [" + keys.get(i) + "] was empty");
                assertTrue(
                        leaf.keyBytes().equals(keys.get(i)),
                        "leaf record's key for key [" + keys.get(i) + "] did not match, it was [" + leaf.keyBytes()
                                + "]");
                printProgress(i, keys.size());
            }
            System.out.println("All leaf record by key are valid :-)" + WHITESPACE);
            System.out.println("YAY all data is good!");
        } catch (final Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Check something is true and throw an error if not
     */
    private static void assertTrue(boolean testResult, String message) {
        if (!testResult) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Print nice progress messages in the form [%%%%%%%%%%		] 60%
     *
     * @param position
     * 		the current progress position between 0 and total
     * @param total
     * 		the position value for 100%
     */
    private void printProgress(long position, long total) {
        assert position >= 0 : "position [" + position + "] is < 0";
        assert total > 0 : "total [" + total + "] is <= 0";
        int newProgressPercentage = (int) (((double) position / (double) total) * 20);
        assert newProgressPercentage >= 0 : "newProgressPercentage [" + newProgressPercentage + "] is < 0";
        assert newProgressPercentage <= 20
                : "newProgressPercentage [" + newProgressPercentage + "] is > 20, " + "position="
                        + position
                        + ", total=" + total;
        if (newProgressPercentage > progressPercentage) {
            progressPercentage = newProgressPercentage;
            System.out.printf(
                    "[%s] %d%%, %,d of %,d\r",
                    ("#".repeat(newProgressPercentage)) + (" ".repeat(20 - newProgressPercentage)),
                    progressPercentage * 5,
                    position,
                    total);
        }
    }
}
