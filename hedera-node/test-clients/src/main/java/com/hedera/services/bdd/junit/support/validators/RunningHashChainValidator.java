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
            }
            previous = file;
        }
    }
}
