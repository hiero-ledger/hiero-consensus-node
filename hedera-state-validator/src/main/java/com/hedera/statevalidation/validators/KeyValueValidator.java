// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Marker interface for validators that can process key-value pairs.
 */
public interface KeyValueValidator extends Validator {
    void processKeyValue(Bytes keyBytes, Bytes valueBytes);
}
