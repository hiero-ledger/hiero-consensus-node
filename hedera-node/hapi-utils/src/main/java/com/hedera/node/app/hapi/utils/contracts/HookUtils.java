// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.contracts;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

public class HookUtils {
    /**
     * Returns a minimal representation of the given bytes, stripping leading zeros.
     * @param bytes the bytes to strip leading zeros from
     * @return the minimal representation of the bytes, or an empty bytes if all bytes were stripped
     */
    public static Bytes minimalRepresentationOf(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        int i = 0;
        int n = (int) bytes.length();
        while (i < n && bytes.getByte(i) == 0) {
            i++;
        }
        if (i == n) {
            return com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY;
        } else if (i == 0) {
            return bytes;
        } else {
            return bytes.slice(i, n - i);
        }
    }

    /**
     * Returns the slot key for a mapping entry, given the left-padded mapping slot and the entry.
     * <p>
     * C.f. Solidity docs <a href="https://docs.soliditylang.org/en/latest/internals/layout_in_storage.html">here</a>.
     * @param leftPaddedMappingSlot the left-padded mapping slot
     * @param entry the mapping entry
     * @return the slot key for the mapping entry
     */
    public static Bytes slotKeyOfMappingEntry(
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes leftPaddedMappingSlot,
            @NonNull final LambdaMappingEntry entry) {
        final com.hedera.pbj.runtime.io.buffer.Bytes hK;
        if (entry.hasKey()) {
            hK = leftPad32(entry.keyOrThrow());
        } else {
            hK = keccak256DigestOf(entry.preimageOrThrow());
        }
        return keccak256DigestOf(hK.append(leftPaddedMappingSlot));
    }

    /**
     * Pads the given bytes to 32 bytes by left-padding with zeros.
     * @param bytes the bytes to pad
     * @return the left-padded bytes, or the original bytes if they are already 32 bytes long
     */
    public static Bytes leftPad32(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        requireNonNull(bytes);
        final int n = (int) bytes.length();
        if (n == 32) {
            return bytes;
        }
        final var padded = new byte[32];
        bytes.getBytes(0, padded, 32 - n, n);
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(padded);
    }
}
