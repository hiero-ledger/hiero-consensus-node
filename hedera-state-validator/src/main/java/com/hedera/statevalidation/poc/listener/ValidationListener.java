// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.listener;

import com.hedera.statevalidation.poc.util.ValidationException;

public interface ValidationListener {

    default void onValidationStarted(String tag) {}

    default void onValidationCompleted(String tag) {}

    default void onValidationFailed(ValidationException error) {}
}
