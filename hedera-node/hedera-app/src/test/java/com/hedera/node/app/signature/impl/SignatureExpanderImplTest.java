// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.extractEvmAddressFromDecompressedECDSAKey;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import com.google.common.collect.Streams;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.fixtures.TestKeyInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class SignatureExpanderImplTest extends AppTestBase implements Scenarios {
    private static final int PREFIX_LENGTH = 10;
    private final SignatureExpanderImpl subject = new SignatureExpanderImpl();

    @Nested
    @DisplayName("Expand Full Key Prefixes")
    final class ExpandFullKeyPrefixes {

        @Test
        @DisplayName("Null args are not allowed")
        @SuppressWarnings("DataFlowIssue")
        void nullArgs() {
            final List<SignaturePair> sigPairs = emptyList();
            final Set<ExpandedSignaturePair> expanded = emptySet();
            assertThatThrownBy(() -> subject.expand(null, expanded)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.expand(sigPairs, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Empty prefixes ignored on expansion")
        void emptyPrefixes() {
            // Given a list of sig pairs where there are empty prefixes
            final List<SignaturePair> sigPairs = List.of(
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 0),
                    ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 0),
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 32));

            // When we expand them,
            final Set<ExpandedSignaturePair> expanded = new HashSet<>();
            subject.expand(sigPairs, expanded);

            // Then we find empty prefixes are skipped
            assertThat(expanded)
                    .containsExactlyInAnyOrderElementsOf(Set.of(ed25519Expanded(FAKE_ED25519_KEY_INFOS[1])));
        }

        @ParameterizedTest
        @MethodSource("provideFullKeyPrefixSignaturePairs")
        @DisplayName("Every \"full\" key prefix expands to a corresponding expanded key")
        void expandsSignaturesWithFullPrefix(
                @NonNull final List<SignaturePair> sigPairs, @NonNull final Set<ExpandedSignaturePair> expected) {
            // Given signature pairs where at least some have full key prefixes,
            // When we expand them,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(sigPairs, expanded);
            // Then we find the expanded set matches the expected set.
            assertThat(expanded).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("Expected \"full\" ECDSA_SECP256K1 prefixes include the evm alias")
        void expandsEvmAlias() {
            // Given a list of sig pairs with an ECDSA_SECP256K1 prefix,
            final var prefix = FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].publicKey().ecdsaSecp256k1OrThrow();
            final var signature = randomBytes(64);
            final var sigPairs = List.of(SignaturePair.newBuilder()
                    .pubKeyPrefix(prefix)
                    .ecdsaSecp256k1(signature)
                    .build());

            // When we expand them,
            final Set<ExpandedSignaturePair> expanded = new HashSet<>();
            subject.expand(sigPairs, expanded);

            // Then we find the EVM address has been computed properly
            assertThat(expanded)
                    .containsExactlyInAnyOrderElementsOf(Set.of(new ExpandedSignaturePair(
                            FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].publicKey(),
                            FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0]
                                    .uncompressedPublicKey()
                                    .ecdsaSecp256k1OrThrow(),
                            FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].alias(),
                            sigPairs.get(0))));
        }

        /** Provides a stream of arguments for the {@link #expandsSignaturesWithFullPrefix(List, Set)} test. */
        static Stream<Arguments> provideFullKeyPrefixSignaturePairs() {
            return Stream.of(
                    Arguments.of(
                            named(
                                    "[Full, Prefix, Prefix]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 32),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 3),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 3))),
                            named("[First]", Set.of(ed25519Expanded(FAKE_ED25519_KEY_INFOS[0])))),
                    Arguments.of(
                            named(
                                    "[Prefix, Full, Prefix]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 3),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 33),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 3))),
                            named("[Middle]", Set.of(ecdsaExpanded(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0])))),
                    Arguments.of(
                            named(
                                    "[Prefix, Prefix, Full]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 3),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 3),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 32))),
                            named("[Last]", Set.of(ed25519Expanded(FAKE_ED25519_KEY_INFOS[1])))),
                    Arguments.of(
                            named(
                                    "[Full, Full, Prefix]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 32),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 33),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 3))),
                            named(
                                    "[First, Middle]",
                                    Set.of(
                                            ed25519Expanded(FAKE_ED25519_KEY_INFOS[0]),
                                            ecdsaExpanded(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0])))),
                    Arguments.of(
                            named(
                                    "[Full, Prefix, Full]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 32),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 3),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 32))),
                            named(
                                    "[First, Last]",
                                    Set.of(
                                            ed25519Expanded(FAKE_ED25519_KEY_INFOS[0]),
                                            ed25519Expanded(FAKE_ED25519_KEY_INFOS[1])))),
                    Arguments.of(
                            named(
                                    "[Prefix, Full, Full]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 3),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 33),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 32))),
                            named(
                                    "[Middle, Last]",
                                    Set.of(
                                            ecdsaExpanded(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0]),
                                            ed25519Expanded(FAKE_ED25519_KEY_INFOS[1])))),
                    Arguments.of(
                            named(
                                    "[Full, Full, Full]",
                                    List.of(
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[0], 32),
                                            ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0], 33),
                                            ed25519Sig(FAKE_ED25519_KEY_INFOS[1], 32))),
                            named(
                                    "[First, Middle, Last]",
                                    Set.of(
                                            ed25519Expanded(FAKE_ED25519_KEY_INFOS[0]),
                                            ecdsaExpanded(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0]),
                                            ed25519Expanded(FAKE_ED25519_KEY_INFOS[1])))));
        }

        /** Utility to create an ExpandedSignaturePair for an ED25519 key prefix */
        private static ExpandedSignaturePair ed25519Expanded(@NonNull final TestKeyInfo keyInfo) {
            final var key = keyInfo.publicKey();
            final var sigPair = ed25519Sig(keyInfo, 32);
            return new ExpandedSignaturePair(key, key.ed25519OrThrow(), null, sigPair);
        }

        /** Utility to create an ExpandedSignaturePair for an ECDSA_SECP256K1 key prefix */
        private static ExpandedSignaturePair ecdsaExpanded(@NonNull final TestKeyInfo keyInfo) {
            final var key = keyInfo.publicKey();
            final var sigPair = ecdsaSig(keyInfo, 33);
            final var compressed = key.ecdsaSecp256k1OrThrow();
            final var array = new byte[(int) compressed.length()];
            compressed.getBytes(0, array);
            final var decompressed = MiscCryptoUtils.decompressSecp256k1(array);
            return new ExpandedSignaturePair(key, Bytes.wrap(decompressed), keyInfo.alias(), sigPair);
        }
    }

    @Nested
    @DisplayName("Expand Partial Key Prefixes")
    final class ExpandPartialKeyPrefixes {
        @Test
        @DisplayName("Null args are not allowed")
        @SuppressWarnings("DataFlowIssue")
        void nullArgs() {
            final List<SignaturePair> sigPairs = emptyList();
            final Set<ExpandedSignaturePair> expanded = emptySet();
            assertThatThrownBy(() -> subject.expand((Key) null, sigPairs, expanded))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.expand(Key.DEFAULT, null, expanded))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.expand(Key.DEFAULT, sigPairs, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys when the signature list is empty expands to empty expanded")
        void expansionWithEmptyList(@NonNull final Key key) {
            // Given a key, when we expand it with an empty signature list,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, emptyList(), expanded);
            // Then we find the expanded set is empty.
            assertThat(expanded).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys when the signature list has no corresponding prefix expands to empty expanded")
        void keysWithNoPrefix(@NonNull final Key key) {
            // Given a key with no corresponding prefixes in the signature list,
            final List<SignaturePair> sigList =
                    List.of(ed25519Sig("bad1", 4), ecdsaSig("bad2", 4), ed25519Sig("bad3", 4));
            // When we expand it,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);
            // Then we find the expanded set is empty.
            assertThat(expanded).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys when the signature list has a prefix that would match but is too big")
        void keysWithPrefixButTooBig(@NonNull final Key key) {
            // Given a key and a signature list where the prefixes are valid but have too many bytes at the end,
            final var sigList = sufficientSignatures(key, true).stream()
                    .map(pair -> new SignaturePair(join(pair.pubKeyPrefix(), randomBytes(1)), pair.signature()))
                    .toList();

            // When we expand it,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);

            // Then we find the expanded set is empty.
            assertThat(expanded).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys when the signature list has matching prefixes BUT wrong type")
        void keysWithPrefixButWrongType(@NonNull final Key key) {
            // Given a key and a signature list where the prefixes are valid but have too many bytes at the end,
            final var sigList = sufficientSignatures(key, false).stream()
                    .map(pair -> {
                        final var builder = pair.copyBuilder();
                        if (pair.signature().kind() == SignatureOneOfType.ED25519) {
                            builder.ecdsaSecp256k1(pair.ed25519OrThrow());
                        } else {
                            builder.ed25519(pair.ecdsaSecp256k1OrThrow());
                        }
                        return builder.build();
                    })
                    .toList();

            // When we expand it,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);

            // Then we find the expanded set is empty.
            assertThat(expanded).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys when the signature list has matching partial prefixes expands the keys")
        void keysWithPartialPrefix(@NonNull final Key key) {
            // Given a key and a signature list where the prefixes are valid and partial
            final var sigList = sufficientSignatures(key, false);

            // When we expand it,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);

            // Then we find the expanded set contains the expected expanded keys.
            assertThat(expanded).hasSameSizeAs(sigList);
            assertThat(expanded.stream().map(ExpandedSignaturePair::sigPair))
                    .containsExactlyInAnyOrderElementsOf(sigList);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys when the signature list has matching full prefixes expands the keys")
        void keysWithFullPrefix(@NonNull final Key key) {
            // Given a key and a signature list where the prefixes are valid and partial
            final var sigList = sufficientSignatures(key, true);

            // When we expand it,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);

            // Then we find the expanded set contains the expected expanded keys.
            assertThat(expanded).hasSameSizeAs(sigList);
            assertThat(expanded.stream().map(ExpandedSignaturePair::sigPair))
                    .containsExactlyInAnyOrderElementsOf(sigList);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.node.app.signature.impl.SignatureExpanderImplTest#provideMixOfAllKindsOfKeys")
        @DisplayName("Expanding keys only picks up those keys with matching prefixes")
        void keysWithNotEveryNeededPrefix(@NonNull final Key key) {
            // Given a key and a signature list where the prefixes are valid and partial
            final var sigList = insufficientSignatures(key, false);

            // When we expand it,
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);

            // Then we find the expanded set only contains those keys we had in prefixes
            assertThat(expanded).hasSameSizeAs(sigList);
            assertThat(expanded.stream().map(ExpandedSignaturePair::sigPair))
                    .containsExactlyInAnyOrderElementsOf(sigList);
        }

        @Test
        @DisplayName(
                "Expanding with complex key structure and mixture of signatures only picks up those keys with matching prefixes")
        void expansion() {
            // Given a complex key structure and a signature list where the prefixes are valid and a mix of partial
            // and full prefixes, and includes prefixes that DO NOT get used,
            final var key = thresholdKey(
                    3,
                    keyList(FAKE_ED25519_KEY_INFOS[0].publicKey(), FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].publicKey()),
                    FAKE_ED25519_KEY_INFOS[2].publicKey(),
                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[2].publicKey(),
                    thresholdKey(
                            1,
                            FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[1].publicKey(),
                            FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[1].publicKey()));

            final var sigList = List.of(
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[0].publicKey(), false),
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[1].publicKey(), true),
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[2].publicKey(), false),
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[3].publicKey(), true),
                    ed25519Sig(FAKE_ED25519_KEY_INFOS[4].publicKey(), false),
                    ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].publicKey(), true),
                    ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[1].publicKey(), false),
                    ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[2].publicKey(), true),
                    ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[3].publicKey(), false),
                    ecdsaSig(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[4].publicKey(), true));

            // When we expand the signatures
            final var expanded = new HashSet<ExpandedSignaturePair>();
            subject.expand(key, sigList, expanded);

            // Then we find the expanded set only contains those keys we had in prefixes
            assertThat(expanded)
                    .containsExactlyInAnyOrder(
                            new ExpandedSignaturePair(
                                    FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                    FAKE_ED25519_KEY_INFOS[0]
                                            .uncompressedPublicKey()
                                            .ed25519OrThrow(),
                                    null,
                                    sigList.get(0)),
                            new ExpandedSignaturePair(
                                    FAKE_ED25519_KEY_INFOS[2].publicKey(),
                                    FAKE_ED25519_KEY_INFOS[2]
                                            .uncompressedPublicKey()
                                            .ed25519OrThrow(),
                                    null,
                                    sigList.get(2)),
                            new ExpandedSignaturePair(
                                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].publicKey(),
                                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0]
                                            .uncompressedPublicKey()
                                            .ecdsaSecp256k1OrThrow(),
                                    Bytes.wrap(
                                            extractEvmAddressFromDecompressedECDSAKey(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0]
                                                    .uncompressedPublicKey()
                                                    .ecdsaSecp256k1OrThrow()
                                                    .toByteArray())),
                                    sigList.get(5)),
                            new ExpandedSignaturePair(
                                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[1].publicKey(),
                                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[1]
                                            .uncompressedPublicKey()
                                            .ecdsaSecp256k1OrThrow(),
                                    Bytes.wrap(
                                            extractEvmAddressFromDecompressedECDSAKey(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[1]
                                                    .uncompressedPublicKey()
                                                    .ecdsaSecp256k1OrThrow()
                                                    .toByteArray())),
                                    sigList.get(6)),
                            new ExpandedSignaturePair(
                                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[2].publicKey(),
                                    FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[2]
                                            .uncompressedPublicKey()
                                            .ecdsaSecp256k1OrThrow(),
                                    Bytes.wrap(
                                            extractEvmAddressFromDecompressedECDSAKey(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[2]
                                                    .uncompressedPublicKey()
                                                    .ecdsaSecp256k1OrThrow()
                                                    .toByteArray())),
                                    sigList.get(7)));
        }
    }

    /** A provider that supplies a mixture of all kinds of keys, all of which are good keys. */
    static Stream<Arguments> provideMixOfAllKindsOfKeys() {
        // FUTURE: Add RSA keys to this list
        return Streams.concat(
                Stream.of(
                        // Single element keys
                        Arguments.of(named("ED25519", FAKE_ED25519_KEY_INFOS[0].publicKey())),
                        Arguments.of(named("ECDSA_SECP256K1", FAKE_ECDSA_KEY_INFOS[0].publicKey()))),
                provideKeyLists(),
                provideThresholdKeys());
    }

    /**
     * A provider specifically for all permutations of a valid key list, including those with duplicate keys
     * and nesting.
     */
    static Stream<Arguments> provideKeyLists() {
        // FUTURE: Add RSA keys to this list
        return Stream.of(
                // Single element key lists of different key types
                Arguments.of(named("KeyList(ED25519)", keyList(FAKE_ED25519_KEY_INFOS[0].publicKey()))),
                Arguments.of(named("KeyList(ECDSA_SECP256K1)", keyList(FAKE_ECDSA_KEY_INFOS[0].publicKey()))),

                // Multiple element key lists of mixed types (unique keys)
                Arguments.of(named(
                        "KeyList(ED25519, ECDSA_SECP256K1)",
                        keyList(FAKE_ED25519_KEY_INFOS[0].publicKey(), FAKE_ECDSA_KEY_INFOS[0].publicKey()))),

                // KeyList with duplicate keys
                Arguments.of(named(
                        "KeyList(ED25519(dup), ED25519(dup))",
                        keyList(FAKE_ED25519_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[0].publicKey()))),

                // KeyList with unique keys of the same type
                Arguments.of(named(
                        "KeyList(ED25519, ED25519)",
                        keyList(FAKE_ED25519_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[1].publicKey()))),
                Arguments.of(named(
                        "KeyList(ECDSA_SECP256K1, ECDSA_SECP256K1)",
                        keyList(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ECDSA_KEY_INFOS[1].publicKey()))),

                // Nested key lists
                Arguments.of(named(
                        "KeyList(ED25519, KeyList(ECDSA_SECP256K1, ECDSA_SECP256K1), ECDSA_SECP256K1)",
                        keyList(
                                FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                keyList(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ECDSA_KEY_INFOS[1].publicKey()),
                                FAKE_ECDSA_KEY_INFOS[2].publicKey()))),

                // Nested key lists with duplicate keys
                Arguments.of(named(
                        "KeyList(ED25519, KeyList(ECDSA_SECP256K1(dup), ECDSA_SECP256K1), ECDSA_SECP256K1(dup))",
                        keyList(
                                FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                keyList(FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ECDSA_KEY_INFOS[1].publicKey()),
                                FAKE_ECDSA_KEY_INFOS[0].publicKey()))),

                // Key lists with threshold keys
                Arguments.of(named(
                        "KeyList(ED25519, ThresholdKey(1, ECDSA_SECP256K1, ED25519), ECDSA_SECP256K1)",
                        keyList(
                                FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                thresholdKey(
                                        1, FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[1].publicKey()),
                                FAKE_ECDSA_KEY_INFOS[2].publicKey()))),

                // Key lists with threshold keys with duplicates
                Arguments.of(named(
                        "KeyList(ED25519(dup), ThresholdKey(1, ECDSA_SECP256K1(dup), ED25519(dup)), ECDSA_SECP256K1(dup))",
                        keyList(
                                FAKE_ED25519_KEY_INFOS[0].publicKey(),
                                thresholdKey(
                                        1, FAKE_ECDSA_KEY_INFOS[0].publicKey(), FAKE_ED25519_KEY_INFOS[0].publicKey()),
                                FAKE_ECDSA_KEY_INFOS[0].publicKey()))));
    }

    /**
     * A provider specifically for all permutations of a valid threshold key, including those with duplicate keys
     * and nesting.
     */
    static Stream<Arguments> provideThresholdKeys() {
        // FUTURE: Add RSA keys to this list
        return Stream.of(
                // ThresholdKey with duplicate keys
                // ThresholdKey with unique keys

                // Single element threshold keys of different key types
                Arguments.of(named("ThresholdKey(1, ED25519)", thresholdKey(1, FAKE_ED25519_KEY_INFOS[0].publicKey()))),
                Arguments.of(thresholdKey(1, FAKE_ECDSA_KEY_INFOS[0].publicKey())),

                // Multiple element threshold keys of mixed types
                Arguments.of(
                        thresholdKey(1, FAKE_ED25519_KEY_INFOS[1].publicKey(), FAKE_ECDSA_KEY_INFOS[1].publicKey())),
                Arguments.of(
                        thresholdKey(1, FAKE_ED25519_KEY_INFOS[1].publicKey(), FAKE_ECDSA_KEY_INFOS[1].publicKey())),

                // Nested Threshold keys
                Arguments.of(thresholdKey(
                        3,
                        keyList(FAKE_ED25519_KEY_INFOS[0].publicKey(), FAKE_ECDSA_KEY_INFOS[0].publicKey()),
                        FAKE_ED25519_KEY_INFOS[2].publicKey(),
                        FAKE_ECDSA_KEY_INFOS[2].publicKey(),
                        thresholdKey(1, FAKE_ECDSA_KEY_INFOS[1].publicKey(), FAKE_ECDSA_KEY_INFOS[1].publicKey()))));
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

    /**
     * Based on the kind of key, returns a list of {@link SignaturePair}s that will pass verification.
     * This list will have *just barely* enough signatures to pass (i.e. they must all pass verification for the
     * key to pass, if even a single key fails, the overall validation will fail).
     *
     * <p>Note that if keys are repeated, only a single signature will be returned for that key. All signatures
     * will have a prefix of {@link #PREFIX_LENGTH} bytes.
     */
    private static List<SignaturePair> sufficientSignatures(@NonNull final Key key, final boolean fullPrefix) {
        final var list =
                switch (key.key().kind()) {
                    case KEY_LIST -> sufficientSignatures(key.keyListOrThrow(), fullPrefix);
                    case THRESHOLD_KEY -> sufficientSignatures(key.thresholdKeyOrThrow(), fullPrefix);
                    case ED25519 -> List.of(ed25519Sig(key, fullPrefix));
                    case ECDSA_SECP256K1 -> List.of(ecdsaSig(key, fullPrefix));
                    default -> throw new IllegalArgumentException(
                            "Unsupported key type: " + key.key().kind());
                };
        return list.stream().distinct().collect(Collectors.toList()); // No duplicate signatures please!
    }

    /** Utility to create a signature for this ED25519 key */
    private static SignaturePair ed25519Sig(@NonNull final Key key, final boolean fullPrefix) {
        final Bytes bytes = key.key().as();
        return SignaturePair.newBuilder()
                .pubKeyPrefix(bytes.getBytes(0, fullPrefix ? 32 : PREFIX_LENGTH))
                .ed25519(bytes)
                .build();
    }

    /** Utility to create a signature for this ECDSA_SECP256K1 key */
    private static SignaturePair ecdsaSig(@NonNull final Key key, final boolean fullPrefix) {
        final Bytes bytes = key.key().as();
        return SignaturePair.newBuilder()
                .pubKeyPrefix(bytes.getBytes(0, fullPrefix ? 33 : PREFIX_LENGTH))
                .ecdsaSecp256k1(bytes)
                .build();
    }

    /** Creates a {@link SignaturePair} for each unique key in the key list */
    private static List<SignaturePair> sufficientSignatures(@NonNull final KeyList key, final boolean fullPrefix) {
        return key.keys().stream()
                .map(k -> sufficientSignatures(k, fullPrefix))
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Creates a {@link SignaturePair} for each unique key in the threshold key UP TO the threshold, but no more.
     */
    private static List<SignaturePair> sufficientSignatures(@NonNull final ThresholdKey key, final boolean fullPrefix) {
        final var sigPairs = sufficientSignatures(key.keysOrThrow(), fullPrefix);
        final var numToRemove = sigPairs.size() - key.threshold();
        if (numToRemove > 0) {
            sigPairs.subList(0, numToRemove).clear();
        }
        return sigPairs;
    }

    /** Creates a list of {@link SignaturePair}s that are insufficient to pass verification */
    private static List<SignaturePair> insufficientSignatures(@NonNull final Key key, final boolean fullPrefix) {
        final var sigPairs = new ArrayList<>(sufficientSignatures(key, fullPrefix));
        sigPairs.remove(0);
        return sigPairs;
    }

    /** Utility to create an ED25519 SignaturePair where the prefix and key start with the given prefix. */
    private static SignaturePair ed25519Sig(@NonNull final String startPrefixWith, int prefixLength) {
        final var startPrefixWithBytes = startPrefixWith.getBytes();
        final var prefix = new byte[prefixLength];
        final var key = new byte[32];
        Arrays.fill(prefix, (byte) 255);
        Arrays.fill(key, (byte) 255);
        System.arraycopy(startPrefixWithBytes, 0, prefix, 0, startPrefixWithBytes.length);
        System.arraycopy(startPrefixWithBytes, 0, key, 0, startPrefixWithBytes.length);
        return SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(prefix))
                .ed25519(Bytes.wrap(key))
                .build();
    }

    /** Utility to create an ED25519 SignaturePair based on a TestKeyInfo with the given prefix length. */
    private static SignaturePair ed25519Sig(@NonNull final TestKeyInfo keyInfo, int prefixLength) {
        final var fullKey = keyInfo.publicKey().ed25519OrThrow();
        return SignaturePair.newBuilder()
                .pubKeyPrefix(fullKey.slice(0, prefixLength))
                .ed25519(fullKey)
                .build();
    }

    /** Utility to create an ECDSA_SECP256K1 SignaturePair based on a TestKeyInfo with the given prefix length. */
    private static SignaturePair ecdsaSig(@NonNull final TestKeyInfo keyInfo, int prefixLength) {
        final var fullKey = keyInfo.publicKey().ecdsaSecp256k1OrThrow();
        return SignaturePair.newBuilder()
                .pubKeyPrefix(fullKey.slice(0, prefixLength))
                .ecdsaSecp256k1(fullKey)
                .build();
    }

    /** Utility to create an ECDSA_SECP256K1 SignaturePair where the prefix and key start with the given prefix. */
    private static SignaturePair ecdsaSig(@NonNull final String startPrefixWith, int prefixLength) {
        final var startPrefixWithBytes = startPrefixWith.getBytes();
        final var prefix = new byte[prefixLength];
        final var key = new byte[64];
        Arrays.fill(prefix, (byte) 255);
        Arrays.fill(key, (byte) 255);
        System.arraycopy(startPrefixWithBytes, 0, prefix, 0, startPrefixWithBytes.length);
        System.arraycopy(startPrefixWithBytes, 0, key, 0, startPrefixWithBytes.length);
        return SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(prefix))
                .ecdsaSecp256k1(Bytes.wrap(key))
                .build();
    }

    private Bytes join(Bytes b1, Bytes b2) {
        final var joined = new byte[(int) (b1.length() + b2.length())];
        b1.getBytes(0, joined, 0, (int) b1.length());
        b2.getBytes(0, joined, (int) b1.length(), (int) b2.length());
        return Bytes.wrap(joined);
    }
}
