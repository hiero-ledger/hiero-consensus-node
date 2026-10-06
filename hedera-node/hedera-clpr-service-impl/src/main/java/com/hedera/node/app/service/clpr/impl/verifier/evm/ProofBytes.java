// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.bouncycastle.crypto.digests.KeccakDigest;

/**
 * Small byte/hash helpers shared by the EVM proof verifiers (Merkle-Patricia traversal, account
 * and storage-value decoding). Kept in the shared {@code evm} package so the
 * verifiers can reuse them rather than each carrying a private copy.
 */
public final class ProofBytes {

    private static final String SOURCE = "ProofBytes";

    private ProofBytes() {}

    /** keccak-256 of {@code input}. */
    @NonNull
    public static byte[] keccak256(@NonNull final byte[] input) {
        final KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        final byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    /**
     * Returns a defensive copy of {@code bytes}, requiring it to be exactly {@code expectedLength} bytes.
     *
     * @throws IllegalArgumentException if the length differs
     */
    public static byte[] checkedCopy(final byte[] bytes, final int expectedLength, final String name) {
        requireLength(bytes, expectedLength, name);
        return bytes.clone();
    }

    /**
     * Asserts {@code bytes} is exactly {@code expectedLength} bytes, without copying.
     * Use when only the length needs validating; use {@link #checkedCopy} when a
     * defensive copy is also required.
     *
     * @throws IllegalArgumentException if the length differs
     */
    public static void requireLength(final byte[] bytes, final int expectedLength, final String name) {
        Objects.requireNonNull(bytes, name);
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException(name + " must be " + expectedLength + " bytes, got " + bytes.length);
        }
    }

    /**
     * Left-pads {@code value} to 32 bytes with leading zeros.
     *
     * @throws ProofException if {@code value} is longer than 32 bytes
     */
    public static byte[] leftPad32(final byte[] value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length > 32) {
            throw new ProofException(SOURCE, name + " is longer than 32 bytes (" + value.length + ")");
        }
        final byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 32 - value.length, value.length);
        return out;
    }

    /**
     * The 32-byte storage slot key (18, left-padded) holding a ClprService contract's
     * {@code ClprEndpointManifest} commitment ({@code keccak256} of the protobuf preimage). Slot 18 =
     * {@code EndpointManifestState} base (17) + {@code commitment} field offset (1). Shared across all EVM
     * chains: they deploy the same {@code ClprService} layout and their verifiers extend the common
     * {@code ClprEvmBundleVerifier}, which pins {@code ENDPOINT_MANIFEST_COMMITMENT_SLOT = 18} (confirmed
     * against clpr-smart-contracts {@code storage-layout.json}, SC-189). Returns a fresh array each call.
     */
    @NonNull
    public static byte[] endpointManifestCommitmentSlot() {
        return leftPad32(new byte[] {18}, "endpointManifestCommitmentSlot");
    }

    /** Expands a byte array into its nibbles, high nibble first (e.g. {@code 0xAB → [0xA, 0xB]}). */
    @NonNull
    public static int[] toNibbles(@NonNull final byte[] bytes) {
        final int[] out = new int[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = (bytes[i] >>> 4) & 0x0f;
            out[i * 2 + 1] = bytes[i] & 0x0f;
        }
        return out;
    }
}
