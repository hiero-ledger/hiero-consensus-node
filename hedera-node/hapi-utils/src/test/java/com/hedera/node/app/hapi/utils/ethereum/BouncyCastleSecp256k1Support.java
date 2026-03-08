// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static java.lang.Math.floorMod;

import java.math.BigInteger;
import java.util.Arrays;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;

/**
 * Pure BouncyCastle secp256k1 helpers for differential testing against the native production path.
 */
public final class BouncyCastleSecp256k1Support {
    private static final int ADDRESS_SIZE = 20;
    private static final int UNCOMPRESSED_KEY_SIZE = 64;
    private static final int COORDINATE_SIZE = UNCOMPRESSED_KEY_SIZE / 2;
    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
    private static final BigInteger FIELD_PRIME = CURVE.getCurve().getField().getCharacteristic();
    private static final BigInteger CURVE_ORDER = CURVE.getN();

    private BouncyCastleSecp256k1Support() {}

    public static byte[] deriveCompressedPublicKey(final byte[] privateKey) {
        final var point =
                new FixedPointCombMultiplier().multiply(CURVE.getG(), new BigInteger(1, privateKey)).normalize();
        return point.getEncoded(true);
    }

    public static byte[] decompressCompressedPublicKey(final byte[] compressedKey) {
        return toRawPublicKey(CURVE.getCurve().decodePoint(compressedKey).normalize());
    }

    public static byte[] compressRawPublicKey(final byte[] rawPublicKey) {
        return pointFromRawPublicKey(rawPublicKey).getEncoded(true);
    }

    public static byte[] recoverAddressFromCompressedPublicKey(final byte[] compressedKey) {
        return recoverAddressFromRawPublicKey(decompressCompressedPublicKey(compressedKey));
    }

    public static byte[] recoverAddressFromRawPublicKey(final byte[] rawPublicKey) {
        final var keyHash = new Keccak.Digest256().digest(rawPublicKey);
        return Arrays.copyOfRange(keyHash, keyHash.length - ADDRESS_SIZE, keyHash.length);
    }

    public static byte[] recoverCompressedPublicKey(final EthTxData ethTxData) {
        return compressRawPublicKey(recoverRawPublicKey(ethTxData));
    }

    public static byte[] recoverAddress(final EthTxData ethTxData) {
        return recoverAddressFromRawPublicKey(recoverRawPublicKey(ethTxData));
    }

    public static byte[] recoverRawPublicKey(final EthTxData ethTxData) {
        return recoverRawPublicKey(
                ethTxData.recId(), ethTxData.r(), ethTxData.s(), EthTxSigs.calculateSignableMessage(ethTxData));
    }

    public static byte[] recoverRawPublicKey(
            final int recId, final byte[] rBytes, final byte[] sBytes, final byte[] signableMessage) {
        final int normalizedRecId = floorMod(recId, 2);
        final var r = checkedCoordinate(rBytes);
        final var s = checkedCoordinate(sBytes);
        final var digest = new Keccak.Digest256().digest(signableMessage);
        final var recoveredPoint = recoverPointFromSignature(normalizedRecId, r, s, digest);
        if (recoveredPoint == null) {
            throw new IllegalArgumentException("Could not recover signature");
        }
        return toRawPublicKey(recoveredPoint);
    }

    private static BigInteger checkedCoordinate(final byte[] coordinate) {
        final var value = new BigInteger(1, coordinate);
        if (value.compareTo(BigInteger.ONE) < 0) {
            throw new IllegalArgumentException("Curve point must be >= 1");
        }
        if (value.compareTo(CURVE_ORDER) >= 0) {
            throw new IllegalArgumentException("Curve point must be < N");
        }
        return value;
    }

    private static ECPoint recoverPointFromSignature(
            final int recId, final BigInteger r, final BigInteger s, final byte[] digest) {
        final var x = r.add(BigInteger.valueOf((long) recId / 2L).multiply(CURVE_ORDER));
        if (x.compareTo(FIELD_PRIME) >= 0) {
            return null;
        }

        final var candidatePoint = decompressKey(x, (recId & 1) == 1);
        if (!candidatePoint.multiply(CURVE_ORDER).isInfinity()) {
            return null;
        }

        final var e = new BigInteger(1, digest);
        final var eInv = BigInteger.ZERO.subtract(e).mod(CURVE_ORDER);
        final var rInv = r.modInverse(CURVE_ORDER);
        final var srInv = rInv.multiply(s).mod(CURVE_ORDER);
        final var eInvrInv = rInv.multiply(eInv).mod(CURVE_ORDER);
        final var publicKey = ECAlgorithms.sumOfTwoMultiplies(DOMAIN.getG(), eInvrInv, candidatePoint, srInv);
        return publicKey.isInfinity() ? null : publicKey.normalize();
    }

    private static ECPoint decompressKey(final BigInteger xCoordinate, final boolean yBit) {
        final var converter = new X9IntegerConverter();
        final var compEnc = converter.integerToBytes(
                xCoordinate, 1 + converter.getByteLength(DOMAIN.getCurve()));
        compEnc[0] = (byte) (yBit ? 0x03 : 0x02);
        return DOMAIN.getCurve().decodePoint(compEnc);
    }

    private static ECPoint pointFromRawPublicKey(final byte[] rawPublicKey) {
        if (rawPublicKey.length != UNCOMPRESSED_KEY_SIZE) {
            throw new IllegalArgumentException("Raw public key must be 64 bytes");
        }
        final var encoded = new byte[UNCOMPRESSED_KEY_SIZE + 1];
        encoded[0] = 0x04;
        System.arraycopy(rawPublicKey, 0, encoded, 1, rawPublicKey.length);
        return CURVE.getCurve().decodePoint(encoded).normalize();
    }

    private static byte[] toRawPublicKey(final ECPoint point) {
        final var encoded = point.getEncoded(false);
        return Arrays.copyOfRange(encoded, 1, encoded.length);
    }
}
