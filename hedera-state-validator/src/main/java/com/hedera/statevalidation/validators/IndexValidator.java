// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

/**
 * Marker interface for validators that can process individual indices (like path).
 */
public interface IndexValidator extends Validator {
    void processIndex(long index);
}
