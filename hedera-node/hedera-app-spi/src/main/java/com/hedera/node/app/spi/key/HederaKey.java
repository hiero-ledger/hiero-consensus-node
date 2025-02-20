// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

/** Placeholder implementation for moving JKey */
public interface HederaKey {
    /** A key is empty if it is a key with no bytes or a key list with nothing in it */
    boolean isEmpty();
}
