// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Objects;

/**
 * Raw ECDSA verification over the NIST P-256 (secp256r1) curve, mirroring the EIP-7951
 * {@code P256VERIFY} precompile the reference Solidity verifier calls.
 *
 * <p>The verifier hashes nothing: the caller passes the 32-byte message digest directly, and
 * {@link #verify} checks {@code (r, s)} against the public key point {@code (x, y)} using the JDK's
 * {@code NONEwithECDSAinP1363Format} signature (pre-hashed input, fixed-width {@code r || s}). Any
 * malformed input — an off-curve point, a wrong-length field, or an out-of-range scalar — yields
 * {@code false} rather than an exception, so a bad signature can never short-circuit higher-level
 * verification.
 */
public final class P256Verify {

    /** secp256r1 (NIST P-256) domain parameters, from the JDK. */
    private static final ECParameterSpec PARAMS = loadParams();

    private static final BigInteger ORDER = PARAMS.getOrder();
    private static final BigInteger FIELD_PRIME = ((ECFieldFp) PARAMS.getCurve().getField()).getP();

    /** Byte length of a P-256 coordinate or scalar. */
    private static final int FIELD_LENGTH = 32;

    private P256Verify() {}

    private static ECParameterSpec loadParams() {
        try {
            final AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            return ap.getParameterSpec(ECParameterSpec.class);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("JDK does not support secp256r1", e);
        }
    }

    /**
     * Verifies the raw ECDSA signature {@code (r, s)} of the 32-byte digest {@code hash32} under the
     * P-256 public key whose affine coordinates are {@code x} and {@code y}. Returns {@code false}
     * (never throws) for any malformed or invalid input.
     *
     * @param x the 32-byte affine x-coordinate of the public key
     * @param y the 32-byte affine y-coordinate of the public key
     * @param hash32 the 32-byte message digest that was signed
     * @param r the 32-byte signature component r
     * @param s the 32-byte signature component s
     */
    public static boolean verify(
            @NonNull final byte[] x,
            @NonNull final byte[] y,
            @NonNull final byte[] hash32,
            @NonNull final byte[] r,
            @NonNull final byte[] s) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(hash32, "hash32");
        Objects.requireNonNull(r, "r");
        Objects.requireNonNull(s, "s");
        if (x.length != FIELD_LENGTH
                || y.length != FIELD_LENGTH
                || hash32.length != FIELD_LENGTH
                || r.length != FIELD_LENGTH
                || s.length != FIELD_LENGTH) {
            return false;
        }
        try {
            final BigInteger rInt = new BigInteger(1, r);
            final BigInteger sInt = new BigInteger(1, s);
            // Reject out-of-range scalars up front; the JDK also rejects these, but be explicit.
            if (rInt.signum() <= 0 || sInt.signum() <= 0) {
                return false;
            }
            if (rInt.compareTo(ORDER) >= 0 || sInt.compareTo(ORDER) >= 0) {
                return false;
            }
            final BigInteger xInt = new BigInteger(1, x);
            final BigInteger yInt = new BigInteger(1, y);
            if (!isOnCurve(xInt, yInt)) {
                return false;
            }
            final PublicKey key =
                    KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(xInt, yInt), PARAMS));
            final byte[] p1363 = new byte[2 * FIELD_LENGTH];
            System.arraycopy(r, 0, p1363, 0, FIELD_LENGTH);
            System.arraycopy(s, 0, p1363, FIELD_LENGTH, FIELD_LENGTH);
            final Signature verifier = Signature.getInstance("NONEwithECDSAinP1363Format");
            verifier.initVerify(key);
            verifier.update(hash32);
            return verifier.verify(p1363);
        } catch (final GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** Checks {@code 0 <= x,y < p} and {@code y^2 = x^3 + ax + b (mod p)}. */
    private static boolean isOnCurve(final BigInteger x, final BigInteger y) {
        if (x.compareTo(FIELD_PRIME) >= 0 || y.compareTo(FIELD_PRIME) >= 0) {
            return false;
        }
        final BigInteger lhs = y.multiply(y).mod(FIELD_PRIME);
        final BigInteger rhs = x.multiply(x)
                .add(PARAMS.getCurve().getA())
                .multiply(x)
                .add(PARAMS.getCurve().getB())
                .mod(FIELD_PRIME);
        return lhs.equals(rhs);
    }
}
