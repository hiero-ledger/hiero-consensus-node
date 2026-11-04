// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.BlockHeader;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprQbftLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.QbftContractModeValidatorSource;
import com.hedera.hapi.node.state.clpr.StorageProofEntry;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.sun.jna.ptr.IntByReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Contract-mode validator selection in {@link BesuQbftVerifier}: the validator set is proven from a contract. */
class BesuQbftContractModeTest {

    private static final byte[] SERVICE_ADDR = filled(20, 0x22);
    private static final byte[] SERVICE_CODE_HASH = filled(32, 0x33);
    private static final byte[] VALIDATOR_CONTRACT = filled(20, 0x44);
    private static final byte[] VALIDATOR_CODE_HASH = filled(32, 0x55);
    private static final byte[] ARRAY_SLOT = uint256(3);
    private static final byte[] CHANNEL_ID = filled(32, 0x66);

    private static final long ANCHOR_BLOCK = 1_000L;
    private static final long CURRENT_BLOCK = 2_000L;

    private static final BesuQbftVerifier BUNDLE_SUBJECT =
            new BesuQbftVerifier(new BesuQbftVerifier.Config(SERVICE_ADDR, SERVICE_CODE_HASH));
    private static final BesuQbftVerifier CONFIG_SUBJECT =
            new BesuQbftVerifier(new BesuQbftVerifier.Config(null, null));

    // Seven deterministic validator keys, A..G.
    private static final List<byte[]> KEYS =
            IntStream.range(0, 7).mapToObj(i -> filled(32, 0x50 + i)).toList();
    private static final List<byte[]> ADDRS =
            KEYS.stream().map(EthSigsUtils::recoverAddressFromPrivateKey).toList();

    // ── Contract-mode trust anchor ──────────────────────────────────────────────

    @Nested
    class TrustAnchorEncoding {

        @Test
        void contractModeAnchorRoundTrips() {
            final var anchor = anchor(chain(set(0, 1, 2, 3)), set(0, 1, 2, 3));

            final var decoded = BesuQbftVerifier.TrustAnchor.decode(anchor.encode());

            assertThat(decoded.mode()).isEqualTo(BesuQbftVerifier.TrustAnchor.CONTRACT_MODE);
            assertThat(decoded.encode()).isEqualTo(anchor.encode());
            assertThat(decoded.validatorContract()).isNotNull();
            assertThat(decoded.validatorContract().address20()).isEqualTo(VALIDATOR_CONTRACT);
            assertThat(decoded.validatorContract().arraySlot32()).isEqualTo(ARRAY_SLOT);
            assertThat(decoded.validatorContract().codeHash32()).isEqualTo(VALIDATOR_CODE_HASH);
            assertThat(decoded.id()).isEqualTo(BesuQbftVerifier.trustAnchorId(ANCHOR_BLOCK));
        }

        @Test
        void advanceKeepsTheValidatorContractAndReplacesTheStorageRoot() {
            final var anchor = anchor(chain(set(0, 1, 2, 3)), set(0, 1, 2, 3));
            final byte[] newRoot = filled(32, 0x77);

            final var next = anchor.advance(encodedSet(4, 5, 6), CURRENT_BLOCK, newRoot);

            assertThat(next.mode()).isEqualTo(BesuQbftVerifier.TrustAnchor.CONTRACT_MODE);
            assertThat(next.anchorBlockNumber()).isEqualTo(CURRENT_BLOCK);
            assertThat(next.validatorSetEncoded()).isEqualTo(encodedSet(4, 5, 6));
            assertThat(next.validatorContract().storageRoot32()).isEqualTo(newRoot);
            assertThat(next.validatorContract().address20()).isEqualTo(VALIDATOR_CONTRACT);
            assertThat(next.validatorContract().codeHash32()).isEqualTo(VALIDATOR_CODE_HASH);
            assertThat(next.serviceAddress20()).isEqualTo(SERVICE_ADDR);
        }

        @Test
        void headerModeAnchorIgnoresTheStorageRootOnAdvance() {
            final var anchor =
                    new BesuQbftVerifier.TrustAnchor(encodedSet(0), SERVICE_ADDR, SERVICE_CODE_HASH, ANCHOR_BLOCK);

            final var next = anchor.advance(encodedSet(1), CURRENT_BLOCK, new byte[0]);

            assertThat(next.mode()).isEqualTo(BesuQbftVerifier.TrustAnchor.HEADER_MODE);
            assertThat(next.validatorContract()).isNull();
        }

        @Test
        void contractModeAnchorWithAMalformedValidatorContractIsRejected() {
            final var anchor = anchor(chain(set(0)), set(0));
            final var items = new ArrayList<>(Rlp.decodeOne(anchor.encode()).children().stream()
                    .map(Rlp.Item::rawBytes)
                    .toList());
            items.set(6, Rlp.encodeBytes(new byte[31])); // array slot

            assertThatThrownBy(() -> BesuQbftVerifier.TrustAnchor.decode(Rlp.encodeList(items)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("malformed validator contract");
        }
    }

    // ── Config ──────────────────────────────────────────────────────────────────

    @Nested
    class ConfigPayloads {

        @Test
        void configWithAProvenValidatorArrayYieldsAContractModeAnchor() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2));

