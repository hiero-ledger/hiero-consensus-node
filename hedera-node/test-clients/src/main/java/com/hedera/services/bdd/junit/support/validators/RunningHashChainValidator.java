// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.stream.proto.RecordStreamFile;
import java.util.List;
import org.junit.jupiter.api.Assertions;

public class RunningHashChainValidator implements RecordStreamValidator {
    @Override
    public void validateFiles(final List<RecordStreamFile> files) {
        RecordStreamFile previous = null;
        for (final var file : files) {
            if (previous != null) {
                Assertions.assertEquals(
                        previous.getEndObjectRunningHash(),
                        file.getStartObjectRunningHash(),
                        String.format(
                                "Block %d startObjectRunningHash does not match endObjectRunningHash of preceding block %d",
                                file.getBlockNumber(), previous.getBlockNumber()));
                Assertions.assertEquals(
                        previous.getBlockNumber() + 1,
                        file.getBlockNumber(),
                        String.format(
                                "Block %d %s blockNumber is not one less than the next Block %d %s",
                                previous.getBlockNumber(),
                                previous.getStartObjectRunningHash(),
                                file.getBlockNumber(),
                                file.getStartObjectRunningHash()));
            }
            previous = file;
        }
    }
}
