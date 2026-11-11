// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonMediators}.
 *
 * <p>Provenance of each vector is documented inline. {@code validate}/{@code lookup} run over the
 * REAL {@code topology-mediator-set.hex} fixture (a Canton LocalNet packed set with one Ed25519
 * mediator key). Signature vectors are CONSTRUCTED with real crypto libraries (JDK SunEC for P-256,
 * BouncyCastle for Ed25519 and secp256k1), since no real P-256/secp256k1 mediator signature exists in
 * the fixtures; DER test vectors are built deterministically from real scalar magnitudes.
 */
class CantonMediatorsTest {

    private static final BigInteger SECP256K1_N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    // ---------------------------------------------------------------------------------------------
    // validate / lookup over the REAL fixture
    // ---------------------------------------------------------------------------------------------

    @Test
    void validateReturnsFixtureThresholdAndMembers() {
        // REAL: topology-mediator-set.hex (threshold 1, 1 member, 1 current Ed25519 key).
        final byte[] set = CantonFixtures.topologyMediatorSet();
        final var summary = CantonMediators.validate(set);
        assertThat(summary.threshold()).isEqualTo(1);
        assertThat(summary.members()).isEqualTo(1);
    }

    @Test
    void validateRejectsTruncatedSet() {
        // REAL fixture with its last byte dropped — the offset walk no longer lands on set.length.
        final byte[] set = CantonFixtures.topologyMediatorSet();
        final byte[] truncated = Arrays.copyOf(set, set.length - 1);
        assertThatThrownBy(() -> CantonMediators.validate(truncated))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo("InvalidMediatorSet"));
    }

    @Test
    void validateRejectsShortHeader() {
        assertThatThrownBy(() -> CantonMediators.validate(new byte[11])).isInstanceOf(CantonProofException.class);
    }

    @Test
    void validateRejectsZeroThreshold() {
        // CONSTRUCTED: header mssSerial=1, threshold=0, members=1 → reference rejects threshold==0.
        final byte[] set = new byte[12];
        set[3] = 1; // mssSerial
        // threshold bytes 4..7 = 0
        set[11] = 1; // members = 1
        assertThatThrownBy(() -> CantonMediators.validate(set)).isInstanceOf(CantonProofException.class);
    }

    @Test
    void lookupFindsFixtureKeyByFingerprint() {
        // REAL fixture: the single current key sits at offset 50; fingerprint 50..82, keySpec 82,
        // point x 84..116, y 116..148.
        final byte[] set = CantonFixtures.topologyMediatorSet();
        final byte[] fingerprint = Arrays.copyOfRange(set, 50, 82);
        final var key = CantonMediators.lookup(set, fingerprint);
        assertThat(key.found()).isTrue();
        assertThat(key.member()).isEqualTo(0);
        assertThat(key.keySpec()).isEqualTo(CantonMediators.KEY_SPEC_ED25519);
        assertThat(key.x()).isEqualTo(Arrays.copyOfRange(set, 84, 116));
        assertThat(key.y()).isEqualTo(Arrays.copyOfRange(set, 116, 148));
    }

    @Test
    void lookupReturnsNotFoundForUnknownFingerprint() {
        final byte[] set = CantonFixtures.topologyMediatorSet();
        final byte[] unknown = new byte[32];
        Arrays.fill(unknown, (byte) 0xAB);
        assertThat(CantonMediators.lookup(set, unknown).found()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // parseEcdsaSignature
    // ---------------------------------------------------------------------------------------------

    @Test
    void parseRaw64Signature() {
        // CONSTRUCTED: 64 arbitrary-but-well-formed bytes split at 32.
        final byte[] sig = new byte[64];
        for (int i = 0; i < 64; i++) {
            sig[i] = (byte) (i + 1);
        }
        final var parsed = CantonMediators.parseEcdsaSignature(sig);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.r()).isEqualTo(Arrays.copyOfRange(sig, 0, 32));
        assertThat(parsed.s()).isEqualTo(Arrays.copyOfRange(sig, 32, 64));
    }

    @Test
    void parseDerWithoutLeadingZero() {
        // CONSTRUCTED: r, s magnitudes with the high bit clear → 32-byte DER INTEGERs, no 0x00 pad.
        final BigInteger r = new BigInteger(1, repeat((byte) 0x7f, 32));
        final BigInteger s = new BigInteger(1, repeat((byte) 0x11, 32));
        final byte[] der = der(r, s);
        final var parsed = CantonMediators.parseEcdsaSignature(der);
        assertThat(parsed.ok()).isTrue();
        assertThat(new BigInteger(1, parsed.r())).isEqualTo(r);
        assertThat(new BigInteger(1, parsed.s())).isEqualTo(s);
    }

    @Test
    void parseDerWithLeadingZeroInteger() {
        // CONSTRUCTED: r has the high bit set → BigInteger.toByteArray() prepends 0x00, making a
        // 33-byte INTEGER the parser must strip.
        final BigInteger r = new BigInteger(1, repeat((byte) 0x80, 32));
        final BigInteger s = new BigInteger(1, repeat((byte) 0x22, 32));
        final byte[] der = der(r, s);
        // Sanity: the r INTEGER really is 33 bytes (0x00 pad present).
        assertThat(r.toByteArray().length).isEqualTo(33);
        final var parsed = CantonMediators.parseEcdsaSignature(der);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.r()).hasSize(32);
        assertThat(new BigInteger(1, parsed.r())).isEqualTo(r);
        assertThat(new BigInteger(1, parsed.s())).isEqualTo(s);
    }

    @Test
    void parseRejectsMalformedAndOverLength() {
        // Wrong SEQUENCE tag.
        final byte[] badTag = der(BigInteger.ONE, BigInteger.ONE);
        badTag[0] = 0x31;
        assertThat(CantonMediators.parseEcdsaSignature(badTag).ok()).isFalse();

        // Declared length does not match actual length.
        final byte[] badLen = der(BigInteger.ONE, BigInteger.ONE);
        badLen[1] = (byte) (badLen[1] + 1);
        assertThat(CantonMediators.parseEcdsaSignature(badLen).ok()).isFalse();

        // Trailing byte after a valid DER signature (off != length).
        final byte[] valid = der(BigInteger.ONE, BigInteger.ONE);
        // Appending a byte leaves the declared length (sig[1]) stale, so actual != declared.
        final byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThat(CantonMediators.parseEcdsaSignature(trailing).ok()).isFalse();

        // Wrong INTEGER tag for r.
        final byte[] badInt = der(BigInteger.ONE, BigInteger.ONE);
        badInt[2] = 0x03;
        assertThat(CantonMediators.parseEcdsaSignature(badInt).ok()).isFalse();

        // Too short to be DER and not 64 bytes.
        assertThat(CantonMediators.parseEcdsaSignature(new byte[7]).ok()).isFalse();

        // 65 zero bytes: not 64, and not a valid DER header.
        assertThat(CantonMediators.parseEcdsaSignature(new byte[65]).ok()).isFalse();

        // INTEGER longer than 32 bytes (not the 33-byte leading-zero case).
        final byte[] overLong = new byte[] {0x30, 0x24, 0x02, 0x22}; // r INTEGER claims 34 bytes
        final byte[] padded = Arrays.copyOf(overLong, 2 + 0x24);
        assertThat(CantonMediators.parseEcdsaSignature(padded).ok()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // verify dispatch
    // ---------------------------------------------------------------------------------------------

    @Test
    void verifyDispatchesEd25519() {
        // CONSTRUCTED: real BC Ed25519 key/signature; keySpec 1, x = 32-byte key, y = zero pad.
        final var priv = new Ed25519PrivateKeyParameters(new SecureRandom());
        final Ed25519PublicKeyParameters pub = priv.generatePublicKey();
        final byte[] message = "verdict multihash".getBytes();
        final var signer = new Ed25519Signer();
        signer.init(true, priv);
        signer.update(message, 0, message.length);
        final byte[] sig = signer.generateSignature();
        final var key = new CantonMediators.SignerKey(
                true, 0, CantonMediators.KEY_SPEC_ED25519, pub.getEncoded(), new byte[32]);

        assertThat(CantonMediators.verify(key, message, sig)).isTrue();
        final byte[] tampered = message.clone();
        tampered[0] ^= 0x01;
        assertThat(CantonMediators.verify(key, tampered, sig)).isFalse();
    }

    @Test
    void verifyDispatchesP256OverSha256() throws Exception {
        // CONSTRUCTED: real SunEC secp256r1 key, SHA256withECDSA signature (DER). verify hashes
        // the message internally with SHA-256, matching the reference.
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair kp = kpg.generateKeyPair();
        final byte[] message = "verdict content".getBytes();
        final Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(message);
        final byte[] der = s.sign();
        final var ec = (ECPublicKey) kp.getPublic();
        final var key = new CantonMediators.SignerKey(
                true,
                0,
                CantonMediators.KEY_SPEC_P256,
                to32(ec.getW().getAffineX()),
                to32(ec.getW().getAffineY()));

        assertThat(CantonMediators.verify(key, message, der)).isTrue();
        assertThat(CantonMediators.verify(key, "other".getBytes(), der)).isFalse();
    }

    @Test
    void verifyDispatchesSecp256k1RoundTrip() {
        // CONSTRUCTED: real BC secp256k1 key, ECDSA over SHA256(message), raw r||s signature.
        final X9ECParameters p = CustomNamedCurves.getByName("secp256k1");
        final ECDomainParameters domain = new ECDomainParameters(p.getCurve(), p.getG(), p.getN(), p.getH());
        final var gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        final var pair = gen.generateKeyPair();
        final var priv = (ECPrivateKeyParameters) pair.getPrivate();
        final var pub = (ECPublicKeyParameters) pair.getPublic();
        final byte[] message = "verdict content k1".getBytes();
        final byte[] digest = sha256(message);
        final var signer = new ECDSASigner();
        signer.init(true, priv);
        final BigInteger[] rs = signer.generateSignature(digest);
        final byte[] sig = concat(to32(rs[0]), to32(rs[1]));
        final byte[] x = to32(pub.getQ().normalize().getAffineXCoord().toBigInteger());
        final byte[] y = to32(pub.getQ().normalize().getAffineYCoord().toBigInteger());
        final var key = new CantonMediators.SignerKey(true, 0, CantonMediators.KEY_SPEC_SECP256K1, x, y);

        assertThat(CantonMediators.verify(key, message, sig)).isTrue();
        assertThat(CantonMediators.verify(key, "tampered".getBytes(), sig)).isFalse();
    }

    @Test
    void verifySecp256k1RejectsZeroAndOutOfRangeS() {
        // CONSTRUCTED: s bounds are checked before recovery, so the key point is irrelevant here.
        final var key =
                new CantonMediators.SignerKey(true, 0, CantonMediators.KEY_SPEC_SECP256K1, new byte[32], new byte[32]);
        final byte[] r = repeat((byte) 0x11, 32);

        final byte[] sZero = concat(r, new byte[32]);
        assertThat(CantonMediators.verify(key, "m".getBytes(), sZero)).isFalse();

        final byte[] sEqualsN = concat(r, to32(SECP256K1_N));
        assertThat(CantonMediators.verify(key, "m".getBytes(), sEqualsN)).isFalse();

        final byte[] sAboveN = concat(r, repeat((byte) 0xFF, 32));
        assertThat(CantonMediators.verify(key, "m".getBytes(), sAboveN)).isFalse();
    }

    @Test
    void verifyRejectsUnknownKeySpec() {
        final var key = new CantonMediators.SignerKey(true, 0, 99, new byte[32], new byte[32]);
        assertThat(CantonMediators.verify(key, "m".getBytes(), new byte[64])).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private static byte[] der(final BigInteger r, final BigInteger s) {
        final byte[] rb = r.toByteArray();
        final byte[] sb = s.toByteArray();
        final var out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(2 + rb.length + 2 + sb.length);
        out.write(0x02);
        out.write(rb.length);
        out.writeBytes(rb);
        out.write(0x02);
        out.write(sb.length);
        out.writeBytes(sb);
        return out.toByteArray();
    }

    private static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(final byte[] message) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(message);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] to32(final BigInteger v) {
        final byte[] raw = v.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }
}
