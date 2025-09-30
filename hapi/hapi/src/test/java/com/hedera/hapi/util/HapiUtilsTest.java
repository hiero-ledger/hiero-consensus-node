// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.util;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.of;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

final class HapiUtilsTest {

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
            2007-12-03T10:15:30.00Z, 2007-12-03T10:15:30.01Z
            2007-12-31T23:59:59.99Z, 2008-01-01T00:00:00.00Z
            """)
    @DisplayName("When timestamp t1 comes before timestamp t2")
    void isBefore(@NonNull final Instant i1, @NonNull final Instant i2) {
        final var t1 = asTimestamp(i1);
        final var t2 = asTimestamp(i2);
        assertThat(HapiUtils.isBefore(t1, t2)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
            2007-12-03T10:15:30.01Z, 2007-12-03T10:15:30.00Z
            2008-01-01T00:00:00.00Z, 2007-12-31T23:59:59.99Z
            """)
    @DisplayName("When timestamp t1 comes after timestamp t2")
    void isAfter(@NonNull final Instant i1, @NonNull final Instant i2) {
        final var t1 = asTimestamp(i1);
        final var t2 = asTimestamp(i2);
        assertThat(HapiUtils.isBefore(t1, t2)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
            2007-12-03T10:15:30.00Z, 2007-12-03T10:15:30.00Z
            2007-12-31T23:59:59.99Z, 2007-12-31T23:59:59.99Z
            2008-01-01T00:00:00.00Z, 2008-01-01T00:00:00.00Z
            """)
    @DisplayName("When timestamp t1 is the same as timestamp t2")
    void isEqual(@NonNull final Instant i1, @NonNull final Instant i2) {
        final var t1 = asTimestamp(i1);
        final var t2 = asTimestamp(i2);
        assertThat(HapiUtils.isBefore(t1, t2)).isFalse();
    }

    @Test
    @DisplayName("Converting an Instant into a Timestamp")
    void convertInstantToTimestamp() {
        // Given an instant with nanosecond precision
        final var instant = Instant.ofEpochSecond(1000, 123456789);
        // When we convert it into a timestamp
        final var timestamp = asTimestamp(instant);
        // Then we find the timestamp matches the original instant
        assertThat(timestamp)
                .isEqualTo(Timestamp.newBuilder().seconds(1000).nanos(123456789).build());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cryptoKeys")
    @DisplayName("Count of primitive cryptographic keys")
    void countOfPrimitiveCryptoKeys(@NonNull final String name, @NonNull final Key key, final int expectedCount) {
        // Given a key (which may be a cryptographic key, key list, threshold key, or other)
        // When we count up the number of primitive keys
        final var count = HapiUtils.countOfCryptographicKeys(key);
        // Then we find the answer is expected
        assertThat(count).isEqualTo(expectedCount);
    }

    public static Stream<Arguments> cryptoKeys() {
        // Even though all these bytes are empty, it is OK for the test, since we're not inspecting the keys
        // for validity, we only need to verify the key exists.
        final var keyBytes = Bytes.EMPTY;
        final var contractId = ContractID.DEFAULT;
        return Stream.of(
                of("ECDSA_384", Key.newBuilder().ecdsa384(keyBytes).build(), 1),
                of("ECDSA_SECP256_K1", Key.newBuilder().ecdsaSecp256k1(keyBytes).build(), 1),
                of("ED25519", Key.newBuilder().ed25519(keyBytes).build(), 1),
                of("RSA_3072", Key.newBuilder().rsa3072(keyBytes).build(), 1),
                of("CONTRACT_ID", Key.newBuilder().contractID(contractId).build(), 0),
                of(
                        "DELEGATABLE_CONTRACT_ID",
                        Key.newBuilder().delegatableContractId(contractId).build(),
                        0),
                of("UNSET", Key.newBuilder().build(), 0),
                of(
                        "KEY_LIST",
                        Key.newBuilder()
                                .keyList(KeyList.newBuilder()
                                        .keys( // Some more-or-less random collection of keys
                                                Key.newBuilder()
                                                        .ed25519(keyBytes)
                                                        .build(),
                                                Key.newBuilder()
                                                        .ed25519(keyBytes)
                                                        .build(),
                                                Key.newBuilder()
                                                        .ecdsaSecp256k1(keyBytes)
                                                        .build(),
                                                Key.newBuilder()
                                                        .contractID(contractId)
                                                        .build(),
                                                Key.newBuilder()
                                                        .rsa3072(keyBytes)
                                                        .build())
                                        .build())
                                .build(),
                        4),
                of(
                        "THRESHOLD_KEY",
                        Key.newBuilder()
                                .thresholdKey(ThresholdKey.newBuilder()
                                        .threshold(1)
                                        .keys(KeyList.newBuilder()
                                                .keys( // Some more-or-less random collection of keys
                                                        Key.newBuilder()
                                                                .ed25519(keyBytes)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .ed25519(keyBytes)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .ecdsaSecp256k1(keyBytes)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .contractID(contractId)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .rsa3072(keyBytes)
                                                                .build())
                                                .build()))
                                .build(),
                        4),
                of(
                        "COMPLEX_KEY",
                        Key.newBuilder()
                                .thresholdKey(ThresholdKey.newBuilder()
                                        .threshold(1)
                                        .keys(KeyList.newBuilder()
                                                .keys( // Some more-or-less random collection of keys
                                                        Key.newBuilder()
                                                                .ed25519(keyBytes)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .ed25519(keyBytes)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .keyList(KeyList.newBuilder()
                                                                        .keys( // Some more-or-less random collection of
                                                                                // keys
                                                                                Key.newBuilder()
                                                                                        .ed25519(keyBytes)
                                                                                        .build(),
                                                                                Key.newBuilder()
                                                                                        .ed25519(keyBytes)
                                                                                        .build(),
                                                                                Key.newBuilder()
                                                                                        .ecdsaSecp256k1(keyBytes)
                                                                                        .build(),
                                                                                Key.newBuilder()
                                                                                        .contractID(contractId)
                                                                                        .build(),
                                                                                Key.newBuilder()
                                                                                        .rsa3072(keyBytes)
                                                                                        .build())
                                                                        .build())
                                                                .build(),
                                                        Key.newBuilder()
                                                                .ecdsaSecp256k1(keyBytes)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .contractID(contractId)
                                                                .build(),
                                                        Key.newBuilder()
                                                                .rsa3072(keyBytes)
                                                                .build())
                                                .build()))
                                .build(),
                        8));
    }
}
