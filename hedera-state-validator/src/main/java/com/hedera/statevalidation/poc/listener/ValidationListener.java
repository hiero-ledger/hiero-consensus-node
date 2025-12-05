// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.listener;

import com.hedera.statevalidation.poc.util.ValidationException;

/**
 * Listener for validation lifecycle events.
 *
 * <p><b>Thread Safety:</b> Implementations must be thread-safe as callbacks
 * may be invoked concurrently from multiple processor threads.
 */
public interface ValidationListener {

    default void onValidationStarted(String tag) {}

    default void onValidationCompleted(String tag) {}

    default void onValidationFailed(ValidationException error) {}
}
