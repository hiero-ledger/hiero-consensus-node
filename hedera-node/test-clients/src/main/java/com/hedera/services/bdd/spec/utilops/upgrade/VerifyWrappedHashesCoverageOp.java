// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.junit.jupiter.api.Assertions;

/**
 * Asserts that the wrapped record hashes file on disk contains a contiguous
 * run of block numbers covering through the live-hash block number (the file
 * may extend further if disk writes continued after the freeze). Verifies that
 * disk writes remained enabled alongside live hash wrapping.
 */
public class VerifyWrappedHashesCoverageOp extends UtilOp {

    private final List<WrappedRecordFileBlockHashes> entries;
    private final String liveBlockNumStr;

    public VerifyWrappedHashesCoverageOp(
            @NonNull final List<WrappedRecordFileBlockHashes> entries, @NonNull final String liveBlockNumStr) {
        this.entries = requireNonNull(entries);
        this.liveBlockNumStr = requireNonNull(liveBlockNumStr);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final long liveBlockNum = Long.parseLong(liveBlockNumStr);

        Assertions.assertFalse(
                entries.isEmpty(),
                "Wrapped record hashes file is empty; expected contiguous entries through block " + liveBlockNum);

        final var blockNumbers = entries.stream()
                .map(WrappedRecordFileBlockHashes::blockNumber)
                .sorted()
                .toList();

        final long firstBlock = blockNumbers.getFirst();
        final long lastBlock = blockNumbers.getLast();
        for (int i = 0; i < blockNumbers.size(); i++) {
            final long expected = firstBlock + i;
            final long actual = blockNumbers.get(i);
            Assertions.assertEquals(
                    expected,
                    actual,
                    "Wrapped record hashes file has a gap at index " + i + ": expected block " + expected
                            + " but found block " + actual + " (range " + firstBlock + ".." + lastBlock + ")");
        }

        Assertions.assertTrue(
                firstBlock <= liveBlockNum && liveBlockNum <= lastBlock,
                "Wrapped record hashes file range " + firstBlock + ".." + lastBlock + " does not cover live-hash block "
                        + liveBlockNum);
        return false;
    }
}
