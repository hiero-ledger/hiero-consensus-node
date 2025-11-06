// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.test.signatures;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.signature.DefaultKeyVerifier;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.impl.SignatureExpanderImpl;
import com.hedera.node.app.signature.impl.SignatureVerifierImpl;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.fixtures.TestKeyInfo;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An integrated test to verify that the signature verification system works from end to end. Specifically, we want to
 * validate signature verification using real signed bytes and real signatures.
 */
class SignatureVerificationTest implements Scenarios {

    private static final long DEFAULT_CONFIG_VERSION = 1L;
    private static final VersionedConfiguration CONFIGURATION =
            new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), DEFAULT_CONFIG_VERSION);

    @Test
    @DisplayName("Verify Hollow Account")
    void verifyHollowAccount() {
        final var testCase = new TestCaseBuilder("Hollow Account", true)
                .keyToVerify(ERIN.keyInfo().publicKey())
                .ecdsa(ERIN.keyInfo(), true);

        // First, expand the signatures
        final var expanded = new HashSet<ExpandedSignaturePair>();
        final var expander = new SignatureExpanderImpl();
        expander.expand(testCase.signatureMap, expanded);

        // Second, verify the signatures
        //noinspection removal
        final var verifier = new SignatureVerifierImpl();
        final var verificationResults = verifier.verify(testCase.signedBytes, expanded);

        // Finally, assert that the verification results are as expected
        final var hederaConfig = CONFIGURATION.getConfigData(HederaConfig.class);
        final var handleContextVerifier = new DefaultKeyVerifier(hederaConfig, verificationResults);
        assertThat(handleContextVerifier.verificationFor(ERIN.account().alias()))
                .isNotNull()
                .extracting(SignatureVerification::passed)
                .isEqualTo(true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("Verify Signatures")
    void verifySignatures(
            @NonNull final Key keyToVerify,
            @NonNull final List<SignaturePair> signatureMap,
            @NonNull final Bytes signedBytes,
            final boolean shouldPass) {
        // First, expand the signatures
        final var expanded = new HashSet<ExpandedSignaturePair>();
        final var expander = new SignatureExpanderImpl();
        expander.expand(signatureMap, expanded);
        expander.expand(keyToVerify, signatureMap, expanded);

        // Second, verify the signatures
        //noinspection removal
        final var verifier = new SignatureVerifierImpl();
        final var verificationResults = verifier.verify(signedBytes, expanded);

        // Finally, assert that the verification results are as expected
        final var hederaConfig = CONFIGURATION.getConfigData(HederaConfig.class);
        final var handleContextVerifier = new DefaultKeyVerifier(hederaConfig, verificationResults);
        assertThat(handleContextVerifier.verificationFor(keyToVerify))
                .isNotNull()
                .extracting(SignatureVerification::passed)
                .isEqualTo(shouldPass);
    }

    /** Construct the test scenarios for verifying cryptographic signatures */
    public static Stream<Arguments> verifySignatures() {
        final var testCases = new ArrayList<Arguments>();

        testCases.add(testCase("ECDSA_SECP256K1", true)
                .keyToVerify(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0])
                .build());

        testCases.add(testCase("ECDSA_SECP256K1 no match", false)
                .keyToVerify(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[1])
                .build());

        testCases.add(testCase("ECDSA_SECP256K1 with full prefix", true)
                .keyToVerify(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0], true)
                .build());

        testCases.add(testCase("ECDSA_SECP256K1 with full prefix no match", false)
                .keyToVerify(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[1], true)
                .build());

        testCases.add(testCase("ECDSA_SECP256K1 with extra signatures", true)
                .keyToVerify(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0])
                .ecdsa(FAKE_ECDSA_KEY_INFOS[1])
                .ed25519(FAKE_ED25519_KEY_INFOS[0])
                .build());

        testCases.add(testCase("ECDSA_SECP256K1 with extra signatures no match", false)
                .keyToVerify(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[1])
                .ecdsa(FAKE_ECDSA_KEY_INFOS[2])
                .ed25519(FAKE_ED25519_KEY_INFOS[0])
                .build());

        testCases.add(testCase("ED25519", true)
                .keyToVerify(FAKE_ED25519_KEY_INFOS[0].publicKey())
                .ed25519(FAKE_ED25519_KEY_INFOS[0])
                .build());

        testCases.add(testCase("ED25519 no match", false)
                .keyToVerify(FAKE_ED25519_KEY_INFOS[0].publicKey())
                .ed25519(FAKE_ED25519_KEY_INFOS[1])
                .build());

        testCases.add(testCase("ED25519 with full prefix", true)
                .keyToVerify(FAKE_ED25519_KEY_INFOS[0].publicKey())
                .ed25519(FAKE_ED25519_KEY_INFOS[0], true)
                .build());

        testCases.add(testCase("ED25519 with full prefix no match", false)
                .keyToVerify(FAKE_ED25519_KEY_INFOS[0].publicKey())
                .ed25519(FAKE_ED25519_KEY_INFOS[1], true)
                .build());

        testCases.add(testCase("ED25519 with extra signatures", true)
                .keyToVerify(FAKE_ED25519_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0])
                .ecdsa(FAKE_ECDSA_KEY_INFOS[1])
                .ed25519(FAKE_ED25519_KEY_INFOS[0])
                .build());

        testCases.add(testCase("ED25519 with extra signatures no match", false)
                .keyToVerify(FAKE_ED25519_KEY_INFOS[0].publicKey())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[1])
                .ecdsa(FAKE_ECDSA_KEY_INFOS[2])
                .ed25519(FAKE_ED25519_KEY_INFOS[1])
                .build());

        testCases.add(testCase("KeyList", true)
                .keyToVerify(Key.newBuilder()
                        .keyList(KeyList.newBuilder()
                                .keys(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[0].publicKey())
                                .build())
                        .build())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0])
                .ed25519(FAKE_ED25519_KEY_INFOS[0])
                .build());

        testCases.add(testCase("KeyList missing a signature", false)
                .keyToVerify(Key.newBuilder()
                        .keyList(KeyList.newBuilder()
                                .keys(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[0].publicKey())
                                .build())
                        .build())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0])
                .build());

        testCases.add(testCase("ThresholdKey", true)
                .keyToVerify(Key.newBuilder()
                        .thresholdKey(ThresholdKey.newBuilder()
                                .threshold(3)
                                .keys(KeyList.newBuilder()
                                        .keys(
                                                FAKE_ECDSA_KEY_INFOS[0].publicKey(),
                                                FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                                FAKE_ECDSA_KEY_INFOS[1].publicKey(),
                                                FAKE_ED25519_KEY_INFOS[1].publicKey(),
                                                FAKE_ECDSA_KEY_INFOS[2].publicKey())
                                        .build())
                                .build())
                        .build())
                .ecdsa(FAKE_ECDSA_KEY_INFOS[0])
                .ed25519(FAKE_ED25519_KEY_INFOS[1])
                .ecdsa(FAKE_ECDSA_KEY_INFOS[2])
                .build());

        testCases.add(testCase("ThresholdKey not enough signatures", false)
                .keyToVerify(Key.newBuilder()
                        .thresholdKey(ThresholdKey.newBuilder()
                                .threshold(3)
                                .keys(KeyList.newBuilder()
                                        .keys(
                                                FAKE_ECDSA_KEY_INFOS[0].publicKey(),
                                                FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                                FAKE_ECDSA_KEY_INFOS[1].publicKey(),
                                                FAKE_ED25519_KEY_INFOS[1].publicKey(),
                                                FAKE_ECDSA_KEY_INFOS[2].publicKey())
                                        .build())
                                .build())
                        .build())
                .ed25519(FAKE_ED25519_KEY_INFOS[1])
                .ecdsa(FAKE_ECDSA_KEY_INFOS[2])
                .build());

        final var allKeys = new ArrayList<Key>();
        allKeys.addAll(
                Arrays.stream(FAKE_ECDSA_KEY_INFOS).map(TestKeyInfo::publicKey).toList());
        allKeys.addAll(Arrays.stream(FAKE_ED25519_KEY_INFOS)
                .map(TestKeyInfo::publicKey)
                .toList());
        allKeys.addAll(Arrays.stream(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS)
                .map(TestKeyInfo::publicKey)
                .toList());
        var testCaseBuilder = testCase("KeyList with many ECDSA_SECP256K1 and ED25519", true)
                .keyToVerify(Key.newBuilder()
                        .keyList(KeyList.newBuilder().keys(allKeys).build())
                        .build());

        for (var keyInfo : FAKE_ECDSA_KEY_INFOS) {
            testCaseBuilder.ecdsa(keyInfo);
        }
        for (var keyInfo : FAKE_ED25519_KEY_INFOS) {
            testCaseBuilder.ed25519(keyInfo);
        }
        for (var keyInfo : FAKE_ECDSA_WITH_ALIAS_KEY_INFOS) {
            testCaseBuilder.ecdsa(keyInfo);
        }
        testCases.add(testCaseBuilder.build());

        testCaseBuilder = testCase("ThresholdKey with ECDSA_SECP256K1 and ED25519", true)
                .keyToVerify(Key.newBuilder()
                        .thresholdKey(ThresholdKey.newBuilder()
                                .threshold(FAKE_ECDSA_KEY_INFOS.length
                                        + FAKE_ED25519_KEY_INFOS.length
                                        + FAKE_ECDSA_WITH_ALIAS_KEY_INFOS.length)
                                .keys(KeyList.newBuilder().keys(allKeys).build())
                                .build())
                        .build());

        for (var keyInfo : FAKE_ECDSA_KEY_INFOS) {
            testCaseBuilder.ecdsa(keyInfo);
        }
        for (var keyInfo : FAKE_ED25519_KEY_INFOS) {
            testCaseBuilder.ed25519(keyInfo);
        }
        for (var keyInfo : FAKE_ECDSA_WITH_ALIAS_KEY_INFOS) {
            testCaseBuilder.ecdsa(keyInfo);
        }
        testCases.add(testCaseBuilder.build());

        return testCases.stream();
    }

    /** Utility for creating a test case */
    private static TestCaseBuilder testCase(@NonNull final String name, final boolean shouldPass) {
        return new TestCaseBuilder(name, shouldPass);
    }

    /** Utility builder class for constructing the test cases */
    private static final class TestCaseBuilder {
        private static final X9ECParameters ECDSA_SECP256K1_CURVE = SECNamedCurves.getByName("secp256k1");
        private static final ECDomainParameters ECDSA_SECP256K1_DOMAIN = new ECDomainParameters(
                ECDSA_SECP256K1_CURVE.getCurve(),
                ECDSA_SECP256K1_CURVE.getG(),
                ECDSA_SECP256K1_CURVE.getN(),
                ECDSA_SECP256K1_CURVE.getH());

        private final Bytes signedBytes = Bytes.wrap("Hashgraph is revolutionary technology");
        private final String name;
        private final boolean shouldPass;
        private final List<SignaturePair> signatureMap = new LinkedList<>();
        private Key keyToVerify;

        /** Create an instance */
        TestCaseBuilder(@NonNull final String name, final boolean shouldPass) {
            this.name = requireNonNull(name);
            this.shouldPass = shouldPass;
        }

        /** Add the key that is to be verified */
        TestCaseBuilder keyToVerify(@NonNull final Key key) {
            keyToVerify = requireNonNull(key);
            return this;
        }

        /** Add an ED25519 key to the signature map with a 6 byte prefix */
        TestCaseBuilder ed25519(@NonNull final TestKeyInfo keyInfo) {
            return ed25519(keyInfo, false);
        }

        /** Add an ECDSA_SECP256K1 key to the signature map with a 6 byte prefix */
        TestCaseBuilder ecdsa(@NonNull final TestKeyInfo keyInfo) {
            return ecdsa(keyInfo, false);
        }

        /** Add an ED25519 key to the signature map with a 6 byte prefix OR the full prefix */
        TestCaseBuilder ed25519(@NonNull final TestKeyInfo keyInfo, final boolean fullPrefix) {
            final var signature = sign(keyInfo, signedBytes);
            final var prefixKey = keyInfo.publicKey().ed25519OrThrow();
            signatureMap.add(SignaturePair.newBuilder()
                    .pubKeyPrefix(fullPrefix ? prefixKey : prefixKey.slice(0, 6))
                    .ed25519(signature)
                    .build());
            return this;
        }

        /** Add an ECDSA_SECP256K1 key to the signature map with a 6 byte prefix OR the full prefix */
        TestCaseBuilder ecdsa(@NonNull final TestKeyInfo keyInfo, final boolean fullPrefix) {
            final var signature = sign(keyInfo, signedBytes);
            final var prefixKey = keyInfo.publicKey().ecdsaSecp256k1OrThrow();
            signatureMap.add(SignaturePair.newBuilder()
                    .pubKeyPrefix(fullPrefix ? prefixKey : prefixKey.slice(0, 6))
                    .ecdsaSecp256k1(signature)
                    .build());
            return this;
        }

        // Build the JUnit5 Arguments
        Arguments build() {
            // Convert the keyToVerify to a nice String name, such that we display only the first 5 bytes, ..., and last
            // 5 bytes of each key.
            final var signatureMapDisplayName = signatureMap.stream()
                    .map(pair -> {
                        final var signatureType = pair.hasEd25519() ? "ED25519" : "ECDSA_SECP256K1";
                        final var signature = pair.hasEd25519() ? pair.ed25519OrThrow() : pair.ecdsaSecp256k1OrThrow();
                        return "(prefix=" + hex(pair.pubKeyPrefix()) + ", signatureType=" + signatureType
                                + ", signature=" + shortHex(signature) + ")";
                    })
                    .collect(Collectors.joining(", ", "[", "]"));

            final var displayName = String.format(
                    "%s ===> Key To Verify = %s; Signature Map = %s; Should Pass = %s",
                    name, asString(keyToVerify), signatureMapDisplayName, shouldPass);
            return Arguments.of(Named.of(displayName, keyToVerify), signatureMap, signedBytes, shouldPass);
        }

        /** Utility for signing a message with a key */
        private Bytes sign(@NonNull final TestKeyInfo keyInfo, @NonNull final Bytes message) {
            return switch (keyInfo.publicKey().key().kind()) {
                case ED25519 -> signEd25519(keyInfo.privateKey(), message);
                case ECDSA_SECP256K1 -> signEcdsaSecp256k1(keyInfo.privateKey(), message);
                default ->
                    throw new IllegalArgumentException(
                            "Unsupported key type: " + keyInfo.publicKey().key().kind());
            };
        }

        /** Signs using ECDSA_SECP256K1 key */
        private Bytes signEcdsaSecp256k1(@NonNull final Bytes privateKeyBytes, @NonNull final Bytes message) {
            final var keyByteArray = new byte[(int) privateKeyBytes.length()];
            privateKeyBytes.getBytes(0, keyByteArray);

            final var messageByteArray = new byte[(int) message.length()];
            message.getBytes(0, messageByteArray);

            final var digest = new Keccak.Digest256();
            digest.update(messageByteArray);
            final var hash = digest.digest();

            final var signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, keyByteArray), ECDSA_SECP256K1_DOMAIN));
            final var bigSig = signer.generateSignature(hash);
            final var sigBytes = Arrays.copyOf(bigIntTo32Bytes(bigSig[0]), 64);
            System.arraycopy(bigIntTo32Bytes(bigSig[1]), 0, sigBytes, 32, 32);
            return Bytes.wrap(sigBytes);
        }

        /** Signs using ED25519 key */
        private Bytes signEd25519(@NonNull final Bytes privateKeyBytes, @NonNull final Bytes message) {
            try {
                final var keyByteArray = new byte[(int) privateKeyBytes.length()];
                privateKeyBytes.getBytes(0, keyByteArray);

                final var messageByteArray = new byte[(int) message.length()];
                message.getBytes(0, messageByteArray);

                final var keyFactory = KeyFactory.getInstance("EdDSA");
                final var privateKeySpec = new EdECPrivateKeySpec(NamedParameterSpec.ED25519, keyByteArray);
                final var privateKey = keyFactory.generatePrivate(privateKeySpec);
                final var sig = Signature.getInstance("Ed25519");
                sig.initSign(privateKey);
                sig.update(messageByteArray);
                return Bytes.wrap(sig.sign());
            } catch (Exception e) {
                throw new AssertionError("Unable to sign ED25519 key", e);
            }
        }

        /** Nonsense needed for ECDSA_SECP256K1 signing */
        private static byte[] bigIntTo32Bytes(BigInteger n) {
            byte[] bytes = n.toByteArray();
            byte[] bytes32 = new byte[32];
            System.arraycopy(
                    bytes,
                    Math.max(0, bytes.length - 32),
                    bytes32,
                    Math.max(0, 32 - bytes.length),
                    Math.min(32, bytes.length));
            return bytes32;
        }

        /** A Utility for converting a key to a nice String representation for test output */
        private static String asString(@NonNull final Key key) {
            return switch (key.key().kind()) {
                case ED25519 -> shortHex(key.ed25519OrThrow());
                case ECDSA_SECP256K1 -> shortHex(key.ecdsaSecp256k1OrThrow());
                case KEY_LIST -> {
                    final var keyList = key.keyListOrThrow().keys();
                    yield "KeyList("
                            + keyList.stream().map(TestCaseBuilder::asString).collect(Collectors.joining(", ")) + ")";
                }
                case THRESHOLD_KEY -> {
                    final var thresholdKey = key.thresholdKeyOrThrow();
                    final var threshold = thresholdKey.threshold();
                    yield "ThresholdKey(" + threshold + ", "
                            + thresholdKey.keysOrThrow().keys().stream()
                                    .map(TestCaseBuilder::asString)
                                    .collect(Collectors.joining(", "))
                            + ")";
                }
                case CONTRACT_ID, DELEGATABLE_CONTRACT_ID, ECDSA_384, RSA_3072, UNSET ->
                    throw new IllegalArgumentException(
                            "Unsupported key type: " + key.key().kind());
            };
        }

        /** A Utility for converting Bytes into a String hex */
        private static String hex(@NonNull final Bytes bytes) {
            StringBuilder sb = new StringBuilder((int) bytes.length() * 2);
            for (int i = 0; i < bytes.length(); i++) {
                sb.append(String.format("%02x", bytes.getByte(i)));
            }
            return sb.toString();
        }

        /** A Utility for converting Bytes into a short String hex */
        private static String shortHex(@NonNull final Bytes bytes) {
            final var full = hex(bytes);
            return full.substring(0, 12) + "..." + full.substring(full.length() - 6);
        }
    }
}
