// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import static com.hedera.node.app.service.token.impl.test.keys.KeyFactory.DefaultKeyGen.DEFAULT_KEY_GEN;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.hiero.base.utility.CommonUtils;

public class KeyFactory {
    private static final KeyFactory DEFAULT_INSTANCE = new KeyFactory();

    public static KeyFactory getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final KeyGenerator keyGen;
    private final Map<String, Key> labelToPrimitive = new HashMap<>();
    private final Map<String, PrivateKey> publicToPrivateKey = new HashMap<>();

    public KeyFactory() {
        this(DEFAULT_KEY_GEN);
    }

    public KeyFactory(KeyGenerator keyGen) {
        this.keyGen = keyGen;
    }

    public Key labeledEd25519(String label) {
        return labelToPrimitive.computeIfAbsent(label, ignore -> newEd25519());
    }

    public Key labeledEcdsaSecp256k1(String label) {
        return labelToPrimitive.computeIfAbsent(label, ignore -> newEcdsaSecp256k1());
    }

    public Key newEd25519() {
        return keyGen.genEd25519AndUpdateMap(publicToPrivateKey);
    }

    public Key newEcdsaSecp256k1() {
        return keyGen.genEcdsaSecp256k1AndUpdateMap(publicToPrivateKey);
    }

    public Key newList(List<Key> children) {
        return Key.newBuilder()
                .setKeyList(KeyList.newBuilder().addAllKeys(children))
                .build();
    }

    public Key newThreshold(List<Key> children, int M) {
        ThresholdKey.Builder thresholdKey = ThresholdKey.newBuilder()
                .setKeys(KeyList.newBuilder().addAllKeys(children).build())
                .setThreshold(M);
        return Key.newBuilder().setThresholdKey(thresholdKey).build();
    }

    public PrivateKey lookupPrivateKey(Key key) {
        return publicToPrivateKey.get(asPubKeyHex(key));
    }

    public PrivateKey lookupPrivateKey(String pubKeyHex) {
        return publicToPrivateKey.get(pubKeyHex);
    }

    public static String asPubKeyHex(Key key) {
        assert (!key.hasKeyList() && !key.hasThresholdKey());
        if (key.getRSA3072() != ByteString.EMPTY) {
            return CommonUtils.hex(key.getRSA3072().toByteArray());
        } else if (key.getECDSA384() != ByteString.EMPTY) {
            return CommonUtils.hex(key.getECDSA384().toByteArray());
        } else if (key.getECDSASecp256K1() != ByteString.EMPTY) {
            return CommonUtils.hex(key.getECDSASecp256K1().toByteArray());
        } else {
            return CommonUtils.hex(key.getEd25519().toByteArray());
        }
    }

    /**
     * Generates a single Ed25519 key and updates the given public-to-private key mapping.
     *
     * @param publicToPrivateKey mapping from hexed public keys to cryptographic private keys
     * @return a gRPC key structure for the generated Ed25519 key
     */
    public static Key genSingleEd25519Key(final Map<String, PrivateKey> publicToPrivateKey) {
        final var kp = new KeyPairGenerator().generateKeyPair();
        final var pubKey = ((EdDSAPublicKey) kp.getPublic()).getAbyte();
        publicToPrivateKey.put(CommonUtils.hex(pubKey), kp.getPrivate());

        return Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
    }

    /**
     * Generates a single ECDSA(secp25k61) key and updates the given public-to-private key mapping.
     *
     * @param publicToPrivateKey mapping from hexed public keys to cryptographic private keys
     * @return a gRPC key structure for the generated ECDSA(secp25k61) key
     */
    public static Key genSingleEcdsaSecp256k1Key(final Map<String, PrivateKey> publicToPrivateKey) {
        byte[] privateKeyBytes = new byte[Secp256k1Utils.SECRET_KEY_BYTES];
        SECURE_RANDOM.nextBytes(privateKeyBytes);

        ECPrivateKey privateKey = Secp256k1Utils.readECKeyFrom(privateKeyBytes);
        byte[] compressedPublicKey = Secp256k1Utils.extractEcdsaPublicKey(privateKeyBytes);

        publicToPrivateKey.put(CommonUtils.hex(compressedPublicKey), privateKey);

        return Key.newBuilder()
                .setECDSASecp256K1(ByteString.copyFrom(compressedPublicKey))
                .build();
    }

    public enum DefaultKeyGen implements KeyGenerator {
        DEFAULT_KEY_GEN;

        @Override
        public Key genEd25519AndUpdateMap(Map<String, PrivateKey> publicToPrivateKey) {
            return KeyFactory.genSingleEd25519Key(publicToPrivateKey);
        }

        @Override
        public Key genEcdsaSecp256k1AndUpdateMap(Map<String, PrivateKey> publicToPrivateKey) {
            return KeyFactory.genSingleEcdsaSecp256k1Key(publicToPrivateKey);
        }
    }
}
