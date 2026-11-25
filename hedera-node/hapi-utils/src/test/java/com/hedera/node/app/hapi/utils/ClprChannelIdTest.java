// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.esaulpaugh.headlong.abi.Single;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClprChannelIdTest {
    private static final Bytes SALT = Bytes.wrap(new byte[32]);
    private static final Bytes ANCHOR_HASH_A = keccak256DigestOf(Bytes.wrap("anchor-a".getBytes(UTF_8)));
    private static final Bytes ANCHOR_HASH_B = keccak256DigestOf(Bytes.wrap("anchor-b".getBytes(UTF_8)));
    private static final Bytes SERVICE = Bytes.fromHex("000000000000000000000000000000000000016e");
    private static final Bytes VERIFIER_ON_A = Bytes.fromHex("00000000000000000000000000000000000013a1");
    private static final Bytes VERIFIER_ON_B = Bytes.fromHex("00112233445566778899aabbccddeeff00112233");
    private static final Timestamp TIMESTAMP_A =
            Timestamp.newBuilder().seconds(1_700_000_000L).nanos(123).build();
    private static final Timestamp TIMESTAMP_B =
            Timestamp.newBuilder().seconds(1_700_000_100L).build();

    @ParameterizedTest
    @CsvSource({"0, 0", "0, 1", "1, 0", "1700000000, 123456789", "9223372036854775807, 999999999"})
    void encodesTimestampsAsAbiUint96Nanos(final long seconds, final int nanos) {
        final var timestamp =
                Timestamp.newBuilder().seconds(seconds).nanos(nanos).build();
        final var totalNanos = BigInteger.valueOf(seconds)
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(nanos));
        final TupleType<Single<BigInteger>> uint96 = TupleType.parse("(uint96)");

        final var encoded = ClprChannelId.encodeTimestamp(timestamp);

        assertThat(encoded).hasSize(32);
        assertThat(encoded).isEqualTo(uint96.encode(Single.of(totalNanos)).array());
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "0, -1", "0, 1000000000"})
    void rejectsInvalidTimestamps(final long seconds, final int nanos) {
        final var timestamp =
                Timestamp.newBuilder().seconds(seconds).nanos(nanos).build();

        assertThatThrownBy(() -> ClprChannelId.encodeTimestamp(timestamp)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashesTheFiveLegWords() {
        final var preimage = ByteBuffer.allocate(5 * 32)
                .put(keccak256DigestOf("eip155:298".getBytes(UTF_8)))
                .put(keccak256DigestOf(SERVICE.toByteArray()))
                .put(ClprChannelId.encodeTimestamp(TIMESTAMP_A))
                .put(ANCHOR_HASH_A.toByteArray())
                .put(keccak256DigestOf(VERIFIER_ON_B.toByteArray()))
                .array();

        assertThat(ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B))
                .isEqualTo(Bytes.wrap(keccak256DigestOf(preimage)));
    }

    @Test
    void derivesTheSameIdOnBothLedgers() {
        final var aToB = ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B);
        final var bToA = ClprChannelId.leg("eip155:299", SERVICE, TIMESTAMP_B, ANCHOR_HASH_B, VERIFIER_ON_A);
        final var lo = Arrays.compareUnsigned(aToB.toByteArray(), bToA.toByteArray()) < 0 ? aToB : bToA;
        final var hi = lo == aToB ? bToA : aToB;
        final var expected = keccak256DigestOf(Bytes.merge(Bytes.merge(lo, hi), SALT));

        assertThat(ClprChannelId.derive(aToB, bToA, SALT)).isEqualTo(expected);
        assertThat(ClprChannelId.derive(bToA, aToB, SALT)).isEqualTo(expected);
    }

    @Test
    void everyLegInputChangesTheId() {
        final var bToA = ClprChannelId.leg("eip155:299", SERVICE, TIMESTAMP_B, ANCHOR_HASH_B, VERIFIER_ON_A);
        final var base = ClprChannelId.derive(
                ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B), bToA, SALT);

        assertThat(ClprChannelId.derive(
                        ClprChannelId.leg("eip155:297", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B),
                        bToA,
                        SALT))
                .isNotEqualTo(base);
        assertThat(ClprChannelId.derive(
                        ClprChannelId.leg("eip155:298", VERIFIER_ON_A, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B),
                        bToA,
                        SALT))
                .isNotEqualTo(base);
        assertThat(ClprChannelId.derive(
                        ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_B, ANCHOR_HASH_A, VERIFIER_ON_B),
                        bToA,
                        SALT))
                .isNotEqualTo(base);
        assertThat(ClprChannelId.derive(
                        ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_B, VERIFIER_ON_B),
                        bToA,
                        SALT))
                .isNotEqualTo(base);
        assertThat(ClprChannelId.derive(
                        ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_A),
                        bToA,
                        SALT))
                .isNotEqualTo(base);
        assertThat(ClprChannelId.derive(
                        ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B),
                        bToA,
                        Bytes.wrap(new byte[] {
                            1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                            0
                        })))
                .isNotEqualTo(base);
    }

    @Test
    void rejectsEqualLegs() {
        final var leg = ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B);

        assertThatThrownBy(() -> ClprChannelId.derive(leg, leg, SALT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedInputs() {
        final var leg = ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B);
        final var other = ClprChannelId.leg("eip155:299", SERVICE, TIMESTAMP_B, ANCHOR_HASH_B, VERIFIER_ON_A);

        assertThatThrownBy(() -> ClprChannelId.leg("", SERVICE, TIMESTAMP_A, ANCHOR_HASH_A, VERIFIER_ON_B))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClprChannelId.leg("eip155:298", SERVICE, TIMESTAMP_A, SERVICE, VERIFIER_ON_B))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClprChannelId.derive(leg, other, Bytes.wrap(new byte[31])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClprChannelId.derive(leg, SERVICE, SALT)).isInstanceOf(IllegalArgumentException.class);
    }
}
