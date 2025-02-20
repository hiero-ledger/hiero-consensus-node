// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.stream.proto.RecordStreamFile;
import java.util.List;
import org.junit.jupiter.api.Assertions;

public class BlockNoValidator implements RecordStreamValidator {
    @Override
    public void validateFiles(final List<RecordStreamFile> files) {
        var precedingBlockNo = -1L;
        for (final var file : files) {
            final var blockNo = file.getBlockNumber();
            Assertions.assertEquals(
                    precedingBlockNo + 1,
                    blockNo,
                    String.format(
                            "Block number %d is not the successor of preceding block number %d",
                            blockNo, precedingBlockNo));
            precedingBlockNo = blockNo;
        }
    }
}
