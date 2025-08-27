// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import org.hiero.consensus.model.node.NodeId;

/**
 * Thrown when an issue occurs while loading keys from pfx files
 */
public class KeyLoadingException extends Exception {
    public KeyLoadingException(final String message) {
        super(message);
    }

    public KeyLoadingException(final String message, final KeyCertPurpose type, final NodeId id) {
        super(message + " Missing:" + type.storeName(id));
    }

    public KeyLoadingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
