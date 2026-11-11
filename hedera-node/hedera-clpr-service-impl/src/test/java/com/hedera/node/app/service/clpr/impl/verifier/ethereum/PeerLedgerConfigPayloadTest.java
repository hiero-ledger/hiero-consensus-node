// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.SIGNATURE_LENGTH;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.bitsWithout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.Committee;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.PeerLedgerConfigPayload.CommitteeKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PeerLedgerConfigPayloadTest {

    private static final SyncCommitteePreset PRESET = SyncCommitteePreset.MINIMAL;
    private static final int COMMITTEE_SEED = 1;
    private static final int FIRST_NON_SIGNER = 3;
    private static final int SECOND_NON_SIGNER = 17;

    /** The flag bit of the first byte that marks a G1 point as compressed. */
    private static final byte COMPRESSION_FLAG = (byte) 0x80;

    private static final Committee COMMITTEE = Committee.of(PRESET, COMMITTEE_SEED);
    private static final CommitteeKeys KEYS =
            new CommitteeKeys(COMMITTEE.compressedKeys, COMMITTEE.compressedAggregate);

    @Nested
    @DisplayName("CommitteeKeys.nonSignerPubkeys")
    class NonSignerPubkeys {

        @Test
        @DisplayName("given two members did not sign, when reading the non-signers, then their decompressed keys come "
                + "back in committee order")
        void givenTwoNonSigners_whenReadingNonSigners_thenDecompressedKeysInCommitteeOrder() {
            final SyncAggregate syncAggregate = syncAggregate(bitsWithout(PRESET, SECOND_NON_SIGNER, FIRST_NON_SIGNER));

            assertThat(KEYS.nonSignerPubkeys(syncAggregate))
                    .containsExactly(
                            COMMITTEE.uncompressedKeys[FIRST_NON_SIGNER],
                            COMMITTEE.uncompressedKeys[SECOND_NON_SIGNER]);
        }

        @Test
        @DisplayName("given every member signed, when reading the non-signers, then there are none")
        void givenFullParticipation_whenReadingNonSigners_thenEmpty() {
            final SyncAggregate syncAggregate = syncAggregate(bitsWithout(PRESET));

            assertThat(KEYS.nonSignerPubkeys(syncAggregate)).isEmpty();
        }

        @Test
        @DisplayName("given a non-signer key that is not on the curve, when reading the non-signers, then it fails "
                + "naming the key")
        void givenNonSignerKeyOffTheCurve_whenReadingNonSigners_thenThrows() {
            final byte[][] pubkeys = COMMITTEE.compressedKeys.clone();
            pubkeys[FIRST_NON_SIGNER] = compressedPointWithXOne();
            final CommitteeKeys keys = new CommitteeKeys(pubkeys, COMMITTEE.compressedAggregate);
            final SyncAggregate syncAggregate = syncAggregate(bitsWithout(PRESET, FIRST_NON_SIGNER));

            assertThatThrownBy(() -> keys.nonSignerPubkeys(syncAggregate))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("sync_committee.pubkeys[" + FIRST_NON_SIGNER
                            + "] is not the x coordinate of a point on the curve");
        }

        @Test
        @DisplayName("given a signer key that is not on the curve, when reading the non-signers, then the key is "
                + "never decompressed")
        void givenSignerKeyOffTheCurve_whenReadingNonSigners_thenSkipsIt() {
            final byte[][] pubkeys = COMMITTEE.compressedKeys.clone();
            pubkeys[SECOND_NON_SIGNER] = compressedPointWithXOne();
            final CommitteeKeys keys = new CommitteeKeys(pubkeys, COMMITTEE.compressedAggregate);
            final SyncAggregate syncAggregate = syncAggregate(bitsWithout(PRESET, FIRST_NON_SIGNER));

            assertThat(keys.nonSignerPubkeys(syncAggregate))
                    .containsExactly(COMMITTEE.uncompressedKeys[FIRST_NON_SIGNER]);
        }

        /** A compressed point with {@code x = 1}: no point of {@code y² = x³ + 4} has it, as 5 is not a square. */
        private static byte[] compressedPointWithXOne() {
            final byte[] point = new byte[G1Compression.COMPRESSED_LENGTH];
            point[0] = COMPRESSION_FLAG;
            point[G1Compression.COMPRESSED_LENGTH - 1] = 1;
            return point;
        }

        private static SyncAggregate syncAggregate(final byte[] bits) {
            return new SyncAggregate(bits, new byte[SIGNATURE_LENGTH]);
        }
    }

    @Nested
    @DisplayName("CommitteeKeys.toSyncCommittee")
    class ToSyncCommittee {

        @Test
        @DisplayName("given a committee's keys, when converting to a SyncCommittee, then it holds the independently "
                + "merkleized keys root and the aggregate")
        void givenCommitteeKeys_whenConvertingToSyncCommittee_thenHoldsKeysRootAndAggregate() {
            final SyncCommittee syncCommittee = KEYS.toSyncCommittee();

            assertThat(syncCommittee.pubkeysRoot32()).isEqualTo(COMMITTEE.pubkeysRoot);
            assertThat(syncCommittee.aggregatePubkey48()).isEqualTo(COMMITTEE.compressedAggregate);
        }
    }
}
