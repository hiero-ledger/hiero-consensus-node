// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Low-level parsing helpers shared between the EVM-callable {@code verifyConfig} and
 * {@code verifyBundle} system-contract methods.
 *
 * <p>Pure protobuf scaffolding for walking a {@code StateProof} → {@code StateItem} →
 * {@code StateValue} chain to extract the wrapped domain value bytes. Kept in this module
 * so the verify system-contract calls do not need a cross-module dependency on
 * {@code hedera-clpr-service-impl}.
 *
 * <p>(FUTURE) Hoist into {@code hapi-utils} so the verifier-contract precompile and any
 * other consumer can share it.
 */
final class ClprProofExtraction {
    /** StateItem field 3 (value) wire tag for length-delimited. */
    static final int SI_VALUE_TAG = 0x1A;

    /** StateValue field 60 (clpr channel) length-delimited tag. */
    static final int SV_CHANNEL_TAG = 482;

    /** StateValue field 62 (clpr message) length-delimited tag. */
    static final int SV_MESSAGE_TAG = 498;

    /**
     * StateValue field 66 (clpr endpoint manifest singleton) length-delimited tag. */
    static final int SV_ENDPOINT_MANIFEST_TAG = 530;

    private ClprProofExtraction() {}

    /**
     * Returns the first {@code state_item_leaf} bytes found in the proof's paths, or null.
     */
    @Nullable
    static Bytes findFirstStateItemLeafBytes(@NonNull final StateProof proof) {
        for (final var path : proof.paths()) {
            if (path.hasStateItemLeaf()) {
                return path.stateItemLeaf();
            }
        }
        return null;
    }

    /**
     * Parses a StateItem protobuf and returns the value-field bytes, or null on failure.
     */
    @Nullable
    static Bytes extractStateItemValue(@NonNull final Bytes stateItemBytes) {
        try {
            final byte[] raw = stateItemBytes.toByteArray();
            int pos = 0;
            Bytes valueBytes = null;
            while (pos < raw.length) {
                final int[] tagResult = readVarint(raw, pos);
                if (tagResult == null) break;
                final int tag = tagResult[0];
                pos = tagResult[1];
                final int[] lenResult = readVarint(raw, pos);
                if (lenResult == null) break;
                final int len = lenResult[0];
                pos = lenResult[1];
                if (pos + len > raw.length) break;
                if (tag == SI_VALUE_TAG) {
                    valueBytes = Bytes.wrap(raw, pos, len);
                }
                pos += len;
            }
            return valueBytes;
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Reads the first varint tag from the given bytes; returns -1 on failure.
     */
    static int readFirstVarintTag(@NonNull final Bytes bytes) {
        try {
            final int[] result = readVarint(bytes.toByteArray(), 0);
            return result != null ? result[0] : -1;
        } catch (final Exception e) {
            return -1;
        }
    }

    /**
     * Given the bytes of a StateValue whose first field wraps a single domain value
     * (tag-length-bytes), skips the tag, reads the length, returns the value bytes.
     */
    @Nullable
    static Bytes unwrapStateValueField(@NonNull final Bytes stateValueBytes) {
        try {
            final byte[] raw = stateValueBytes.toByteArray();
            final int[] tagResult = readVarint(raw, 0);
            if (tagResult == null) return null;
            int pos = tagResult[1];
            final int[] lenResult = readVarint(raw, pos);
            if (lenResult == null) return null;
            pos = lenResult[1];
            final int len = lenResult[0];
            if (pos + len > raw.length) return null;
            return Bytes.wrap(raw, pos, len);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Decodes a single protobuf varint starting at {@code offset} in {@code data}, capped
     * at 5 bytes (large enough for any 32-bit value, which is the only thing we read here:
     * proto field tags and length prefixes for the state-value structures we care about).
     * Returns {@code {value, newOffset}} on success, or {@code null} if the varint is
     * truncated or would exceed 5 bytes — both cases mean the surrounding state-item bytes
     * are malformed and the caller should bail out of extraction.
     */
    @Nullable
    private static int[] readVarint(final byte[] data, int offset) {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5 && offset + i < data.length; i++) {
            final int b = data[offset + i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[] {value, offset + i + 1};
            }
            shift += 7;
        }
        return null;
    }
}
