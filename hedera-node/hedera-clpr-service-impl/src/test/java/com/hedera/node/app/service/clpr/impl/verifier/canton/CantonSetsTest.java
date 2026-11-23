// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.MEDIATOR1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonSets}, the packed owner/mediator set decode/encode and key-fingerprint primitives.
 *
 * <p>All vectors are REAL Canton bytes from the shipped fixtures ({@code topology-owner-set.hex},
 * {@code topology-mediator-set.hex}, {@code lsu-owner-set.hex}) and the Solidity test oracle
 * {@code CantonTopologyFixtures.sol} (ref {@code pr-556}). The owner namespace, mediator member id, the Ed25519
 * key and its fingerprint are those Canton itself produced; the fingerprint is cross-checked by recomputing it
 * from the raw key via {@link CantonSets#keyFromDer}.
 */
class CantonSetsTest {

    @Nested
    class OwnerSet {
        @Test
        void decodeOwnersReadsTheRealOwnerSet() {
            final byte[] bytes = CantonFixtures.topologyOwnerSet();
            final CantonSets.Owners o = CantonSets.decodeOwners(bytes);

            assertThat(o.serial()).isEqualTo(1L);
            assertThat(o.threshold()).isEqualTo(1L);
            assertThat(o.owners()).hasSize(1);
            final CantonSets.Owner owner = o.owners().getFirst();
            assertThat(owner.namespace()).isEqualTo(CantonFixtures.OWNER_SEQUENCER);
            // A root key's fingerprint must be its owner's namespace (enforced by decode).
            assertThat(owner.root().fingerprint()).isEqualTo(CantonFixtures.OWNER_SEQUENCER);
            assertThat(owner.root().keySpec()).isEqualTo(CantonSets.SPEC_ED25519);
            assertThat(owner.delegations()).isEmpty();
        }

        @Test
        void encodeOwnersRoundTripsTheRealOwnerSet() {
            final byte[] bytes = CantonFixtures.topologyOwnerSet();
            assertThat(CantonSets.decodeOwners(bytes).encode()).isEqualTo(bytes);
        }

        @Test
        void decodeOwnersReadsTheLsuOwnerSet() {
            final CantonSets.Owners o = CantonSets.decodeOwners(CantonFixtures.lsuOwnerSet());
            assertThat(o.serial()).isEqualTo(1L);
            assertThat(o.threshold()).isEqualTo(1L);
            assertThat(o.owners()).hasSize(1);
            assertThat(o.encode()).isEqualTo(CantonFixtures.lsuOwnerSet());
        }
    }

    @Nested
    class MediatorSet {
        @Test
        void decodeMediatorsReadsTheRealMediatorSet() {
            final CantonSets.Mediators m = CantonSets.decodeMediators(CantonFixtures.topologyMediatorSet());

            assertThat(m.serial()).isEqualTo(1L);
            assertThat(m.threshold()).isEqualTo(1L);
            assertThat(m.members()).hasSize(1);
            final CantonSets.Member member = m.members().getFirst();
            assertThat(member.memberId()).isEqualTo(MEDIATOR1);
            assertThat(member.otkSerial()).isEqualTo(1L);
            assertThat(member.delegations()).isEmpty();
            assertThat(member.current()).hasSize(1);
            assertThat(member.previous()).isEmpty();
            assertThat(member.current().getFirst().fingerprint()).isEqualTo(CantonFixtures.MEDIATOR1_KEY_1);
            assertThat(member.current().getFirst().keySpec()).isEqualTo(CantonSets.SPEC_ED25519);
        }

        @Test
        void encodeMediatorsRoundTripsTheRealMediatorSet() {
            final byte[] bytes = CantonFixtures.topologyMediatorSet();
            assertThat(CantonSets.decodeMediators(bytes).encode()).isEqualTo(bytes);
        }
    }

    @Nested
    class KeysAndFingerprints {
        @Test
        void decodeKeyAndEncodeKeyRoundTrip() {
            final CantonSets.Key key = CantonSets.decodeMediators(CantonFixtures.topologyMediatorSet())
                    .members()
                    .getFirst()
                    .current()
                    .getFirst();
            final byte[] encoded = key.encode();
            assertThat(encoded).hasSize(98);
            // Key is a record over byte[] (identity equality), so compare the encoding and the fields.
            final CantonSets.Key decoded = CantonSets.decodeKey(encoded);
            assertThat(decoded.encode()).isEqualTo(encoded);
            assertThat(decoded.fingerprint()).isEqualTo(key.fingerprint());
            assertThat(decoded.keySpec()).isEqualTo(key.keySpec());
            assertThat(decoded.fingerprintMode()).isEqualTo(key.fingerprintMode());
            assertThat(decoded.x()).isEqualTo(key.x());
            assertThat(decoded.y()).isEqualTo(key.y());
        }

        @Test
        void keyFromDerRecomputesTheRealEd25519Fingerprint() {
            final CantonSets.Key key = CantonSets.decodeMediators(CantonFixtures.topologyMediatorSet())
                    .members()
                    .getFirst()
                    .current()
                    .getFirst();
            // Ed25519: the DER header plus the raw 32-byte key; fingerprint = SHA256(BE32(12) ‖ rawKey).
            final byte[] der = CantonBytes.concat(CantonSets.DER_ED25519, key.x());
            final CantonSets.KeyFromDer parsed = CantonSets.keyFromDer(CantonSets.SPEC_ED25519, der);

            assertThat(parsed.ok()).isTrue();
            assertThat(parsed.key().fingerprint()).isEqualTo(CantonFixtures.MEDIATOR1_KEY_1);
            assertThat(parsed.key().fingerprintMode()).isZero();
            assertThat(parsed.key().keySpec()).isEqualTo(CantonSets.SPEC_ED25519);
            assertThat(parsed.key().x()).isEqualTo(key.x());
        }

        @Test
        void keyFromDerRejectsAWrongLengthOrHeader() {
            assertThat(CantonSets.keyFromDer(CantonSets.SPEC_ED25519, new byte[10])
                            .ok())
                    .isFalse();
            // Right length, wrong header byte.
            final byte[] der = CantonBytes.concat(CantonSets.DER_ED25519, new byte[32]);
            der[0] = 0x00;
            assertThat(CantonSets.keyFromDer(CantonSets.SPEC_ED25519, der).ok()).isFalse();
            // Unknown spec.
            assertThat(CantonSets.keyFromDer(99, der).ok()).isFalse();
        }

        @Test
        void hasOwnFingerprintAcceptsTheRealKeyAndRejectsTampering() {
            final CantonSets.Key key = CantonSets.decodeMediators(CantonFixtures.topologyMediatorSet())
                    .members()
                    .getFirst()
                    .current()
                    .getFirst();
            assertThat(key.hasOwnFingerprint()).isTrue();

            final byte[] badFingerprint = key.fingerprint().clone();
            badFingerprint[0] ^= 0x01;
            final CantonSets.Key forged =
                    new CantonSets.Key(badFingerprint, key.keySpec(), key.fingerprintMode(), key.x(), key.y());
            assertThat(forged.hasOwnFingerprint()).isFalse();
        }
    }

    @Nested()
    class InvalidSetParity {

        @Test
        void decodeOwnersRejectsAShortBuffer() {
            assertThatThrownBy(() -> CantonSets.decodeOwners(new byte[4]))
                    .isInstanceOfSatisfying(
                            CantonProofException.class,
                            e -> assertThat(e.errorName()).isEqualTo("InvalidSet"));
        }

        @Test
        void decodeOwnersRejectsARootFingerprintThatIsNotItsNamespace() {
            final byte[] bytes = CantonFixtures.topologyOwnerSet().clone();
            // Flip a byte of the root key's fingerprint (it sits right after the 32-byte namespace at offset 12).
            bytes[12 + 32] ^= 0x01;
            assertThatThrownBy(() -> CantonSets.decodeOwners(bytes))
                    .isInstanceOfSatisfying(
                            CantonProofException.class,
                            e -> assertThat(e.errorName()).isEqualTo("InvalidSet"));
        }

        @Test
        void decodeOwnersRejectsTrailingBytes() {
            final byte[] bytes =
                    Arrays.copyOf(CantonFixtures.topologyOwnerSet(), CantonFixtures.topologyOwnerSet().length + 1);
            assertThatThrownBy(() -> CantonSets.decodeOwners(bytes))
                    .isInstanceOfSatisfying(
                            CantonProofException.class,
                            e -> assertThat(e.errorName()).isEqualTo("InvalidSet"));
        }

        @Test
        void decodeMediatorsRejectsTrailingBytes() {
            final byte[] src = CantonFixtures.topologyMediatorSet();
            final byte[] bytes = Arrays.copyOf(src, src.length + 1);
            assertThatThrownBy(() -> CantonSets.decodeMediators(bytes))
                    .isInstanceOfSatisfying(
                            CantonProofException.class,
                            e -> assertThat(e.errorName()).isEqualTo("InvalidSet"));
        }
    }
}
