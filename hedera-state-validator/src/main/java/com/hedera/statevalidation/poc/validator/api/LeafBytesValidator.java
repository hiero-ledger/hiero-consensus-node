// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator.api;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface LeafBytesValidator extends Validator {
    void processLeafBytes(long dataLocation, @NonNull VirtualLeafBytes leafBytes);
}
