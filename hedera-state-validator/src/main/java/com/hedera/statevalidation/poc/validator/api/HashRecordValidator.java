// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator.api;

import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Marker interface for validators that can process virtual hash records
 * to validate internal indexes.
 */
public interface HashRecordValidator extends Validator {
    void processHashRecord(@NonNull VirtualHashRecord virtualHashRecord);
}
