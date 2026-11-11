// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Objects;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;

/**
 * Raw ECDSA verification over the NIST P-256 (secp256r1) curve, mirroring the EIP-7951
 * {@code P256VERIFY} precompile the reference Solidity verifier calls.
 *
 * <p>The verifier hashes nothing: the caller passes the 32-byte message digest directly, and
 * {@link #verify} checks {@code (r, s)} against the public key point {@code (x, y)} exactly as
 * {@code ECDSASigner.verifySignature(hash, r, s)} does. Any malformed input — an off-curve point,
 * a wrong-length field, or an out-of-range scalar — yields {@code false} rather than an exception,
 * so a bad signature can never short-circuit higher-level verification.
 */
public final class P256Verify {

    /** secp256r1 (NIST P-256) domain parameters. */
    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName("secp256r1");

    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    /** Byte length of a P-256 coordinate or scalar. */
    private static final int FIELD_LENGTH = 32;

    private P256Verify() {}

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
            // Reject out-of-range scalars up front; BC also rejects these, but be explicit.
            if (rInt.signum() <= 0 || sInt.signum() <= 0) {
                return false;
            }
            if (rInt.compareTo(DOMAIN.getN()) >= 0 || sInt.compareTo(DOMAIN.getN()) >= 0) {
                return false;
            }
            // Build and validate the public key point; an off-curve point throws here.
            final ECPoint point = DOMAIN.getCurve().validatePoint(new BigInteger(1, x), new BigInteger(1, y));
            if (point.isInfinity()) {
                return false;
            }
            final ECDSASigner signer = new ECDSASigner();
            signer.init(false, new ECPublicKeyParameters(point, DOMAIN));
            return signer.verifySignature(hash32, rInt, sInt);
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