            final var verified = CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain)));

            final var anchor = BesuQbftVerifier.TrustAnchor.decode(
                    verified.ledgerConfiguration().initialTrustAnchor().toByteArray());
            assertThat(anchor.mode()).isEqualTo(BesuQbftVerifier.TrustAnchor.CONTRACT_MODE);
            assertThat(anchor.validatorSetEncoded()).isEqualTo(encodedSet(0, 1, 2, 3));
            assertThat(anchor.anchorBlockNumber()).isEqualTo(CURRENT_BLOCK);
            assertThat(anchor.codeHash32()).isEqualTo(SERVICE_CODE_HASH);
            assertThat(anchor.validatorContract().address20()).isEqualTo(VALIDATOR_CONTRACT);
            assertThat(anchor.validatorContract().arraySlot32()).isEqualTo(ARRAY_SLOT);
            assertThat(anchor.validatorContract().codeHash32()).isEqualTo(VALIDATOR_CODE_HASH);
            assertThat(anchor.validatorContract().storageRoot32()).isEqualTo(chain.validatorStorageRoot());
            assertThat(verified.ledgerConfiguration().initialTrustAnchorId().toByteArray())
                    .isEqualTo(BesuQbftVerifier.trustAnchorId(CURRENT_BLOCK));
        }

        @Test
        void configHeaderBelowTheQuorumOfTheProvenSetIsRejected() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1));

            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain))))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("committed seal");
        }

        @Test
        void configHeaderSealedByAnOutsiderIsRejected() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2, 6));

            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain))))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        @Test
        void configWithAMalformedValidatorContractAddressIsRejected() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2));
            final var badSource = source(chain)
                    .copyBuilder()
                    .validatorContractAddress(Bytes.wrap(new byte[19]))
                    .build();

            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, badSource)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validator_contract_address");
        }

        @Test
        void configForAnAbsentValidatorContractIsRejected() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2));
            final var otherContract = source(chain)
                    .copyBuilder()
                    .validatorContractAddress(Bytes.wrap(filled(20, 0x45)))
                    .build();

            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, otherContract)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validator contract account is absent");
        }

        @Test
        void invalidValidatorArraysAreRejected() {
            final byte[] a = ADDRS.get(0);
            assertArrayRejected(new ValidatorArray(0, List.of()), "validator array is empty");
            assertArrayRejected(new ValidatorArray(257, List.of()), "at most 256");
            assertArrayRejected(new ValidatorArray(2, List.of(a, new byte[20])), "zero address");
            assertArrayRejected(new ValidatorArray(2, List.of(a, a)), "twice");
        }

        @Test
        void arrayWithAnUnprovenElementIsRejected() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2));
            final var withoutLastElement = source(chain)
                    .copyBuilder()
                    .validatorContractStorageProofs(
                            source(chain).validatorContractStorageProofs().subList(0, 4))
                    .build();

            assertThatThrownBy(
                            () -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, withoutLastElement)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("is not proven");
        }

        @Test
        void configAtAChangeBlockSealedByAQuorumOfTheNewArrayIsAccepted() {
            // The header is sealed by the parent's set {0..3}; its state adds 4. All four old members sealed, which
            // is also a quorum (4) of the five-member array, so the config is accepted with the proven array: the
            // set that seals the next block.
            final Chain chain = chain(set(0, 1, 2, 3, 4));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2, 3));

            final var verified = CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain)));

            assertThat(BesuQbftVerifier.TrustAnchor.decode(verified.ledgerConfiguration()
                                    .initialTrustAnchor()
                                    .toByteArray())
                            .validatorSetEncoded())
                    .isEqualTo(encodedSet(0, 1, 2, 3, 4));
        }

        @Test
        void configAtAChangeBlockNotSealedByTheNewArrayIsRejected() {
            // The header's state replaces 3 with 4, but 3 (an old member) sealed it.
            final Chain chain = chain(set(0, 1, 2, 4));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2, 3));

            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain))))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        @Test
        void configHeaderListingValidatorsIsRejected() {
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = headerListing(CURRENT_BLOCK, chain.stateRoot(), addrs(0, 1, 2, 3), keys(0, 1, 2));

            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain))))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("a contract-mode header must list none");
        }

        @Test
        void unpaddedStorageKeysAreAccepted() {
            // eth_getProof may return slot keys without leading zeros (e.g. 0x03 for the length slot).
            final Chain chain = chain(set(0, 1, 2, 3));
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0, 1, 2));
            final var padded = source(chain);
            final List<StorageProofEntry> entries = new ArrayList<>(padded.validatorContractStorageProofs());
            entries.set(
                    0,
                    entries.getFirst()
                            .copyBuilder()
                            .key(Bytes.wrap(new byte[] {3}))
                            .build());
            final var unpadded =
                    padded.copyBuilder().validatorContractStorageProofs(entries).build();

            final var verified = CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, unpadded));

            assertThat(BesuQbftVerifier.TrustAnchor.decode(verified.ledgerConfiguration()
                                    .initialTrustAnchor()
                                    .toByteArray())
                            .validatorSetEncoded())
                    .isEqualTo(encodedSet(0, 1, 2, 3));
        }

        private void assertArrayRejected(final ValidatorArray array, final String message) {
            final Chain chain = chain(array, false);
            final byte[] header = header(CURRENT_BLOCK, chain.stateRoot(), keys(0));
            assertThatThrownBy(() -> CONFIG_SUBJECT.verifyConfigPayload(configPayload(chain, header, source(chain))))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining(message);
        }
    }

    // ── Bundles ─────────────────────────────────────────────────────────────────

    @Nested
    class Bundles {

        private final Chain anchored = chain(set(0, 1, 2, 3));
        private final BesuQbftVerifier.TrustAnchor anchor = anchor(anchored, set(0, 1, 2, 3));

        @Test
        void unchangedStorageRootIsAcceptedWithoutTheArray() {
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProof(anchored, false));

            final var verified = BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID);

            assertThat(verified.newTrustAnchor()).isEmpty();
            assertThat(verified.newTrustAnchorId()).isEmpty();
            assertThat(verified.newValidatorStorageRoot32()).isEmpty();
        }

        @Test
        void unchangedStorageRootIsAcceptedWithTheArrayToo() {
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProof(anchored, true));

            assertThat(BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID).newTrustAnchor())
                    .isEmpty();
        }

        @Test
        void currentBlockThatChangesTheArrayAdvancesTheAnchor() {
            // The current block is a change block: sealed by the anchored set, its state holds the new array.
            final Chain changed = chain(set(0, 1, 2, 4));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, changed.stateRoot(), keys(0, 1, 2)), List.of(), contractProof(changed, true));

            final var verified = BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID);

            assertThat(verified.newTrustAnchor()).isEqualTo(encodedSet(0, 1, 2, 4));
            assertThat(verified.newTrustAnchorId()).isEqualTo(BesuQbftVerifier.trustAnchorId(CURRENT_BLOCK));
            assertThat(verified.newValidatorStorageRoot32()).isEqualTo(changed.validatorStorageRoot());
        }

        @Test
        void changedStorageRootWithoutTheArrayIsRejected() {
            final Chain changed = chain(set(0, 1, 2, 4));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, changed.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProof(changed, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("is not proven");
        }

        @Test
        void storageRootChangeThatKeepsTheSetAdvancesTheAnchorWithTheSameSet() {
            final Chain sameSetNewRoot = chain(new ValidatorArray(4, ADDRS.subList(0, 4)), true);
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, sameSetNewRoot.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProof(sameSetNewRoot, true));

            final var verified = BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID);

            assertThat(verified.newTrustAnchor()).isEqualTo(encodedSet(0, 1, 2, 3));
            assertThat(verified.newValidatorStorageRoot32()).isEqualTo(sameSetNewRoot.validatorStorageRoot());
        }

        @Test
        void hopThatReplacesSeveralMembersAdvancesTheSet() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final byte[] hop = hop(1_500L, afterHop, keys(0, 1, 2));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop),
                    contractProof(afterHop, false));

            final var verified = BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID);

            assertThat(verified.newTrustAnchor()).isEqualTo(encodedSet(3, 4, 5, 6));
            assertThat(verified.newTrustAnchorId()).isEqualTo(BesuQbftVerifier.trustAnchorId(CURRENT_BLOCK));
            assertThat(verified.newValidatorStorageRoot32()).isEqualTo(afterHop.validatorStorageRoot());
        }

        @Test
        void consecutiveHopsChain() {
            final Chain first = chain(set(0, 1, 2, 4));
            final Chain second = chain(set(4, 5, 6));
            final List<byte[]> hops = List.of(hop(1_200L, first, keys(0, 1, 2)), hop(1_400L, second, keys(0, 2, 4)));
            final byte[] payload =
                    bundle(header(CURRENT_BLOCK, second.stateRoot(), keys(4, 5)), hops, contractProof(second, false));

            assertThat(BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID).newTrustAnchor())
                    .isEqualTo(encodedSet(4, 5, 6));
        }

        @Test
        void currentHeaderListingValidatorsIsRejected() {
            final byte[] payload = bundle(
                    headerListing(CURRENT_BLOCK, anchored.stateRoot(), addrs(0, 1, 2, 3), keys(0, 1, 2)),
                    List.of(),
                    contractProof(anchored, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("a contract-mode header must list none");
        }

        @Test
        void hopHeaderListingValidatorsIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final byte[] hop = Rlp.encodeList(List.of(
                    headerListing(1_500L, afterHop.stateRoot(), addrs(3, 4, 5, 6), keys(0, 1, 2)),
                    contractProof(afterHop, true)));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop),
                    contractProof(afterHop, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("hops[0].changeBlockHeader lists validators");
        }

        @Test
        void hopWithAnotherValidatorCodeHashIsRejected() {
            final Chain upgraded = chain(new ValidatorArray(4, addrs(3, 4, 5, 6)), false, filled(32, 0x56));
            assertHopRejected(upgraded, contractProof(upgraded, true), "does not match the anchored");
        }

        @Test
        void hopProvingAnotherContractOrSlotIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            assertHopRejected(
                    afterHop,
                    contractProof(afterHop, true, filled(20, 0x45), ARRAY_SLOT),
                    "validator contract proof is for 0x");
            assertHopRejected(afterHop, contractProof(afterHop, true, VALIDATOR_CONTRACT, uint256(4)), "array slot");
        }

        @Test
        void hopWithoutTheFullArrayIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final List<byte[]> withoutLastElement = arrayEntries(afterHop).subList(0, 4);

            assertHopRejected(afterHop, contractProof(afterHop, false), "is not proven");
            assertHopRejected(afterHop, contractProofWithEntries(afterHop, withoutLastElement), "is not proven");
        }

        @Test
        void trustedSetOfMoreThan256IsRejected() {
            final List<byte[]> members = new ArrayList<>();
            for (int i = 1; i <= BesuQbftVerifier.MAX_CONTRACT_MODE_VALIDATORS + 1; i++) {
                final byte[] member = new byte[20];
                ByteBuffer.wrap(member, 12, 8).putLong(i);
                members.add(member);
            }
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2)),
                    BesuQbftVerifier.encodeValidatorSet(members),
                    List.of(),
                    contractProof(anchored, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("at most " + BesuQbftVerifier.MAX_CONTRACT_MODE_VALIDATORS);
        }

        @Test
        void storageListOfMoreThan257EntriesIsRejected() {
            final Chain changed = chain(set(0, 1, 2, 4));
            final byte[] entry = arrayEntries(changed).getFirst();
            final List<byte[]> entries = new ArrayList<>();
            for (int i = 0; i <= BesuQbftVerifier.MAX_CONTRACT_MODE_VALIDATORS + 1; i++) {
                entries.add(entry);
            }
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, changed.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProofWithEntries(changed, entries));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("at most " + (BesuQbftVerifier.MAX_CONTRACT_MODE_VALIDATORS + 1));
        }

        /** A bundle crossing one hop to {@code afterHop}, whose proof is {@code hopProof}, must fail with {@code message}. */
        private void assertHopRejected(final Chain afterHop, final byte[] hopProof, final String message) {
            final byte[] hop = Rlp.encodeList(List.of(header(1_500L, afterHop.stateRoot(), keys(0, 1, 2)), hopProof));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop),
                    contractProof(afterHop, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining(message);
        }

        @Test
        void hopBelowTheQuorumOfTheTrustedSetIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop(1_500L, afterHop, keys(0, 1))),
                    contractProof(afterHop, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("committed seal");
        }

        @Test
        void hopSealedByTheNewSetIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop(1_500L, afterHop, keys(4, 5, 6))),
                    contractProof(afterHop, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        @Test
        void hopThatDoesNotChangeTheSetIsRejected() {
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2)),
                    List.of(hop(1_500L, anchored, keys(0, 1, 2))),
                    contractProof(anchored, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not change the validator set");
        }

        @Test
        void hopAtOrBelowTheAnchorBlockIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop(ANCHOR_BLOCK, afterHop, keys(0, 1, 2))),
                    contractProof(afterHop, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not strictly greater");
        }

        @Test
        void currentBlockAtOrBelowTheLastHopIsRejected() {
            final Chain afterHop = chain(set(3, 4, 5, 6));
            final byte[] payload = bundle(
                    header(1_500L, afterHop.stateRoot(), keys(4, 5, 6)),
                    List.of(hop(1_500L, afterHop, keys(0, 1, 2))),
                    contractProof(afterHop, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("is not after the last hop");
        }

        @Test
        void tooManyHopsAreRejected() {
            final List<byte[]> hops = new ArrayList<>();
            for (int i = 0; i <= BesuQbftVerifier.MAX_CONTRACT_MODE_HOPS; i++) {
                hops.add(Rlp.encodeList(List.of()));
            }
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2)), hops, contractProof(anchored, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("at most " + BesuQbftVerifier.MAX_CONTRACT_MODE_HOPS);
        }

        @Test
        void currentBlockSealedByAnOutsiderIsRejected() {
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2, 6)),
                    List.of(),
                    contractProof(anchored, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        @Test
        void validatorContractWithAnotherCodeHashIsRejected() {
            final Chain upgraded = chain(new ValidatorArray(4, ADDRS.subList(0, 4)), false, filled(32, 0x56));
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, upgraded.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProof(upgraded, true));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not match the anchored");
        }

        @Test
        void proofOfAnotherContractOrSlotIsRejected() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] otherContract = contractProof(anchored, false, filled(20, 0x45), ARRAY_SLOT);
            final byte[] otherSlot = contractProof(anchored, false, VALIDATOR_CONTRACT, uint256(4));

            assertThatThrownBy(() ->
                            BUNDLE_SUBJECT.verifyBundle(bundle(header, List.of(), otherContract), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validator contract proof is for 0x");
            assertThatThrownBy(
                            () -> BUNDLE_SUBJECT.verifyBundle(bundle(header, List.of(), otherSlot), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("array slot");
        }

        @Test
        void trustedSetMustBeTheAnchoredSet() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] proof = contractProof(anchored, false);

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(
                            bundle(header, Rlp.encodeList(List.of()), List.of(), proof), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("contains no validators");
            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(
                            bundle(header, encodedSet(0, 1, 2), List.of(), proof), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not match the trust anchor");
        }

        @Test
        void trustedSetWithDuplicatesIsAccepted() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] withDuplicate = BesuQbftVerifier.encodeValidatorSet(addrs(0, 1, 1, 2, 3));

            assertThat(BUNDLE_SUBJECT
                            .verifyBundle(
                                    bundle(header, withDuplicate, List.of(), contractProof(anchored, false)),
                                    anchor,
                                    CHANNEL_ID)
                            .newTrustAnchor())
                    .isEmpty();
        }

        @Test
        void storageProofIsNotReadWhenTheStorageRootIsUnchanged() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] malformedStorageProof = Rlp.encodeBytes(Rlp.encodeList(List.of(
                    Rlp.encodeBytes(VALIDATOR_CONTRACT),
                    nodeList(anchored.stateNodes()),
                    Rlp.encodeBytes(ARRAY_SLOT),
                    Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[] {0x01}))))));

            assertThat(BUNDLE_SUBJECT
                            .verifyBundle(bundle(header, List.of(), malformedStorageProof), anchor, CHANNEL_ID)
                            .newTrustAnchor())
                    .isEmpty();
        }

        @Test
        void slotProvenTwiceIsAccepted() {
            final Chain changed = chain(set(0, 1, 2, 4));
            final byte[] storageNodes = nodeList(changed.storageNodes());
            final List<byte[]> entries = new ArrayList<>();
            for (final byte[] slot : arraySlots(changed)) {
                entries.add(Rlp.encodeList(List.of(Rlp.encodeBytes(slot), storageNodes)));
            }
            entries.add(entries.getFirst());
            final byte[] proof = Rlp.encodeBytes(Rlp.encodeList(List.of(
                    Rlp.encodeBytes(VALIDATOR_CONTRACT),
                    nodeList(changed.stateNodes()),
                    Rlp.encodeBytes(ARRAY_SLOT),
                    Rlp.encodeList(entries))));

            final var verified = BUNDLE_SUBJECT.verifyBundle(
                    bundle(header(CURRENT_BLOCK, changed.stateRoot(), keys(0, 1, 2)), List.of(), proof),
                    anchor,
                    CHANNEL_ID);

            assertThat(verified.newTrustAnchor()).isEqualTo(encodedSet(0, 1, 2, 4));
        }

        @Test
        void validatorContractItemMustBeAnAddress() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] shortAddress = contractProof(anchored, false, new byte[19], ARRAY_SLOT);
            final byte[] smallAddress = contractProof(anchored, false, new byte[] {0x01}, ARRAY_SLOT);

            assertThatThrownBy(() ->
                            BUNDLE_SUBJECT.verifyBundle(bundle(header, List.of(), shortAddress), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("1-byte or 20-byte address");
            // A 1-byte item is a well-formed (small) address; it is then rejected as another contract.
            assertThatThrownBy(() ->
                            BUNDLE_SUBJECT.verifyBundle(bundle(header, List.of(), smallAddress), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validator contract proof is for 0x0000000000000000000000000000000000000001");
        }

        @Test
        void contractProofThatIsNotAWrappedListIsRejected() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] bareList =
                    Rlp.decodeOne(contractProof(anchored, false)).asBytes();

            assertThatThrownBy(
                            () -> BUNDLE_SUBJECT.verifyBundle(bundle(header, List.of(), bareList), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("must be an RLP byte string");
        }

        @Test
        void malformedPayloadIsARejectedProof() {
            // A current header whose state root is not 32 bytes fails a low-level length check; in contract mode,
            // as in header mode, that is reported as a rejected proof, not an unchecked exception.
            final byte[] header = header(CURRENT_BLOCK, new byte[31], keys(0, 1, 2));
            final byte[] payload = Rlp.encodeList(List.of(
                    header,
                    Rlp.encodeList(
                            List.of(encodedSet(0, 1, 2, 3), Rlp.encodeList(List.of()), contractProof(anchored, false))),
                    Rlp.encodeList(List.of()),
                    channelStorageProof(),
                    Rlp.encodeBytes(new byte[0])));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("malformed input");
        }

        @Test
        void headerModePayloadIsRejectedUnderAContractModeAnchor() {
            final byte[] header = header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2));
            final byte[] headerModeSource = Rlp.encodeList(List.of(encodedSet(0, 1, 2, 3), Rlp.encodeList(List.of())));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload(header, headerModeSource), anchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("[trustedSet, hops, contractProof]");
        }

        @Test
        void contractModePayloadIsRejectedUnderAHeaderModeAnchor() {
            final var headerModeAnchor = new BesuQbftVerifier.TrustAnchor(
                    encodedSet(0, 1, 2, 3), SERVICE_ADDR, SERVICE_CODE_HASH, ANCHOR_BLOCK);
            final byte[] payload = bundle(
                    header(CURRENT_BLOCK, anchored.stateRoot(), keys(0, 1, 2)),
                    List.of(),
                    contractProof(anchored, false));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(payload, headerModeAnchor, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("[trustedSet, hops]");
        }
    }

    // ── Chain fixtures ──────────────────────────────────────────────────────────

    /** The validator array as stored: its length slot value and its elements. */
    private record ValidatorArray(long length, List<byte[]> elements) {}

    /**
     * A state with the CLPR service (empty storage) and the validator contract holding {@code array}.
     *
     * @param stateNodes every node of the state trie (a proof for both accounts)
     * @param storageNodes every node of the validator contract's storage trie
     */
    private record Chain(
            byte[] stateRoot,
            byte[][] stateNodes,
            byte[] validatorStorageRoot,
            byte[][] storageNodes,
            ValidatorArray array) {}

    private static Chain chain(final int... set) {
        return chain(new ValidatorArray(set.length, addrs(set)), false);
    }

    private static Chain chain(final ValidatorArray array, final boolean extraSlot) {
        return chain(array, extraSlot, VALIDATOR_CODE_HASH);
    }

    /** {@code extraSlot} writes an unrelated slot, changing the storage root without changing the array. */
    private static Chain chain(final ValidatorArray array, final boolean extraSlot, final byte[] validatorCodeHash) {
        final List<byte[][]> storage = new ArrayList<>();
        storage.add(new byte[][] {ARRAY_SLOT, uint256(array.length())});
        final byte[] elementsBase = MiscCryptoUtils.keccak256DigestOf(ARRAY_SLOT);
        for (int i = 0; i < array.elements().size(); i++) {
            final byte[] value = new byte[32];
            System.arraycopy(array.elements().get(i), 0, value, 12, 20);
            storage.add(new byte[][] {plus(elementsBase, i), value});
        }
        if (extraSlot) {
            storage.add(new byte[][] {uint256(9), uint256(1)});
        }
        final List<byte[][]> storageLeaves = storage.stream()
                .map(kv -> new byte[][] {
                    MiscCryptoUtils.keccak256DigestOf(kv[0]), Rlp.encodeBytes(Rlp.encodeBytes(stripZeros(kv[1])))
                })
                .toList();
        final TestTrie storageTrie = TestTrie.build(storageLeaves);

        final List<byte[][]> accounts = List.of(
                new byte[][] {
                    MiscCryptoUtils.keccak256DigestOf(SERVICE_ADDR),
                    Rlp.encodeBytes(account(TestTrie.EMPTY_ROOT, SERVICE_CODE_HASH))
                },
                new byte[][] {
                    MiscCryptoUtils.keccak256DigestOf(VALIDATOR_CONTRACT),
                    Rlp.encodeBytes(account(storageTrie.root(), validatorCodeHash))
                });
        final TestTrie stateTrie = TestTrie.build(accounts);
        return new Chain(stateTrie.root(), stateTrie.nodes(), storageTrie.root(), storageTrie.nodes(), array);
    }

    private static byte[] account(final byte[] storageRoot, final byte[] codeHash) {
        return Rlp.encodeList(List.of(
                Rlp.encodeUint(0L), Rlp.encodeUint(0L), Rlp.encodeBytes(storageRoot), Rlp.encodeBytes(codeHash)));
    }

    private static BesuQbftVerifier.TrustAnchor anchor(final Chain chain, final int... set) {
        return new BesuQbftVerifier.TrustAnchor(
                encodedSet(set),
                SERVICE_ADDR,
                SERVICE_CODE_HASH,
                ANCHOR_BLOCK,
                new BesuQbftVerifier.ValidatorContract(
                        VALIDATOR_CONTRACT, ARRAY_SLOT, VALIDATOR_CODE_HASH, chain.validatorStorageRoot()));
    }

    /** The slots of {@code chain}'s array: its length slot and every element slot. */
    private static List<byte[]> arraySlots(final Chain chain) {
        final List<byte[]> slots = new ArrayList<>();
        slots.add(ARRAY_SLOT);
        final byte[] elementsBase = MiscCryptoUtils.keccak256DigestOf(ARRAY_SLOT);
        for (int i = 0; i < chain.array().elements().size(); i++) {
            slots.add(plus(elementsBase, i));
        }
        return slots;
    }

    private static QbftContractModeValidatorSource source(final Chain chain) {
        final List<Bytes> storageNodes =
                Arrays.stream(chain.storageNodes()).map(Bytes::wrap).toList();
        return QbftContractModeValidatorSource.newBuilder()
                .validatorContractAddress(Bytes.wrap(VALIDATOR_CONTRACT))
                .arraySlot(Bytes.wrap(ARRAY_SLOT))
                .validatorContractAccountProof(
                        Arrays.stream(chain.stateNodes()).map(Bytes::wrap).toList())
                .validatorContractStorageProofs(arraySlots(chain).stream()
                        .map(slot -> StorageProofEntry.newBuilder()
                                .key(Bytes.wrap(slot))
                                .proof(storageNodes)
                                .build())
                        .toList())
                .build();
    }

    private static byte[] configPayload(
            final Chain chain, final byte[] currentHeader, final QbftContractModeValidatorSource source) {
        return ClprQbftLedgerConfigurationPayload.PROTOBUF
                .toBytes(ClprQbftLedgerConfigurationPayload.newBuilder()
                        .currentBlockHeader(BlockHeader.newBuilder()
                                .rlp(Bytes.wrap(currentHeader))
                                .build())
                        .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                                .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                                .build())
                        .clprServiceAccountProof(Arrays.stream(chain.stateNodes())
                                .map(Bytes::wrap)
                                .toList())
                        .contractModeValidatorSource(source)
                        .build())
                .toByteArray();
    }

    private static byte[] contractProof(final Chain chain, final boolean withArray) {
        return contractProof(chain, withArray, VALIDATOR_CONTRACT, ARRAY_SLOT);
    }

    /** The bundle form: {@code RLP_bytes(RLP([contract, accountProof, arraySlot, storageProof]))}. */
    private static byte[] contractProof(
            final Chain chain, final boolean withArray, final byte[] contract, final byte[] arraySlot) {
        final byte[] storageNodes = nodeList(chain.storageNodes());
        final List<byte[]> entries = withArray
                ? arraySlots(chain).stream()
                        .map(slot -> Rlp.encodeList(List.of(Rlp.encodeBytes(slot), storageNodes)))
                        .toList()
                : List.of();
        return Rlp.encodeBytes(Rlp.encodeList(List.of(
                Rlp.encodeBytes(contract),
                nodeList(chain.stateNodes()),
                Rlp.encodeBytes(arraySlot),
                Rlp.encodeList(entries))));
    }

    /** A contract proof of {@code chain} whose storage list is exactly {@code entries}. */
    private static byte[] contractProofWithEntries(final Chain chain, final List<byte[]> entries) {
        return Rlp.encodeBytes(Rlp.encodeList(List.of(
                Rlp.encodeBytes(VALIDATOR_CONTRACT),
                nodeList(chain.stateNodes()),
                Rlp.encodeBytes(ARRAY_SLOT),
                Rlp.encodeList(entries))));
    }

    /** The storage entries proving {@code chain}'s array: its length slot and every element slot. */
    private static List<byte[]> arrayEntries(final Chain chain) {
        final byte[] storageNodes = nodeList(chain.storageNodes());
        return arraySlots(chain).stream()
                .map(slot -> Rlp.encodeList(List.of(Rlp.encodeBytes(slot), storageNodes)))
                .toList();
    }

    private static byte[] hop(final long blockNumber, final Chain chain, final List<byte[]> signers) {
        return Rlp.encodeList(List.of(header(blockNumber, chain.stateRoot(), signers), contractProof(chain, true)));
    }

    private static byte[] bundle(final byte[] currentHeader, final List<byte[]> hops, final byte[] contractProof) {
        return bundle(currentHeader, encodedSet(0, 1, 2, 3), hops, contractProof);
    }

    private static byte[] bundle(
            final byte[] currentHeader, final byte[] trustedSet, final List<byte[]> hops, final byte[] contractProof) {
        return payload(currentHeader, Rlp.encodeList(List.of(trustedSet, Rlp.encodeList(hops), contractProof)));
    }

    /**
     * A bundle whose service account has empty storage: the five Channel slots of {@link #CHANNEL_ID} are proven
     * absent, and the content is empty.
     */
    private static byte[] payload(final byte[] currentHeader, final byte[] validatorSource) {
        // The service account proof must come from the state the current header commits to.
        final byte[] stateRoot = Rlp.decodeOne(currentHeader).children().get(3).asBytes();
        final byte[][] stateNodes = Objects.requireNonNull(
                TRIE_NODES_BY_ROOT.get(new BesuQbftVerifier.BytesKey(stateRoot)), "no fixture state for the header");
        return Rlp.encodeList(List.of(
                currentHeader,
                validatorSource,
                nodeList(stateNodes),
                channelStorageProof(),
                Rlp.encodeBytes(new byte[0])));
    }

    /** Every trie built by {@link TestTrie#build}, by root, so a bundle can carry the proof of its header's state. */
    private static final Map<BesuQbftVerifier.BytesKey, byte[][]> TRIE_NODES_BY_ROOT = new ConcurrentHashMap<>();

    private static byte[] channelStorageProof() {
        final byte[] base = MiscCryptoUtils.keccak256DigestOf(concat(CHANNEL_ID, uint256(15)));
        final List<byte[]> entries = new ArrayList<>();
        for (final int offset : new int[] {1, 2, 4, 5, 16}) {
            entries.add(Rlp.encodeList(List.of(Rlp.encodeBytes(plus(base, offset)), Rlp.encodeList(List.of()))));
        }
        return Rlp.encodeList(entries);
    }

    // ── Header fixtures ─────────────────────────────────────────────────────────

    /** A contract-mode header (empty validator list in extra-data) sealed by {@code signers}. */
    private static byte[] header(final long blockNumber, final byte[] stateRoot, final List<byte[]> signers) {
        return headerListing(blockNumber, stateRoot, List.of(), signers);
    }

    /** A header listing {@code listed} in its extra-data (as a header-mode header does), sealed by {@code signers}. */
    private static byte[] headerListing(
            final long blockNumber, final byte[] stateRoot, final List<byte[]> listed, final List<byte[]> signers) {
        final byte[] unsealed = rawHeader(blockNumber, stateRoot, extraData(listed, List.of()));
        final Rlp.Item headerItem = Rlp.decodeOne(unsealed);
        final byte[] commitHash = BesuQbftVerifier.buildCommitSealHash(
                headerItem, Rlp.decodeOne(headerItem.children().get(12).asBytes()));
        final List<byte[]> seals =
                signers.stream().map(k -> sign(commitHash, k)).toList();
        return rawHeader(blockNumber, stateRoot, extraData(listed, seals));
    }

    private static byte[] extraData(final List<byte[]> listed, final List<byte[]> seals) {
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(new byte[32]),
                Rlp.encodeList(listed.stream().map(Rlp::encodeBytes).toList()),
                Rlp.encodeBytes(new byte[0]),
                Rlp.encodeUint(0L),
                Rlp.encodeList(seals.stream().map(Rlp::encodeBytes).toList())));
    }

    private static byte[] rawHeader(final long blockNumber, final byte[] stateRoot, final byte[] extraData) {
        final List<byte[]> fields = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fields.add(
                    switch (i) {
                        case 3 -> Rlp.encodeBytes(stateRoot);
                        case 8 -> Rlp.encodeUint(blockNumber);
                        case 12 -> Rlp.encodeBytes(extraData);
                        default -> Rlp.encodeBytes(new byte[0]);
                    });
        }
        return Rlp.encodeList(fields);
    }

    /** Signs {@code hash32} with secp256k1; returns r‖s‖v (65 bytes, v = 0 or 1). */
    private static byte[] sign(final byte[] hash32, final byte[] privKey32) {
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, hash32, privKey32, null, null);
        final var compact = ByteBuffer.allocate(64);
        final var recId = new IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compact, recId, recoverableSig);
        final byte[] seal = new byte[65];
        System.arraycopy(compact.array(), 0, seal, 0, 64);
        seal[64] = (byte) recId.getValue();
        return seal;
    }

    // ── Small helpers ───────────────────────────────────────────────────────────

    private static int[] set(final int... indexes) {
        return indexes;
    }

    private static List<byte[]> addrs(final int... indexes) {
        return Arrays.stream(indexes).mapToObj(ADDRS::get).toList();
    }

    private static List<byte[]> keys(final int... indexes) {
        return Arrays.stream(indexes).mapToObj(KEYS::get).toList();
    }

    private static byte[] encodedSet(final int... indexes) {
        return BesuQbftVerifier.encodeValidatorSet(addrs(indexes));
    }

    private static byte[] nodeList(final byte[][] nodes) {
        return Rlp.encodeList(Arrays.stream(nodes).map(Rlp::encodeBytes).toList());
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private static byte[] uint256(final long value) {
        final byte[] out = new byte[32];
        ByteBuffer.wrap(out, 24, 8).putLong(value);
        return out;
    }

    private static byte[] plus(final byte[] base32, final int offset) {
        final byte[] out = base32.clone();
        int carry = offset;
        for (int i = 31; i >= 0 && carry != 0; i--) {
            final int sum = (out[i] & 0xff) + (carry & 0xff);
            out[i] = (byte) sum;
            carry = (carry >>> 8) + (sum >>> 8);
        }
        return out;
    }

    private static byte[] stripZeros(final byte[] value) {
        int i = 0;
        while (i < value.length && value[i] == 0) {
            i++;
        }
        return Arrays.copyOfRange(value, i, value.length);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * A minimal Merkle Patricia trie builder for test fixtures: branch, extension and leaf nodes over 32-byte
     * keys. Every node is referenced by hash (leaf payloads here are always long enough), and {@link #nodes()}
     * returns all of them, which is a valid proof for any key.
     */
    private record TestTrie(byte[] root, byte[][] nodes) {
        static final byte[] EMPTY_ROOT = MiscCryptoUtils.keccak256DigestOf(Rlp.encodeBytes(new byte[0]));

        /** @param leaves {@code [keyHash32, encodedLeafValue]} pairs */
        static TestTrie build(final List<byte[][]> leaves) {
            if (leaves.isEmpty()) {
                return new TestTrie(EMPTY_ROOT, new byte[0][]);
            }
            final List<int[]> paths = new ArrayList<>();
            final List<byte[]> values = new ArrayList<>();
            leaves.stream()
                    .sorted(Comparator.comparing(kv -> kv[0], Arrays::compareUnsigned))
                    .forEach(kv -> {
                        paths.add(nibbles(kv[0]));
                        values.add(kv[1]);
                    });
            final List<byte[]> nodes = new ArrayList<>();
            final byte[] rootNode = node(paths, values, 0, nodes);
            final TestTrie trie =
                    new TestTrie(MiscCryptoUtils.keccak256DigestOf(rootNode), nodes.toArray(byte[][]::new));
            TRIE_NODES_BY_ROOT.put(new BesuQbftVerifier.BytesKey(trie.root()), trie.nodes());
            return trie;
        }

        private static byte[] node(
                final List<int[]> paths, final List<byte[]> values, final int depth, final List<byte[]> out) {
            final byte[] node;
            if (paths.size() == 1) {
                // values are pre-encoded RLP byte strings; the leaf stores their content
                node = Rlp.encodeList(List.of(
                        Rlp.encodeBytes(hexPrefix(Arrays.copyOfRange(paths.getFirst(), depth, 64), true)),
                        values.getFirst()));
            } else {
                int common = 0;
                while (depth + common < 64 && allShare(paths, depth + common)) {
                    common++;
                }
                if (common > 0) {
                    final byte[] child = node(paths, values, depth + common, out);
                    node = Rlp.encodeList(List.of(
                            Rlp.encodeBytes(
                                    hexPrefix(Arrays.copyOfRange(paths.getFirst(), depth, depth + common), false)),
                            Rlp.encodeBytes(MiscCryptoUtils.keccak256DigestOf(child))));
                } else {
                    final List<byte[]> items = new ArrayList<>();
                    for (int nibble = 0; nibble < 16; nibble++) {
                        final List<int[]> groupPaths = new ArrayList<>();
                        final List<byte[]> groupValues = new ArrayList<>();
                        for (int i = 0; i < paths.size(); i++) {
                            if (paths.get(i)[depth] == nibble) {
                                groupPaths.add(paths.get(i));
                                groupValues.add(values.get(i));
                            }
                        }
                        items.add(
                                groupPaths.isEmpty()
                                        ? Rlp.encodeBytes(new byte[0])
                                        : Rlp.encodeBytes(MiscCryptoUtils.keccak256DigestOf(
                                                node(groupPaths, groupValues, depth + 1, out))));
                    }
                    items.add(Rlp.encodeBytes(new byte[0]));
                    node = Rlp.encodeList(items);
                }
            }
            out.add(node);
            return node;
        }

        private static boolean allShare(final List<int[]> paths, final int position) {
            final int first = paths.getFirst()[position];
            return paths.stream().allMatch(p -> p[position] == first);
        }

        private static int[] nibbles(final byte[] key) {
            final int[] out = new int[key.length * 2];
            for (int i = 0; i < key.length; i++) {
                out[2 * i] = (key[i] >> 4) & 0xF;
                out[2 * i + 1] = key[i] & 0xF;
            }
            return out;
        }

        private static byte[] hexPrefix(final int[] nibbles, final boolean leaf) {
            final boolean odd = nibbles.length % 2 == 1;
            final int flag = (leaf ? 2 : 0) + (odd ? 1 : 0);
            final byte[] out = new byte[nibbles.length / 2 + 1];
            int pos = 0;
            if (odd) {
                out[0] = (byte) ((flag << 4) | nibbles[0]);
                pos = 1;
            } else {
                out[0] = (byte) (flag << 4);
            }
            for (int i = 1; i < out.length; i++) {
                out[i] = (byte) ((nibbles[pos] << 4) | nibbles[pos + 1]);
                pos += 2;
            }
            return out;
        }
    }
}
