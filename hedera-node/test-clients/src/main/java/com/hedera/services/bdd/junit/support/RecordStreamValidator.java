// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.services.stream.proto.RecordStreamFile;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Stream;

public interface RecordStreamValidator {
    default Stream<Throwable> validationErrorsIn(@NonNull final StreamFileAccess.RecordStreamData data) {
        try {
            validateFiles(data.files());
            validateRecordsAndSidecars(data.records());
        } catch (final Throwable t) {
            return Stream.of(t);
        }
        return Stream.empty();
    }

    default void validateFiles(List<RecordStreamFile> files) {
        // No-op
    }

    default void validateRecordsAndSidecars(List<RecordWithSidecars> records) {
        // No-op
    }
}
