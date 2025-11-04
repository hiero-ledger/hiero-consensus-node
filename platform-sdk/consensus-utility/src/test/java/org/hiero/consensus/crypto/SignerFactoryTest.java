package org.hiero.consensus.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import org.hiero.base.crypto.BytesSigner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SignerFactoryTest {
    private static final Bytes TEST_DATA = Bytes.fromHex("abcd1234");
    private static final Bytes BAD_DATA = Bytes.fromHex("abcd");

    @Test
    void testSodiumCompatibility() throws Exception {
        final SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");
        secureRandom.setSeed(123);

        //final KeyPair keyPair = SignerFactory.generateKeyPair(SigningType.ED25519_SUN, secureRandom);
        final KeyPair keyPair = generateKeys();
        System.out.println("Public Key: " + Bytes.wrap(keyPair.getPublic().getEncoded()).toHex());
        final BytesSigner jcaSigner = SignerFactory.createSigner(SigningType.ED25519_SUN, keyPair);
        final BytesSigner sodSigner = SignerFactory.createSigner(SigningType.ED25519_SODIUM, keyPair);
        final Bytes jcaSignature = jcaSigner.sign(TEST_DATA);
        final Bytes sodSignature = sodSigner.sign(TEST_DATA);

        Assertions.assertEquals(jcaSignature, sodSignature);

        final BytesVerifier jcaVerifier = new JcaVerifier(keyPair.getPublic(), SigningType.ED25519_SUN.getSigningAlgorithm(), SigningType.ED25519_SUN.getProvider());
        final BytesVerifier sodVerifier = new SodiumVerifier(keyPair.getPublic());

        assertTrue(jcaVerifier.verify(jcaSignature, TEST_DATA));
        assertTrue(sodVerifier.verify(sodSignature, TEST_DATA));

        assertTrue(jcaVerifier.verify(sodSignature, TEST_DATA));
        assertTrue(sodVerifier.verify(jcaSignature, TEST_DATA));

        Assertions.assertFalse(jcaVerifier.verify(sodSignature, Bytes.fromHex("abcd")));
        Assertions.assertFalse(sodVerifier.verify(jcaSignature, Bytes.fromHex("abcd")));
    }

    public static KeyPair fromRawEd25519Keys(final byte[] rawPublicKey, final byte[] rawPrivateKey) throws Exception {
        final byte[] x509Header = HexFormat.of().parseHex("302a300506032b6570032100");
        final byte[] x509Bytes = new byte[x509Header.length + rawPublicKey.length];
        System.arraycopy(x509Header, 0, x509Bytes, 0, x509Header.length);
        System.arraycopy(rawPublicKey, 0, x509Bytes, x509Header.length, rawPublicKey.length);

        final byte[] pkcs8Header = HexFormat.of().parseHex("302e020100300506032b657004220420");
        final byte[] pkcs8Bytes = new byte[pkcs8Header.length + rawPrivateKey.length];
        System.arraycopy(pkcs8Header, 0, pkcs8Bytes, 0, pkcs8Header.length);
        System.arraycopy(rawPrivateKey, 0, pkcs8Bytes, pkcs8Header.length, rawPrivateKey.length);

        final KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        final PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(x509Bytes));
        final PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));

        return new KeyPair(publicKey, privateKey);
    }

    public static KeyPair generateKeys() throws Exception {
        final SodiumJava sodium = new SodiumJava();
        final Sign.Native signer = new LazySodiumJava(sodium);

        final byte[] publicKey = new byte[Sign.PUBLICKEYBYTES];
        final byte[] privateKey = new byte[Sign.SECRETKEYBYTES];

        if(!signer.cryptoSignKeypair(publicKey, privateKey)){
            throw new RuntimeException("Keypair generation failed");
        }
        return fromRawEd25519Keys(publicKey, privateKey);
    }

    @Test
    void sodTest(){
        final SodiumJava sodium = new SodiumJava();
        final Sign.Native signer = new LazySodiumJava(sodium);

        final byte[] publicKey = new byte[Sign.PUBLICKEYBYTES];
        final byte[] privateKey = new byte[Sign.SECRETKEYBYTES];

        if(!signer.cryptoSignKeypair(publicKey, privateKey)){
            throw new RuntimeException("Keypair generation failed");
        }

        final byte[] sig = new byte[Sign.BYTES];
        if (!signer.cryptoSignDetached(sig, TEST_DATA.toByteArray(), TEST_DATA.length(), privateKey)) {
            throw new RuntimeException();
        }

        assertTrue(signer.cryptoSignVerifyDetached(sig, TEST_DATA.toByteArray(), Math.toIntExact(TEST_DATA.length()), publicKey));
        assertFalse(signer.cryptoSignVerifyDetached(sig, BAD_DATA.toByteArray(), Math.toIntExact(BAD_DATA.length()), publicKey));
    }


    @Test
    void test() throws NoSuchAlgorithmException, NoSuchProviderException {
//        for (var provider : Security.getProviders()) {
//            System.out.println(provider.getName());
//        }

//        String keyType = "Ed25519"; // Change to desired key type
//        for (Provider provider : Security.getProviders()) {
//            for (Provider.Service service : provider.getServices()) {
//                if ("KeyPairGenerator".equals(service.getType()) && keyType.equalsIgnoreCase(service.getAlgorithm())) {
//                    System.out.println(provider.getName() + " provides KeyPairGenerator for " + keyType);
//                }
//            }
//        }

        for (final SigningType signingType : SigningType.values()) {
            System.out.println("Type: " + signingType);
            final KeyPair keyPair = SignerFactory.generateKeyPair(signingType, new SecureRandom());
            final BytesSigner signer = SignerFactory.createSigner(signingType, keyPair);
            signer.sign(Bytes.fromHex("abcd1234"));
        }

    }
}