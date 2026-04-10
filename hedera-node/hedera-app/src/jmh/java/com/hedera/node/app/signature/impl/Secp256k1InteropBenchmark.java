// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static java.lang.Math.floorMod;

import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks native secp256k1 call sites against a pure BouncyCastle implementation using identical inputs.
 */
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class Secp256k1InteropBenchmark {
    private static final byte[] TRUFFLE0_PRIVATE_KEY = HexFormat.of()
            .parseHex("c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3");
    private static final byte[] TRUFFLE0_COMPRESSED_PUBLIC_KEY = HexFormat.of()
            .parseHex("03af80b90d25145da28c583359beb47b21796b2fe1a23c1511e443e7a64dfdb27d");
    private static final byte[] ISSUE4180_TX = HexFormat.of()
            .parseHex(
                    "f88b718601d1a94a20008316e360940000000000000000000000000000000002e8a7b980a4fdacd5760000000000000000000000000000000000000000000000000000000000000002820273a076398dfd239dcdf69aeef7328a5e8cc69ef1b4ba5cca56eab1af06d7959923599f8194cd217b301cbdbdcd05b3572c411ec9333af39c98af8c5c9de45ddb05c5");
    private static final byte[] SAMPLE_MESSAGE_DIGEST =
            new Keccak.Digest256().digest("secp256k1-benchmark-message".getBytes(StandardCharsets.UTF_8));

    private final Cryptography cryptography = CryptographyProvider.getInstance();
    private EthTxData ethTxData;
    private byte[] rawPublicKey;
    private byte[] rawSignature;

    @Setup(Level.Trial)
    public void setUp() {
        ethTxData = EthTxData.populateEthTxData(ISSUE4180_TX);
        rawPublicKey = BouncyCastleSecp256k1.deriveRawPublicKey(TRUFFLE0_PRIVATE_KEY);
        rawSignature = BouncyCastleSecp256k1.signDigest(TRUFFLE0_PRIVATE_KEY, SAMPLE_MESSAGE_DIGEST);
    }

    @Benchmark
    public void nativeRecoverAddressFromCompressedKey(final Blackhole blackhole) {
        blackhole.consume(EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_COMPRESSED_PUBLIC_KEY));
    }

    @Benchmark
    public void bcRecoverAddressFromCompressedKey(final Blackhole blackhole) {
        blackhole.consume(BouncyCastleSecp256k1.recoverAddressFromCompressedPublicKey(TRUFFLE0_COMPRESSED_PUBLIC_KEY));
    }

    @Benchmark
    public void nativeDecompressCompressedKey(final Blackhole blackhole) {
        blackhole.consume(MiscCryptoUtils.decompressSecp256k1(TRUFFLE0_COMPRESSED_PUBLIC_KEY));
    }

    @Benchmark
    public void bcDecompressCompressedKey(final Blackhole blackhole) {
        blackhole.consume(BouncyCastleSecp256k1.decompressCompressedPublicKey(TRUFFLE0_COMPRESSED_PUBLIC_KEY));
    }

    @Benchmark
    public void nativeExtractEthereumTransactionSignatures(final Blackhole blackhole) {
        blackhole.consume(EthTxSigs.extractSignatures(ethTxData));
    }

    @Benchmark
    public void bcExtractEthereumTransactionSignatures(final Blackhole blackhole) {
        blackhole.consume(BouncyCastleSecp256k1.extractSignatures(ethTxData));
    }

    @Benchmark
    public void nativeVerifySignature(final Blackhole blackhole) {
        blackhole.consume(cryptography.verifySync(
                SAMPLE_MESSAGE_DIGEST, rawSignature, rawPublicKey, SignatureType.ECDSA_SECP256K1));
    }

    @Benchmark
    public void bcVerifySignature(final Blackhole blackhole) {
        blackhole.consume(BouncyCastleSecp256k1.verify(rawSignature, SAMPLE_MESSAGE_DIGEST, rawPublicKey));
    }

    private record SignatureExtraction(byte[] publicKey, byte[] address) {}

    private static final class BouncyCastleSecp256k1 {
        private static final int ADDRESS_SIZE = 20;
        private static final int RAW_PUBLIC_KEY_SIZE = 64;
        private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
        private static final ECDomainParameters DOMAIN =
                new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
        private static final BigInteger CURVE_ORDER = CURVE.getN();
        private static final BigInteger FIELD_PRIME = CURVE.getCurve().getField().getCharacteristic();

        private static byte[] deriveRawPublicKey(final byte[] privateKey) {
            final var point =
                    new FixedPointCombMultiplier().multiply(CURVE.getG(), new BigInteger(1, privateKey)).normalize();
            return toRawPublicKey(point);
        }

        private static byte[] signDigest(final byte[] privateKey, final byte[] digest) {
            final var signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, privateKey), DOMAIN));
            final var signature = signer.generateSignature(digest);
            final var rawSignature = new byte[64];
            System.arraycopy(to32Bytes(signature[0]), 0, rawSignature, 0, 32);
            System.arraycopy(to32Bytes(signature[1]), 0, rawSignature, 32, 32);
            return rawSignature;
        }

        private static boolean verify(final byte[] rawSignature, final byte[] digest, final byte[] rawPublicKey) {
            final var signer = new ECDSASigner();
            signer.init(false, new ECPublicKeyParameters(pointFromRawPublicKey(rawPublicKey), DOMAIN));
            return signer.verifySignature(
                    digest,
                    new BigInteger(1, Arrays.copyOfRange(rawSignature, 0, 32)),
                    new BigInteger(1, Arrays.copyOfRange(rawSignature, 32, 64)));
        }

        private static byte[] decompressCompressedPublicKey(final byte[] compressedKey) {
            return toRawPublicKey(CURVE.getCurve().decodePoint(compressedKey).normalize());
        }

        private static byte[] recoverAddressFromCompressedPublicKey(final byte[] compressedKey) {
            final var keyHash = new Keccak.Digest256().digest(decompressCompressedPublicKey(compressedKey));
            return Arrays.copyOfRange(keyHash, keyHash.length - ADDRESS_SIZE, keyHash.length);
        }

        private static SignatureExtraction extractSignatures(final EthTxData ethTxData) {
            final var rawPublicKey = recoverRawPublicKey(
                    ethTxData.recId(), ethTxData.r(), ethTxData.s(), EthTxSigs.calculateSignableMessage(ethTxData));
            final var compressedPublicKey = pointFromRawPublicKey(rawPublicKey).getEncoded(true);
            return new SignatureExtraction(compressedPublicKey, recoverAddressFromCompressedPublicKey(compressedPublicKey));
        }

        private static byte[] recoverRawPublicKey(
                final int recId, final byte[] rBytes, final byte[] sBytes, final byte[] signableMessage) {
            final var digest = new Keccak.Digest256().digest(signableMessage);
            final var point = recoverPoint(
                    floorMod(recId, 2), new BigInteger(1, rBytes), new BigInteger(1, sBytes), digest);
            return point == null ? new byte[0] : toRawPublicKey(point);
        }

        private static ECPoint recoverPoint(
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
            final var compEnc =
                    converter.integerToBytes(xCoordinate, 1 + converter.getByteLength(DOMAIN.getCurve()));
            compEnc[0] = (byte) (yBit ? 0x03 : 0x02);
            return DOMAIN.getCurve().decodePoint(compEnc);
        }

        private static ECPoint pointFromRawPublicKey(final byte[] rawPublicKey) {
            final var encoded = new byte[RAW_PUBLIC_KEY_SIZE + 1];
            encoded[0] = 0x04;
            System.arraycopy(rawPublicKey, 0, encoded, 1, rawPublicKey.length);
            return CURVE.getCurve().decodePoint(encoded).normalize();
        }

        private static byte[] toRawPublicKey(final ECPoint point) {
            final var encoded = point.getEncoded(false);
            return Arrays.copyOfRange(encoded, 1, encoded.length);
        }

        private static byte[] to32Bytes(final BigInteger value) {
            final var input = value.toByteArray();
            final var output = new byte[32];
            System.arraycopy(input, Math.max(0, input.length - 32), output, Math.max(0, 32 - input.length), Math.min(32, input.length));
            return output;
        }
    }
}
