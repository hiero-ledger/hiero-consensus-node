// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static com.hedera.node.app.spi.fixtures.Scenarios.ALICE;
import static com.hedera.node.app.spi.fixtures.Scenarios.BOB;
import static com.hedera.node.app.spi.fixtures.Scenarios.CAROL;
import static com.hedera.node.app.spi.fixtures.Scenarios.ERIN;
import static com.hedera.node.app.spi.fixtures.Scenarios.FAKE_ECDSA_KEY_INFOS;
import static com.hedera.node.app.spi.fixtures.Scenarios.FAKE_ED25519_KEY_INFOS;
import static com.hedera.node.app.workflows.prehandle.FakeSignatureVerificationFuture.badFuture;
import static com.hedera.node.app.workflows.prehandle.FakeSignatureVerificationFuture.goodFuture;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.of;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Streams;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.hapi.utils.keys.KeyComparator;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.workflows.prehandle.FakeSignatureVerificationFuture;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultKeyVerifierTest {
    private static final Key ECDSA_X1 = FAKE_ECDSA_KEY_INFOS[1].publicKey();
    private static final Key ECDSA_X2 = FAKE_ECDSA_KEY_INFOS[2].publicKey();
    private static final Key ED25519_X1 = FAKE_ED25519_KEY_INFOS[1].publicKey();
    private static final Key ED25519_X2 = FAKE_ED25519_KEY_INFOS[2].publicKey();
    private static final Comparator<Key> KEY_COMPARATOR = new KeyComparator();

    private static final HederaConfig HEDERA_CONFIG =
            HederaTestConfigBuilder.createConfig().getConfigData(HederaConfig.class);

    @Mock
    VerificationAssistant verificationAssistant;

    @SuppressWarnings("ConstantConditions")
    @Test
    void testMethodsWithInvalidArguments() {
        // given
        final var keyVerifications = Map.<Key, SignatureVerificationFuture>of();
        final var verifier = createVerifier(keyVerifications);
        final var key = ALICE.keyInfo().publicKey();

        // then
        assertThatThrownBy(() -> new DefaultKeyVerifier(null, keyVerifications))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultKeyVerifier(HEDERA_CONFIG, null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> verifier.verificationFor((Key) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> verifier.verificationFor(null, verificationAssistant))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> verifier.verificationFor(key, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> verifier.verificationFor((Bytes) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void reportsNumSignsVerified() {
        final Map<Key, SignatureVerificationFuture> keyVerifications = new HashMap<>();
        final var key = ALICE.keyInfo().publicKey();
        keyVerifications.put(key, goodFuture(key));
        final var verifier = createVerifier(keyVerifications);
        assertThat(verifier.numSignaturesVerified()).isEqualTo(1);
    }

    /**
     * Tests to verify that finding a {@link SignatureVerification} for cryptographic keys (ED25519, ECDSA_SECP256K1)
     * work as expected. No key lists or threshold keys involved.
     */
    @Nested
    @DisplayName("Finding SignatureVerification With Cryptographic Keys")
    @ExtendWith(MockitoExtension.class)
    final class FindingSignatureVerificationWithCryptoKeyTests {
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("If there are no verification results, then the result is failed")
        void noVerificationResults(@NonNull final Key key) {
            final var result = createVerifier(Map.of());
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("If there are no verification results, then the result is failed")
        void noVerificationResultsWithAssistant(@NonNull final Key key) {
            final var result = createVerifier(Map.of());
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            verify(verificationAssistant).test(eq(key), any());
        }

        @Test
        @DisplayName("If the key is a cryptographic key in the results then it is returned")
        void cryptoKeyIsPresent() {
            final var aliceKey = ALICE.keyInfo().publicKey(); // ECDSA
            final var aliceVerification = mock(SignatureVerification.class);
            final var aliceFuture = new FakeSignatureVerificationFuture(aliceVerification);
            final var bobKey = BOB.keyInfo().publicKey(); // ED25519
            final var bobVerification = mock(SignatureVerification.class);
            final var bobFuture = new FakeSignatureVerificationFuture(bobVerification);
            final var verificationResults =
                    Map.<Key, SignatureVerificationFuture>of(aliceKey, aliceFuture, bobKey, bobFuture);
            final var result = createVerifier(verificationResults);

            when(verificationAssistant.test(aliceKey, aliceVerification)).thenReturn(true);
            when(verificationAssistant.test(bobKey, bobVerification)).thenReturn(false);

            assertThat(result.verificationFor(aliceKey)).isSameAs(aliceVerification);
            assertThat(result.verificationFor(bobKey)).isSameAs(bobVerification);

            assertThat(result.verificationFor(aliceKey, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(bobKey, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("If the key is a cryptographic key not in the results then null returned")
        void cryptoKeyIsMissing() {
            final var aliceKey = ALICE.keyInfo().publicKey(); // ECDSA
            final var aliceVerification = mock(SignatureVerification.class);
            final var aliceFuture = new FakeSignatureVerificationFuture(aliceVerification);
            final var bobKey = BOB.keyInfo().publicKey(); // ED25519
            final var bobVerification = mock(SignatureVerification.class);
            final var bobFuture = new FakeSignatureVerificationFuture(bobVerification);
            final var erinKey = ERIN.keyInfo().publicKey();
            final var verificationResults =
                    Map.<Key, SignatureVerificationFuture>of(aliceKey, aliceFuture, bobKey, bobFuture);
            final var result = createVerifier(verificationResults);

            // ERIN is another ECDSA key, but one that is not in the verification results
            assertThat(result.verificationFor(ERIN.keyInfo().publicKey()))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);

            when(verificationAssistant.test(eq(erinKey), any())).thenReturn(true);

            // ERIN is another ECDSA key, but one that is not in the verification results
            assertThat(result.verificationFor(erinKey, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        /** A provider that supplies basic cryptographic keys */
        static Stream<Arguments> provideCompoundKeys() {
            // FUTURE: Add RSA keys to this list
            return Stream.of(
                    Arguments.of(named("ED25519", FAKE_ED25519_KEY_INFOS[0].publicKey())),
                    Arguments.of(named("ECDSA_SECP256K1", FAKE_ECDSA_KEY_INFOS[0].publicKey())));
        }
    }

    @Nested
    @DisplayName("Only keys with valid signatures are returned by signingCryptoKeys()")
    class SigningCryptoKeysTests {
        @ParameterizedTest
        @MethodSource("variousValidityScenarios")
        void exactlyKeysWithValidKeysAreReturned(@NonNull final Map<Key, Boolean> keysAndPassFail) {
            final var subject = new DefaultKeyVerifier(HEDERA_CONFIG, verificationResults(keysAndPassFail));
            final var expectedKeys = keysAndPassFail.entrySet().stream()
                    .filter(Entry::getValue)
                    .map(Entry::getKey)
                    .collect(Collectors.toCollection(() -> new TreeSet<>(KEY_COMPARATOR)));
            final var actualKeys = subject.authorizingSimpleKeys();
            assertThat(actualKeys).isEqualTo(expectedKeys);
        }

        static Stream<Arguments> variousValidityScenarios() {
            return Stream.of(
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=fail, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, false,
                                    ED25519_X1, false,
                                    ED25519_X2, false))));
        }
    }

    /**
     * Tests to verify that finding a {@link SignatureVerification} for compound keys (threshold keys, key lists) that
     * also have duplicated keys. The point of these tests is really to verify that duplicate keys are counted multiple
     * times as expected when meeting threshold requirements.
     *
     * <p>We try testing all the boundary conditions:
     *      <ul>Just enough signatures and all are valid</ul>
     *      <ul>More than enough signatures but only a sufficient number are valid</ul>
     *      <ul>More than enough signatures and more than enough are valid</ul>
     *      <ul>More than enough signatures but not enough are valid</ul>
     *      <ul>Not enough signatures but all are valid</ul>
     * </ul>
     *
     * <p>And for those testing "more than needed" and "less than needed", we try to get right on the boundary condition
     * as well as all the other permutations.
     */
    @Nested
    @DisplayName("Finding SignatureVerification With Complex Keys with Duplicates")
    @ExtendWith(MockitoExtension.class)
    final class FindingSignatureVerificationWithDuplicateKeysTests {
        // ECDSA_X1 is used once in the key list
        // ECDSA_X2 is used twice in the key list
        // ED25519_X1 is used once in the key list
        // ED25519_X2 is used twice in the key list

        @BeforeEach
        void setup() {
            when(verificationAssistant.test(any(), any())).thenAnswer(invocation -> {
                final SignatureVerification verification = invocation.getArgument(1);
                return verification.passed();
            });
        }

        @Test
        @DisplayName("All signatures are valid for the KeyList")
        void allValidInKeyList() {
            // Given a KeyList with 6 different keys with 2 duplicates (4 unique keys) and
            // verification results for ALL 4 different keys that are PASSING
            final var keyList = KeyList.newBuilder()
                    .keys(ECDSA_X2, ECDSA_X2, ECDSA_X1, ED25519_X2, ED25519_X2, ED25519_X1)
                    .build();
            var key = Key.newBuilder().keyList(keyList).build();
            var verificationResults = verificationResults(Map.of(
                    ECDSA_X1, true,
                    ECDSA_X2, true,
                    ED25519_X1, true,
                    ED25519_X2, true));
            // When we pre handle
            var result = createVerifier(verificationResults);
            // Then we find the verification results are passing because we have all keys signed
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        /**
         * If there are just enough signatures to meet the threshold and all are valid signatures, then the overall
         * verification will pass.
         */
        @ParameterizedTest
        @MethodSource("provideJustEnoughSignaturesAndAllAreValid")
        @DisplayName("Just enough signatures and all are valid")
        void justEnoughAndAllAreValid(@NonNull final Map<Key, Boolean> keysAndPassFail) {
            // Given a ThresholdList with a threshold of 3 and 6 different keys with 2 duplicates (4 unique keys) and
            // verification results for only 2 keys (1 that is a duplicate, one that is not), so that the threshold is
            // met
            final var keyList = KeyList.newBuilder()
                    .keys(ECDSA_X1, ECDSA_X2, ECDSA_X2, ED25519_X1, ED25519_X2, ED25519_X2)
                    .build();
            final var thresholdKey =
                    ThresholdKey.newBuilder().threshold(3).keys(keyList).build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var verificationResults = verificationResults(keysAndPassFail);
            // When we pre handle
            final var result = createVerifier(verificationResults);
            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        static Stream<Arguments> provideJustEnoughSignaturesAndAllAreValid() {
            return Stream.of(
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X2=pass, ED25519_X1=pass",
                            Map.of(
                                    ECDSA_X2, true,
                                    ED25519_X1, true))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ED25519_X1, true,
                                    ED25519_X2, true))));
        }

        /**
         * If there are more than enough signatures, but only *just barely* enough signatures are valid that the
         * threshold is met, then the verification will still pass.
         */
        @ParameterizedTest
        @MethodSource("provideMoreThanEnoughAndJustEnoughValid")
        @DisplayName("More than enough signatures but only a sufficient number are valid")
        void moreThanEnoughAndJustEnoughValid(@NonNull final Map<Key, Boolean> keysAndPassFail) {
            // Given a ThresholdList with a threshold of 3 and 6 different keys with 2 duplicates (4 unique keys) and
            // verification results for 3 keys (1 that is a duplicate, two that are not), but only 2 of the three are
            // passing (where one of them is the duplicate), so that the threshold is met
            final var keyList = KeyList.newBuilder()
                    .keys(ECDSA_X1, ECDSA_X2, ECDSA_X2, ED25519_X1, ED25519_X2, ED25519_X2)
                    .build();
            final var thresholdKey =
                    ThresholdKey.newBuilder().threshold(3).keys(keyList).build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var verificationResults = verificationResults(keysAndPassFail);
            // When we pre handle
            final var result = createVerifier(verificationResults);
            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        static Stream<Arguments> provideMoreThanEnoughAndJustEnoughValid() {
            return Stream.of(
                    // Every key answers, but just enough are valid to pass
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=fail, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, false,
                                    ED25519_X1, true,
                                    ED25519_X2, true))),
                    // Some keys don't answer, but just enough are valid to pass
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, false))),
                    Arguments.of(named(
                            "ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ED25519_X1, true,
                                    ED25519_X2, true))),
                    // Some other keys don't answer, but just enough are valid to pass
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, true))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X2=fail, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X2, false,
                                    ED25519_X1, true,
                                    ED25519_X2, true))));
        }

        /**
         * More than enough signatures were provided, and more than were needed actually passed. The overall
         * verification therefore also passes.
         */
        @ParameterizedTest
        @MethodSource("provideMoreThanEnoughAndMoreThanNeededAreValid")
        @DisplayName("More than enough signatures and more than enough are valid")
        void moreThanEnoughAndMoreThanNeededAreValid(@NonNull final Map<Key, Boolean> keysAndPassFail) {
            // Given a ThresholdList with a threshold of 3 and 6 different keys with 2 duplicates (4 unique keys) and
            // verification results for 3 keys (1 that is a duplicate, two that are not), and all three are passing,
            // so that the threshold is met, plus more!
            final var keyList = KeyList.newBuilder()
                    .keys(ECDSA_X1, ECDSA_X2, ECDSA_X2, ED25519_X1, ED25519_X2, ED25519_X2)
                    .build();
            final var thresholdKey =
                    ThresholdKey.newBuilder().threshold(3).keys(keyList).build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var verificationResults = verificationResults(keysAndPassFail);
            // When we pre handle
            final var result = createVerifier(verificationResults);
            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        static Stream<Arguments> provideMoreThanEnoughAndMoreThanNeededAreValid() {
            return Stream.of(
                    // Every key answers, and all are valid
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, true))),

                    // Every key answers, one or more is invalid, but still more than we need
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X1, true,
                                    ED25519_X2, true))),

                    // Some keys don't answer, but all are valid (more than enough)
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X1=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X1, true))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, true,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X2=pass, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X2, true,
                                    ED25519_X1, true,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X2=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X2, true,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ED25519_X1=pass, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ED25519_X1, true,
                                    ED25519_X2, true))));
        }

        /**
         * In this test there are more than enough keys in the signature ot meet the threshold, if they all passed.
         * But it turns out, that enough of them did NOT pass, that the threshold is not met, and the overall
         * verification is therefore failed.
         */
        @ParameterizedTest
        @MethodSource("provideMoreThanEnoughButNotEnoughValid")
        @DisplayName("More than enough signatures but not enough are valid")
        void moreThanEnoughButNotEnoughValid(@NonNull final Map<Key, Boolean> keysAndPassFail) {
            // Given a ThresholdList with a threshold of 3 and 6 different keys with 2 duplicates (4 unique keys) and
            // verification results for 3 keys (1 that is a duplicate, two that are not), and only the two non-duplicate
            // keys are passing, so the threshold is NOT met.
            final var keyList = KeyList.newBuilder()
                    .keys(ECDSA_X1, ECDSA_X2, ECDSA_X2, ED25519_X1, ED25519_X2, ED25519_X2)
                    .build();
            final var thresholdKey =
                    ThresholdKey.newBuilder().threshold(3).keys(keyList).build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var verificationResults = verificationResults(keysAndPassFail);
            // When we pre handle
            final var result = createVerifier(verificationResults);
            // Then we find the verification results are NOT passing because we have NOT met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        static Stream<Arguments> provideMoreThanEnoughButNotEnoughValid() {
            return Stream.of(
                    // Every key answers, but not enough are valid
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X1, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=fail, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, false,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=fail, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, false,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),

                    // Some keys don't answer, and those that do don't cross the threshold
                    Arguments.of(named(
                            "ECDSA_X2=pass, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X2, true,
                                    ED25519_X1, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ED25519_X1=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ED25519_X1, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X1=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X1, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X1=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X1, false))),
                    Arguments.of(named(
                            "ECDSA_X2=fail, ED25519_X1=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X2, false,
                                    ED25519_X1, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X2=fail, ED25519_X1=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X2, false,
                                    ED25519_X1, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X1=pass",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X1, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=pass, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, true,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=pass, ECDSA_X2=fail, ED25519_X2=fail",
                            Map.of(
                                    ECDSA_X1, true,
                                    ECDSA_X2, false,
                                    ED25519_X2, false))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=fail, ED25519_X2=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, false,
                                    ED25519_X2, true))),
                    Arguments.of(named(
                            "ECDSA_X1=fail, ECDSA_X2=fail, ED25519_X1=pass",
                            Map.of(
                                    ECDSA_X1, false,
                                    ECDSA_X2, false,
                                    ED25519_X1, true))));
        }

        /**
         * In this test, every signature is valid, but there just are not enough signatures to meet the threshold,
         * so the overall verification must fail.
         */
        @ParameterizedTest
        @MethodSource("provideNotEnoughSignatures")
        @DisplayName("Not enough signatures but all are valid")
        void notEnoughSignatures(@NonNull final Map<Key, Boolean> keysAndPassFail) {
            // Given a ThresholdList with a threshold of 3 and 6 different keys with 2 duplicates (4 unique keys) and
            // there are only verification results for 1 key, which isn't enough to meet the threshold.
            final var keyList = KeyList.newBuilder()
                    .keys(ECDSA_X1, ECDSA_X2, ECDSA_X2, ED25519_X1, ED25519_X2, ED25519_X2)
                    .build();
            final var thresholdKey =
                    ThresholdKey.newBuilder().threshold(3).keys(keyList).build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var verificationResults = verificationResults(keysAndPassFail);
            // When we pre handle
            final var result = createVerifier(verificationResults);
            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        static Stream<Arguments> provideNotEnoughSignatures() {
            return Stream.of(
                    // Every key answers, but not enough are valid
                    Arguments.of(named("ECDSA_X1=pass", Map.of(ECDSA_X1, true))), // 1 of 3
                    Arguments.of(named("ECDSA_X2=pass", Map.of(ECDSA_X2, true))), // 2 of 3
                    Arguments.of(named("ED25519_X1=pass", Map.of(ED25519_X1, true))), // 1 of 3
                    Arguments.of(named("ED25519_X2=pass", Map.of(ED25519_X2, true))), // 2 of 3
                    Arguments.of(named(
                            "ECDSA_X1=pass, ED25519_X1=pass", Map.of(ECDSA_X1, true, ED25519_X1, true)))); // 2 of 3
        }
    }

    /**
     * Various targeted tests for {@link ThresholdKey} and {@link KeyList} lookup.
     */
    @Nested
    @DisplayName("Finding SignatureVerification With Threshold and KeyList Keys")
    @ExtendWith(MockitoExtension.class)
    final class FindingSignatureVerificationWithCompoundKeyTests {

        @BeforeEach
        void setup() {
            lenient().when(verificationAssistant.test(any(), any())).thenAnswer(invocation -> {
                final SignatureVerification verification = invocation.getArgument(1);
                return verification.passed();
            });
        }

        // A ThresholdKey with a threshold greater than max keys acts like a KeyList

        @Test
        @DisplayName("An empty KeyList never validates")
        void emptyKeyList() {
            // Given a KeyList with no keys
            final var keyList = KeyList.newBuilder().build();
            final var key = Key.newBuilder().keyList(keyList).build();
            // When we pre handle
            final var result = createVerifier(emptyMap());
            // Then we find the verification results will fail
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            verify(verificationAssistant, never()).test(any(), any());
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0})
        @DisplayName("A threshold of less than 1 is clamped to 1")
        void thresholdLessThanOne(final int threshold) {
            // Given a ThresholdKey with a threshold less than 1
            final var thresholdKey = ThresholdKey.newBuilder()
                    .threshold(threshold)
                    .keys(KeyList.newBuilder()
                            .keys(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[0].publicKey()))
                    .build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();

            // First, verify that if there are NO valid verification results the threshold verification fails
            Map<Key, SignatureVerificationFuture> verificationResults =
                    Map.of(FAKE_ECDSA_KEY_INFOS[1].publicKey(), goodFuture(FAKE_ECDSA_KEY_INFOS[1].publicKey()));
            var result = createVerifier(verificationResults);
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);

            // Now verify that if we verify with one valid verification result, the threshold verification passes
            verificationResults =
                    Map.of(FAKE_ECDSA_KEY_INFOS[0].publicKey(), goodFuture(FAKE_ECDSA_KEY_INFOS[0].publicKey()));
            // When we pre handle
            result = createVerifier(verificationResults);
            // Then we find the verification results will pass if we have at least 1 valid signature
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 1})
        @DisplayName("A threshold of less than 1 is clamped to 1")
        void thresholdWithEmptyKeylist(final int threshold) {
            // Given a ThresholdKey with a threshold less than 1
            final var thresholdKey = ThresholdKey.newBuilder()
                    .threshold(threshold)
                    .keys(KeyList.newBuilder().build())
                    .build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();

            // First, verify that if there are NO valid verification results the threshold verification fails
            Map<Key, SignatureVerificationFuture> verificationResults =
                    Map.of(FAKE_ECDSA_KEY_INFOS[1].publicKey(), goodFuture(FAKE_ECDSA_KEY_INFOS[1].publicKey()));
            var result = createVerifier(verificationResults);
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);

            // Now verify that if we verify with one valid verification result, the threshold verification fails
            verificationResults =
                    Map.of(FAKE_ECDSA_KEY_INFOS[0].publicKey(), goodFuture(FAKE_ECDSA_KEY_INFOS[0].publicKey()));
            // When we pre handle
            result = createVerifier(verificationResults);
            // Then we find the verification results will pass if we have at least 1 valid signature
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("A threshold greater than the number of keys is clamped to the number of keys")
        void thresholdGreaterThanNumKeys() {
            // Given a ThresholdKey with a threshold greater than the number of keys
            final var thresholdKey = ThresholdKey.newBuilder()
                    .threshold(3)
                    .keys(KeyList.newBuilder()
                            .keys(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[0].publicKey()))
                    .build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final Map<Key, SignatureVerificationFuture> verificationResults = Map.of(
                    FAKE_ECDSA_KEY_INFOS[0].publicKey(), goodFuture(FAKE_ECDSA_KEY_INFOS[0].publicKey()),
                    FAKE_ED25519_KEY_INFOS[0].publicKey(), goodFuture(FAKE_ED25519_KEY_INFOS[0].publicKey()));

            // When we pre handle
            var result = createVerifier(verificationResults);

            // Then we find the verification results will pass
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        /**
         * If there are no verification results at all, then no matter what key we throw at it, we should get back
         * a failed verification.
         */
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("A ThresholdKey or KeyList with no verification results returns a failed SignatureVerification")
        void keyWithNoVerificationResults(@NonNull final Key key) {
            final var result = createVerifier(emptyMap());
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        /**
         * If there are just enough signatures to meet the threshold and all are valid signatures, then the overall
         * verification will pass.
         */
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("Just enough signatures and all are valid")
        void justEnoughAndAllAreValid(@NonNull final Key key) {
            // Given a barely sufficient number of signatures, all of which are valid
            final var verificationResults = allVerifications(key);
            removeVerificationsFrom(key, verificationResults, false);

            // When we pre handle
            final var result = createVerifier(verificationResults);

            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        /**
         * If there are more than enough signatures, but only *just barely* enough signatures are valid that the
         * threshold is met, then the verification will still pass.
         */
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("More than enough signatures but only a sufficient number are valid")
        void moreThanEnoughAndJustEnoughValid(@NonNull final Key key) {
            // Given more than enough validations but just barely enough of them are valid
            final var verificationResults = allVerifications(key);
            failVerificationsIn(key, verificationResults, false);

            // When we pre handle
            final var result = createVerifier(verificationResults);

            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        /**
         * More than enough signatures were provided, and more than were needed actually passed. The overall
         * verification therefore also passes.
         */
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("More than enough signatures and more than enough are valid")
        void moreThanEnoughAndMoreThanNeededAreValid(@NonNull final Key key) {
            // Given more than enough validations but just barely enough of them are valid
            final Map<Key, SignatureVerificationFuture> verificationResults = allVerifications(key);

            // When we pre handle
            final var result = createVerifier(verificationResults);

            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(true);
        }

        /**
         * In this test there are more than enough keys in the signature ot meet the threshold, if they all passed.
         * But it turns out, that enough of them did NOT pass, that the threshold is not met, and the overall
         * verification is therefore failed.
         */
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("More than enough signatures but not enough are valid")
        void moreThanEnoughButNotEnoughValid(@NonNull final Key key) {
            // Given more than enough validations but not enough of them are valid
            final var verificationResults = allVerifications(key);
            failVerificationsIn(key, verificationResults, true);

            // When we pre handle
            final var result = createVerifier(verificationResults);

            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        /**
         * In this test, every signature is valid, but there just are not enough signatures to meet the threshold,
         * so the overall verification must fail.
         */
        @ParameterizedTest
        @MethodSource("provideCompoundKeys")
        @DisplayName("Not enough signatures but all are valid")
        void notEnoughSignatures(@NonNull final Key key) {
            // Given not enough signatures
            final var verificationResults = allVerifications(key);
            removeVerificationsFrom(key, verificationResults, true);

            // When we pre handle
            final var result = createVerifier(verificationResults);

            // Then we find the verification results are passing because we have met the minimum threshold
            assertThat(result.verificationFor(key))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
            assertThat(result.verificationFor(key, verificationAssistant))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        /** A provider that supplies a mixture of KeyLists and ThresholdKeys, all of which are good keys. */
        static Stream<Arguments> provideCompoundKeys() {
            // FUTURE: Add RSA keys to this list
            return Streams.concat(provideKeyLists(), provideThresholdKeys());
        }

        /**
         * Provides a comprehensive set of KeyLists, including with nesting of KeyLists and ThresholdKeys. At most, we
         * return a KeyList with a depth of 3 and with up to 4 elements, one for each type of key that we support. This
         * provider does not create duplicates, those scenarios are tested separately.
         */
        static Stream<Arguments> provideKeyLists() {
            return keyListPermutations().entrySet().stream()
                    .map(entry -> of(named(
                            "KeyList(" + entry.getKey() + ")",
                            Key.newBuilder().keyList(entry.getValue()).build())));
        }

        /**
         * A provider specifically for all permutations of a valid threshold key, including those with duplicate keys
         * and nesting.
         */
        static Stream<Arguments> provideThresholdKeys() {
            return keyListPermutations().entrySet().stream().map(entry -> {
                final var keys = entry.getValue().keys();
                final var threshold = Math.max(1, keys.size() / 2);
                final var thresholdKey = Key.newBuilder()
                        .thresholdKey(ThresholdKey.newBuilder()
                                .threshold(threshold)
                                .keys(KeyList.newBuilder().keys(keys)))
                        .build();
                return of(named("ThresholdKey(" + threshold + ", " + entry.getKey() + ")", thresholdKey));
            });
        }

        /** Generates the set of test permutations shared between KeyLists and ThresholdKeys. */
        private static Map<String, KeyList> keyListPermutations() {
            final var map = new LinkedHashMap<String, KeyList>();
            // FUTURE: Add RSA keys to this list
            final List<Function<Integer, Entry<String, Key>>> creators = List.of(
                    (i) -> Map.entry("ED25519", FAKE_ED25519_KEY_INFOS[i].publicKey()),
                    (i) -> Map.entry("ECDSA_SECP256K1", FAKE_ECDSA_KEY_INFOS[i].publicKey()),
                    (i) -> Map.entry(
                            "KeyList(ECDSA_SECP256K1, ED25519)",
                            keyList(FAKE_ECDSA_KEY_INFOS[i].publicKey(), FAKE_ED25519_KEY_INFOS[i].publicKey())),
                    (i) -> Map.entry(
                            "ThresholdKey(1, ED25519, ECDSA_SECP256K1)",
                            thresholdKey(
                                    1, FAKE_ED25519_KEY_INFOS[i].publicKey(), FAKE_ECDSA_KEY_INFOS[i].publicKey())));

            // Compute every permutation of 1, 2, 3, and 4 elements.
            for (int i = -1; i < 4; i++) {
                for (int j = -1; j < 4; j++) {
                    for (int k = -1; k < 4; k++) {
                        for (int el = 0; el < 4; el++) {
                            int keyIndex = 0;
                            final var names = new ArrayList<String>();
                            final var keys = new ArrayList<Key>();
                            if (i >= 0) {
                                final var entry = creators.get(i).apply(keyIndex++);
                                final var name = entry.getKey();
                                final var key = entry.getValue();
                                names.add(name);
                                keys.add(key);
                            }
                            if (j >= 0) {
                                final var entry = creators.get(j).apply(keyIndex++);
                                final var name = entry.getKey();
                                final var key = entry.getValue();
                                names.add(name);
                                keys.add(key);
                            }
                            if (k >= 0) {
                                final var entry = creators.get(k).apply(keyIndex++);
                                final var name = entry.getKey();
                                final var key = entry.getValue();
                                names.add(name);
                                keys.add(key);
                            }
                            final var entry = creators.get(el).apply(keyIndex);
                            final var name = entry.getKey();
                            final var key = entry.getValue();
                            names.add(name);
                            keys.add(key);

                            final var keyList = KeyList.newBuilder().keys(keys).build();
                            map.put(String.join(", ", names), keyList);
                        }
                    }
                }
            }
            return map;
        }

        /** Provides all {@link SignatureVerificationFuture}s for every cryptographic key in the {@link Key}. */
        private static Map<Key, SignatureVerificationFuture> allVerifications(@NonNull final Key key) {
            return switch (key.key().kind()) {
                case KEY_LIST -> allVerifications(key.keyListOrThrow());
                case THRESHOLD_KEY -> allVerifications(key.thresholdKeyOrThrow().keysOrThrow());
                case ED25519, ECDSA_SECP256K1 -> new HashMap<>(Map.of(key, goodFuture(key))); // make mutable
                default ->
                    throw new IllegalArgumentException(
                            "Unsupported key type: " + key.key().kind());
            };
        }

        /** Creates a {@link SignatureVerification} for each key in the key list */
        private static Map<Key, SignatureVerificationFuture> allVerifications(@NonNull final KeyList key) {
            return key.keys().stream()
                    .map(FindingSignatureVerificationWithCompoundKeyTests::allVerifications)
                    .flatMap(map -> map.entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        /**
         * Removes some number of {@link SignatureVerificationFuture}s from the map such that either there are only
         * just barely enough remaining to pass any threshold checks (if {@code removeTooMany} is {@code false}), or too
         * many of them such that there are not enough for threshold checks to pass (if {@code removeToMany} is
         * {@code true}).
         */
        private static void removeVerificationsFrom(
                @NonNull final Key key,
                @NonNull final Map<Key, SignatureVerificationFuture> map,
                final boolean removeTooMany) {

            switch (key.key().kind()) {
                case KEY_LIST -> {
                    // A Key list cannot have ANY removed and still pass. So we only remove a single key's worth of
                    // verifications if we are removing too many.
                    if (removeTooMany) {
                        final var subKeys = key.keyListOrThrow().keys();
                        final var subKey = subKeys.get(0);
                        removeVerificationsFrom(subKey, map, true);
                    }
                }
                case THRESHOLD_KEY -> {
                    // We remove verifications associated with keys. If we are removing too many, we remove one more
                    // than is supported by the threshold. Otherwise, we just remove down to the threshold
                    final var threshold = key.thresholdKeyOrThrow().threshold();
                    final var subKeys = key.thresholdKeyOrThrow().keysOrThrow().keys();
                    final var numToRemove = subKeys.size() - threshold + (removeTooMany ? 1 : 0);
                    for (int i = 0; i < numToRemove; i++) {
                        final var subKey = subKeys.get(i);
                        removeVerificationsFrom(subKey, map, removeTooMany);
                    }
                }
                case ED25519, ECDSA_SECP256K1 -> {
                    if (removeTooMany) {
                        map.remove(key);
                    }
                }
                default ->
                    throw new IllegalArgumentException(
                            "Unsupported key type: " + key.key().kind());
            }
        }

        /** Similar to the above, except we fail verifications instead of removing them. */
        private static void failVerificationsIn(
                @NonNull final Key key, @NonNull Map<Key, SignatureVerificationFuture> map, boolean failTooMany) {
            switch (key.key().kind()) {
                case KEY_LIST -> {
                    // A Key list cannot have ANY failed and still pass. So we only fail a single key's worth of
                    // verifications if we are failing too many.
                    if (failTooMany) {
                        final var subKeys = key.keyListOrThrow().keys();
                        final var subKey = subKeys.get(0);
                        failVerificationsIn(subKey, map, true);
                    }
                }
                case THRESHOLD_KEY -> {
                    // We fail verifications associated with keys. If we are failing too many, we fail one more
                    // than is supported by the threshold. Otherwise, we just fail down to the threshold
                    final var threshold = key.thresholdKeyOrThrow().threshold();
                    final var subKeys = key.thresholdKeyOrThrow().keysOrThrow().keys();
                    final var numToFail = subKeys.size() - threshold + (failTooMany ? 1 : 0);
                    for (int i = 0; i < numToFail; i++) {
                        final var subKey = subKeys.get(i);
                        failVerificationsIn(subKey, map, failTooMany);
                    }
                }
                case ED25519, ECDSA_SECP256K1 -> {
                    if (failTooMany) {
                        map.put(key, badFuture(key));
                    }
                }
                default ->
                    throw new IllegalArgumentException(
                            "Unsupported key type: " + key.key().kind());
            }
        }
    }

    @Nested
    @DisplayName("Hollow Account based Verification")
    final class HollowAccountBasedTest {
        /** As with key verification, with hollow account verification, an empty list of signatures should fail. */
        @Test
        @DisplayName("Cannot verify hollow account when the signature list is empty")
        void failToVerifyIfSignaturesAreEmpty() {
            // Given a hollow account and no verification results
            final var alias = ERIN.account().alias();
            // When we pre-handle the transaction
            final var result = createVerifier(emptyMap());
            // Then we find the verification result is failed
            assertThat(result.verificationFor(alias))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        /** If there are verifications but none for this hollow account, then we get no result */
        @Test
        @DisplayName("Cannot verify hollow account if it is not in the verification results")
        void failToVerifyIfHollowAccountIsNotInVerificationResults() {
            // Given a hollow account and no verification results
            final var alias = ERIN.account().alias();
            Map<Key, SignatureVerificationFuture> verificationResults = Map.of(
                    ALICE.keyInfo().publicKey(), goodFuture(ALICE.keyInfo().publicKey()),
                    BOB.keyInfo().publicKey(), goodFuture(BOB.keyInfo().publicKey()),
                    CAROL.keyInfo().publicKey(), goodFuture(CAROL.keyInfo().publicKey(), CAROL.account()));
            // When we pre-handle the transaction
            final var result = createVerifier(verificationResults);
            // Then we find the verification result is failed
            assertThat(result.verificationFor(alias))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(false);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Able to verify if the hollow account is in the verification results")
        void failToVerifyIfHollowAccountIsNotInVerificationResults(final boolean passes) {
            // Given a hollow account and no verification results
            final var alias = ERIN.account().alias();
            Map<Key, SignatureVerificationFuture> verificationResults = Map.of(
                    ALICE.keyInfo().publicKey(),
                    goodFuture(ALICE.keyInfo().publicKey()),
                    BOB.keyInfo().publicKey(),
                    goodFuture(BOB.keyInfo().publicKey()),
                    CAROL.keyInfo().publicKey(),
                    goodFuture(CAROL.keyInfo().publicKey(), CAROL.account()),
                    ERIN.keyInfo().publicKey(),
                    passes
                            ? goodFuture(ERIN.keyInfo().publicKey(), ERIN.account())
                            : badFuture(ERIN.keyInfo().publicKey(), ERIN.account()));
            // When we pre-handle the transaction
            final var result = createVerifier(verificationResults);
            // Then we find the verification result is as expected
            assertThat(result.verificationFor(alias))
                    .extracting(SignatureVerification::passed)
                    .isEqualTo(passes);
        }
    }

    private AppKeyVerifier createVerifier(@NonNull final Map<Key, SignatureVerificationFuture> map) {
        return new DefaultKeyVerifier(HEDERA_CONFIG, map);
    }

    /** Convenience method for creating a key list */
    private static Key keyList(Key... keys) {
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(keys)).build();
    }

    /** Convenience method for creating a threshold key */
    private static Key thresholdKey(int threshold, Key... keys) {
        return Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .keys(KeyList.newBuilder().keys(keys))
                        .threshold(threshold))
                .build();
    }

    private static Map<Key, SignatureVerificationFuture> verificationResults(Map<Key, Boolean> keysAndPassFail) {
        final var results = new HashMap<Key, SignatureVerificationFuture>();
        for (final var entry : keysAndPassFail.entrySet()) {
            results.put(
                    entry.getKey(),
                    new FakeSignatureVerificationFuture(
                            new SignatureVerificationImpl(entry.getKey(), null, entry.getValue())));
        }
        return results;
    }
}
