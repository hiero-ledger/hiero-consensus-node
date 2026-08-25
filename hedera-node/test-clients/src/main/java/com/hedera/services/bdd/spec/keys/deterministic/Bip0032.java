// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys.deterministic;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;

public class Bip0032 {
    private static final int KEY_SIZE = 512;
    private static final int ITERATIONS = 2048;
    private static final String ALGORITHM = "HmacSHA512";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int[] INDICES = {44, 3030, 0, 0, 0};
    private static final byte[] MAC_PASSWORD = "ed25519 seed".getBytes(Charset.forName("UTF-8"));

    /**
     * Converts the mnemonic to the corresponding Ed25519 private key as prescribed by BIP-0032
     * and the Hedera key derivation indices.
     *
     * @param mnemonic the mnemonic to convert
     * @return the Ed25519 private key
     */
    public static EdDSAPrivateKey mnemonicToEd25519Key(@NonNull final String mnemonic) {
        try {
            return Ed25519Factory.ed25519From(privateKeyFrom(seedFrom(mnemonic)));
        } catch (NoSuchAlgorithmException | InvalidKeyException | ShortBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] seedFrom(String mnemonic) {
        final String salt = "mnemonic";
        final KeySpec spec = new PBEKeySpec(mnemonic.toCharArray(), salt.getBytes(UTF_8), ITERATIONS, KEY_SIZE);
        try {
            final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] privateKeyFrom(byte[] seed)
            throws NoSuchAlgorithmException, InvalidKeyException, ShortBufferException {
        final byte[] buf = new byte[64];
        final Mac mac = Mac.getInstance(ALGORITHM);

        mac.init(new SecretKeySpec(MAC_PASSWORD, ALGORITHM));
        mac.update(seed);
        mac.doFinal(buf, 0);

        for (int i : INDICES) {
            mac.init(new SecretKeySpec(buf, 32, 32, ALGORITHM));
            mac.update((byte) 0x00);
            mac.update(buf, 0, 32);
            mac.update((byte) (i >> 24 | 0x80));
            mac.update((byte) (i >> 16));
            mac.update((byte) (i >> 8));
            mac.update((byte) i);
            mac.doFinal(buf, 0);
        }
        return Arrays.copyOfRange(buf, 0, 32);
    }

    public static String mnemonicFromFile(final String wordsLoc) {
        try (final var lines = Files.lines(Paths.get(wordsLoc))) {
            return lines.map(String::strip).collect(Collectors.joining(" ")).strip();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
