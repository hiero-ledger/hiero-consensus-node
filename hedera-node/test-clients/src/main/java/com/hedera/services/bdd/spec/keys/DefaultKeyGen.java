// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static com.hedera.node.app.hapi.utils.SignatureGenerator.BOUNCYCASTLE_PROVIDER;
import static org.hiero.base.utility.CommonUtils.hex;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Key;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Map;
import net.i2p.crypto.eddsa.EdDSAPublicKey;

/**
 * The default {@link KeyGenerator}, which generates Ed25519 and ECDSA(secp256k1) keys
 * using secure key generators.
 */
public enum DefaultKeyGen implements KeyGenerator {
    DEFAULT_KEY_GEN;

    private static final ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256k1");
    private static final KeyPairGenerator ecKpGenerator;

    static {
        try {
            ecKpGenerator = KeyPairGenerator.getInstance("EC", BOUNCYCASTLE_PROVIDER);
            ecKpGenerator.initialize(ecSpec, new SecureRandom());
        } catch (final NoSuchAlgorithmException | InvalidAlgorithmParameterException fatal) {
            throw new IllegalStateException(fatal);
        }
    }

    @Override
    public Key genEd25519AndUpdateMap(final Map<String, PrivateKey> mutablePkMap) {
        return genAndRememberEd25519Key(mutablePkMap);
    }

    @Override
    public Key genEcdsaSecp256k1AndUpdate(final Map<String, PrivateKey> mutablePkMap) {
        final var kp = ecKpGenerator.generateKeyPair();
        final var compressedPublicKey = asCompressedEcdsaKey(kp.getPublic());
        mutablePkMap.put(hex(compressedPublicKey), kp.getPrivate());
        return Key.newBuilder()
                .setECDSASecp256K1(ByteString.copyFrom(compressedPublicKey))
                .build();
    }

    public static Key genAndRememberEd25519Key(final Map<String, PrivateKey> hexedPublicToPrivateKeys) {
        final var pair = new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
        final var pubKey = remember(pair, hexedPublicToPrivateKeys);
        return protoEd25519KeyWith(pubKey);
    }

    public static Key protoEd25519KeyWith(final byte[] pubKey) {
        return Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
    }

    public static Key protoEcdsaKeyWith(final byte[] pubKey) {
        return Key.newBuilder().setECDSASecp256K1(ByteString.copyFrom(pubKey)).build();
    }

    private static byte[] remember(final KeyPair pair, final Map<String, PrivateKey> hexedPublicToPrivateKeys) {
        final var pubKey = ((EdDSAPublicKey) pair.getPublic()).getAbyte();
        final var pubKeyHex = hex(pubKey);
        hexedPublicToPrivateKeys.put(pubKeyHex, pair.getPrivate());
        return pubKey;
    }

    private static byte[] asCompressedEcdsaKey(final PublicKey publicKey) {
        final var encodedPk = publicKey.getEncoded();
        final var rawPkCoords = Arrays.copyOfRange(encodedPk, encodedPk.length - 64, encodedPk.length);
        final var compressedPk = new byte[33];
        compressedPk[0] = (rawPkCoords[63] & 1) == 1 ? (byte) 0x03 : (byte) 0x02;
        System.arraycopy(rawPkCoords, 0, compressedPk, 1, 32);
        return compressedPk;
    }
}
