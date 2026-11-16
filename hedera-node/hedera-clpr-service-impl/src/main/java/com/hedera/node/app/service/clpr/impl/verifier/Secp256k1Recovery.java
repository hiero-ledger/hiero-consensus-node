// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.sun.jna.ptr.LongByReference;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * secp256k1 public-key recovery shared by the chain verifiers (Besu QBFT, Canton). The recovered
 * signer is identified by the 20-byte keccak256 address of its public key, a convention that is
 * independent of whether the chain actually runs an EVM.
 */
public final class Secp256k1Recovery {

    /** Length in bytes of a recoverable signature, {@code r || s || v}. */
    public static final int SIGNATURE_LENGTH = 65;

    /** Length in bytes of a recovered signer address. */
    public static final int ADDRESS_LENGTH = 20;

    private static final int DIGEST_LENGTH = 32;
    private static final int COMPONENT_LENGTH = 32;

    private Secp256k1Recovery() {}

    /**
     * Recovers the signer address of a recoverable secp256k1 signature: {@code keccak256(x || y)[12:]}
     * of the public key that produced {@code signature65} over {@code digest32}. The recovery byte
     * {@code v} (last byte) may be 0/1 or 27/28.
     *
     * @param digest32 the 32-byte message digest that was signed
     * @param signature65 the 65-byte {@code r || s || v} signature
     * @return the 20-byte signer address
     * @throws IllegalArgumentException if an input has the wrong length, {@code v} is not 0/1 (or 27/28),
     *     or the signature cannot be parsed or recovered
     */
    @NonNull
    public static byte[] recoverAddress(@NonNull final byte[] digest32, @NonNull final byte[] signature65) {
        Objects.requireNonNull(digest32, "digest32");
        Objects.requireNonNull(signature65, "signature65");
        if (digest32.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("digest must be 32 bytes");
        }
        if (signature65.length != SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("signature must be " + SIGNATURE_LENGTH + " bytes");
        }
        return recoverAddress(
                digest32,
                Arrays.copyOfRange(signature65, 0, 32),
                Arrays.copyOfRange(signature65, 32, 64),
                signature65[64] & 0xFF);
    }

    /**
     * Recovers the signer address of a recoverable secp256k1 signature given as separate components:
     * {@code keccak256(x || y)[12:]} of the public key that produced {@code (r32, s32)} over {@code digest32}.
     *
     * @param digest32 the 32-byte message digest that was signed
     * @param r32 the 32-byte big-endian {@code r}
     * @param s32 the 32-byte big-endian {@code s}
     * @param recId the recovery id: 0 or 1, or 27 or 28
     * @return the 20-byte signer address
     * @throws IllegalArgumentException if an input has the wrong length, {@code recId} is not 0/1 (or 27/28),
     *     or the signature cannot be parsed or recovered
     */
    @NonNull
    public static byte[] recoverAddress(
            @NonNull final byte[] digest32, @NonNull final byte[] r32, @NonNull final byte[] s32, final int recId) {
        Objects.requireNonNull(digest32, "digest32");
        Objects.requireNonNull(r32, "r32");
        Objects.requireNonNull(s32, "s32");
        if (digest32.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("digest must be 32 bytes");
        }
        if (r32.length != COMPONENT_LENGTH || s32.length != COMPONENT_LENGTH) {
            throw new IllegalArgumentException("r and s must each be " + COMPONENT_LENGTH + " bytes");
        }
        int v = recId;
        if (v == 27 || v == 28) {
            v -= 27;
        }
        if (v != 0 && v != 1) {
            throw new IllegalArgumentException("signature v byte must be 0/1 (or 27/28), got " + recId);
        }
        final byte[] compact = new byte[2 * COMPONENT_LENGTH];
        System.arraycopy(r32, 0, compact, 0, COMPONENT_LENGTH);
        System.arraycopy(s32, 0, compact, COMPONENT_LENGTH, COMPONENT_LENGTH);
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        if (LibSecp256k1.secp256k1_ecdsa_recoverable_signature_parse_compact(
                        LibSecp256k1.CONTEXT, recoverableSig, compact, v)
                != 1) {
            throw new IllegalArgumentException("failed to parse recoverable signature");
        }
        final var pubKey = new LibSecp256k1.secp256k1_pubkey();
        if (LibSecp256k1.secp256k1_ecdsa_recover(LibSecp256k1.CONTEXT, pubKey, recoverableSig, digest32) != 1) {
            throw new IllegalArgumentException("ecRecover failed");
        }
        final ByteBuffer serializedPubKey = ByteBuffer.allocate(65);
        final var serializedPubKeySize = new LongByReference(serializedPubKey.capacity());
        if (LibSecp256k1.secp256k1_ec_pubkey_serialize(
                        LibSecp256k1.CONTEXT,
                        serializedPubKey,
                        serializedPubKeySize,
                        pubKey,
                        LibSecp256k1.SECP256K1_EC_UNCOMPRESSED)
                != 1) {
            throw new IllegalArgumentException("failed to serialize recovered public key");
        }
        final byte[] addr = EthSigsUtils.recoverAddressFromPubKey(serializedPubKey.array());
        if (addr == null || addr.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException("recovered address has wrong length");
        }
        return addr;
    }
}
