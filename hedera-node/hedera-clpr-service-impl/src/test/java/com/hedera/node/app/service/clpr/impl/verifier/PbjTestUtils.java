// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

/**
 * Shared helpers for exercising PBJ {@code parseStrict} behavior in verifier tests.
 */
public final class PbjTestUtils {

    private PbjTestUtils() {}

    /**
     * Appends a protobuf record for field #255 (varint-encoded tag {@code 0xF8 0x0F}, wire type 0
     * varint, value 0) to the given serialized message. Field 255 is not defined by any of the
     * CLPR schemas, so any {@code parseStrict} call over the returned bytes will reject the input
     * as containing an unrecognized field.
     */
    public static byte[] appendUnknownField(final byte[] bytes) {
        final byte[] out = new byte[bytes.length + 3];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        out[out.length - 3] = (byte) 0xF8; // tag byte 1 (field #255, wire type 0)
        out[out.length - 2] = (byte) 0x0F; // tag byte 2 (varint continuation of field #255)
        out[out.length - 1] = (byte) 0x00; // varint value 0
        return out;
    }
}
