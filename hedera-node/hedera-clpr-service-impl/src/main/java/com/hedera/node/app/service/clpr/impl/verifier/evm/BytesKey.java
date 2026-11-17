// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/**
 * Class wrapping a byte array so that it can be used as a key in a map.
 **/
record BytesKey(@NonNull byte[] bytes) {
    BytesKey {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof BytesKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
