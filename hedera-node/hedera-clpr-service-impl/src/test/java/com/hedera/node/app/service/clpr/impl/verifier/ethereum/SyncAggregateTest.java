// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the participation bits to the SSZ {@code Bitvector} layout: bit {@code i} is bit {@code i % 8} of byte
 * {@code i / 8}, least significant bit first. The expected values come from the spec.
 */
class SyncAggregateTest {

    private static final int MAINNET_BITS_LENGTH = 64;
    private static final int MAINNET_COMMITTEE_SIZE = 512;

    private static final byte[] SIGNATURE = new byte[256];

    @Nested
    class HasSigned {

        @Test
        @DisplayName("given bits 0x01 0x80, then only members 0 and 15 have signed")
        void givenLowestBitOfFirstByteAndHighestBitOfSecond_thenMembersZeroAndFifteenSigned() {
            final SyncAggregate aggregate = aggregate((byte) 0x01, (byte) 0x80);

            assertThat(aggregate.hasSigned(0)).isTrue();
            assertThat(aggregate.hasSigned(1)).isFalse();
            assertThat(aggregate.hasSigned(7)).isFalse();
            assertThat(aggregate.hasSigned(8)).isFalse();
            assertThat(aggregate.hasSigned(15)).isTrue();
        }

        @Test
        @DisplayName("given a mainnet bitvector whose last byte is 0x80, then only the last member has signed")
        void givenHighestBitOfLastByte_thenOnlyLastMemberSigned() {
            final byte[] bits = new byte[MAINNET_BITS_LENGTH];
            bits[MAINNET_BITS_LENGTH - 1] = (byte) 0x80;
            final SyncAggregate aggregate = new SyncAggregate(bits, SIGNATURE);

            assertThat(aggregate.hasSigned(MAINNET_COMMITTEE_SIZE - 1)).isTrue();
            assertThat(aggregate.hasSigned(MAINNET_COMMITTEE_SIZE - 2)).isFalse();
        }
    }

    @Nested
    class ParticipantCount {

        @Test
        @DisplayName("given every mainnet bit set, then participantCount is 512")
        void givenFullParticipation_thenCountsWholeCommittee() {
            final byte[] bits = new byte[MAINNET_BITS_LENGTH];
            Arrays.fill(bits, (byte) 0xFF);

            assertThat(new SyncAggregate(bits, SIGNATURE).participantCount()).isEqualTo(MAINNET_COMMITTEE_SIZE);
        }

        @Test
        @DisplayName("given bits 0x01 0x80 0x00, then participantCount is 2")
        void givenOneBitInTwoBytes_thenCountsTwo() {
            assertThat(aggregate((byte) 0x01, (byte) 0x80, (byte) 0x00).participantCount())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("given no bit set, then participantCount is 0")
        void givenNoParticipation_thenCountsZero() {
            assertThat(new SyncAggregate(new byte[MAINNET_BITS_LENGTH], SIGNATURE).participantCount())
                    .isZero();
        }
    }

    private static SyncAggregate aggregate(final byte... bits) {
        return new SyncAggregate(bits, SIGNATURE);
    }
}
