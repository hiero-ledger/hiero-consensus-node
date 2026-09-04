// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.PbjTestUtils.appendUnknownField;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.foldBranch;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.merkleizeIndependently;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sha256;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.uint64LeafLittleEndian;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.verifier.BlsSignatureVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EthereumSyncCommitteeProofVerifierTest {

    private static final HexFormat HEX = HexFormat.of();

    // keccak256(RLP(empty)) — the canonical empty MPT root
    private static final byte[] EMPTY_TRIE_ROOT =
            HEX.parseHex("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421");

    // Stable 20-byte CLPR service contract address
    private static final byte[] SERVICE_ADDR = {
        (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE,
        (byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44, (byte) 0x55,
        (byte) 0x66, (byte) 0x77, (byte) 0x88, (byte) 0x99, (byte) 0x00,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05
    };

    private static final int COMMITTEE_SIZE = 512;
    private static final byte[] GENESIS_VALIDATORS_ROOT = deterministicBytes(32, 0x4B);
    private static final byte[] FORK_VERSION = {0x05, 0x00, 0x00, 0x00};

    /** Beacon slot the config's initial committee is current for (period 1024 * 8192). */
    private static final long CONFIG_SLOT = 8_388_608L;

    /**
     * Deterministic 512-pubkey committee: pubkey[i][j] = i*7 + j (mod 256).
     */
    private static final byte[][] PUBKEYS = buildPubkeys();

    private static final byte[] AGGREGATE_PUBKEY = deterministicBytes(48, 0x77);
    private static final SyncCommittee COMMITTEE = new SyncCommittee(PUBKEYS, AGGREGATE_PUBKEY);
    private static final byte[] TRUST_ANCHOR =
            TrustAnchor.encode(COMMITTEE, GENESIS_VALIDATORS_ROOT, FORK_VERSION, SERVICE_ADDR);

    /**
     * Records its inputs and returns a configurable result.
     */
    private static final class FakeBls implements BlsSignatureVerifier {

        boolean result = true;
        List<byte[]> lastPublicKeys;
        byte[] lastMessage;
        byte[] lastSignature;

        @Override
        public boolean fastAggregateVerify(
                final List<byte[]> publicKeys, final byte[] message, final byte[] signature) {
            lastPublicKeys = publicKeys;
            lastMessage = message;
            lastSignature = signature;
            return result;
        }
    }

    private static EthereumSyncCommitteeProofVerifier verifier(final FakeBls bls) {
        return new EthereumSyncCommitteeProofVerifier(bls);
    }

    /**
     * RLP-encodes a committee as {@code [pubkeys[], aggregatePubkey]} for hand-built anchors/payloads.
     */
    private static byte[] committeeRlp(byte[][] pubkeys, byte[] aggregatePubkey) {
        List<byte[]> encodedKeys = new ArrayList<>(pubkeys.length);
        for (byte[] key : pubkeys) {
            encodedKeys.add(Rlp.encodeBytes(key));
        }
        return Rlp.encodeList(List.of(Rlp.encodeList(encodedKeys), Rlp.encodeBytes(aggregatePubkey)));
    }

    /**
     * Shorthand for {@code new BeaconHeader(...).hashTreeRoot()}.
     */
    private static byte[] beaconHeaderRoot(
            long slot, long proposerIndex, byte[] parentRoot, byte[] stateRoot, byte[] bodyRoot) {
        return new BeaconHeader(slot, proposerIndex, parentRoot, stateRoot, bodyRoot).hashTreeRoot();
    }

    @Nested
    @DisplayName("verifyBundle(...)")
    class VerifyBundle {

        @Nested
        class PayloadDecoding {

            @Test
            void nullPayload_throwsNpe() {
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(null, TRUST_ANCHOR))
                        .isInstanceOf(NullPointerException.class);
            }

            @Test
            void nullTrustAnchor_throwsNpe() {
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(new byte[0], null))
                        .isInstanceOf(NullPointerException.class);
            }

            @Test
            void garbagePayload_throwsProofException() {
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(new byte[] {0x01, 0x02}, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("not a valid RLP item");
            }

            @Test
            void wrongTopLevelItemCount_throwsProofException() {
                byte[] payload = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[0])));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("expected top-level RLP list of 9 or 11 items");
            }

            @Test
            void beaconHeaderWrongFieldCount_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.attestedHeaderOverride = Rlp.encodeList(List.of(Rlp.encodeUint(1L)));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("attestedHeader is not an RLP list of 5 fields");
            }

            @Test
            void bitsWrongLength_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.bits = new byte[63];
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("syncAggregate.bits must be 64 bytes");
            }

            @Test
            void signatureWrongLength_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.signature = new byte[95];
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("syncAggregate.signature must be 96 bytes");
            }
        }

        @Nested
        class TrustAnchorDecoding {

            @Test
            void garbageAnchor_throwsProofException() {
                assertThatThrownBy(() -> verifier(new FakeBls())
                                .verifyBundle(validPayloadBuilder().build(), new byte[] {0x01, 0x02}))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("trustAnchor is not a valid RLP item");
            }

            @Test
            void anchorWrongItemCount_throwsProofException() {
                byte[] anchor = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[32])));
                assertThatThrownBy(() -> verifier(new FakeBls())
                                .verifyBundle(validPayloadBuilder().build(), anchor))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("trustAnchor is not an RLP list of 4 items");
            }

            @Test
            void anchorCommitteeWrongPubkeyCount_throwsProofException() {
                // First anchor item must be a committee list of 512 pubkeys; a single-key list is rejected.
                byte[] badCommittee = Rlp.encodeList(
                        List.of(Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[48]))), Rlp.encodeBytes(new byte[48])));
                byte[] anchor = Rlp.encodeList(List.of(
                        badCommittee,
                        Rlp.encodeBytes(GENESIS_VALIDATORS_ROOT),
                        Rlp.encodeBytes(FORK_VERSION),
                        Rlp.encodeBytes(SERVICE_ADDR)));
                assertThatThrownBy(() -> verifier(new FakeBls())
                                .verifyBundle(validPayloadBuilder().build(), anchor))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("syncCommittee.pubkeys must be an RLP list of 512 items");
            }

            @Test
            void anchorForkVersionWrongLength_throwsProofException() {
                byte[] anchor = Rlp.encodeList(List.of(
                        committeeRlp(PUBKEYS, AGGREGATE_PUBKEY),
                        Rlp.encodeBytes(GENESIS_VALIDATORS_ROOT),
                        Rlp.encodeBytes(new byte[5]),
                        Rlp.encodeBytes(SERVICE_ADDR)));
                assertThatThrownBy(() -> verifier(new FakeBls())
                                .verifyBundle(validPayloadBuilder().build(), anchor))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("trustAnchor.forkVersion must be 4 bytes");
            }

            @Test
            void anchorServiceAddressWrongLength_throwsProofException() {
                byte[] anchor = Rlp.encodeList(List.of(
                        committeeRlp(PUBKEYS, AGGREGATE_PUBKEY),
                        Rlp.encodeBytes(GENESIS_VALIDATORS_ROOT),
                        Rlp.encodeBytes(FORK_VERSION),
                        Rlp.encodeBytes(new byte[19])));
                assertThatThrownBy(() -> verifier(new FakeBls())
                                .verifyBundle(validPayloadBuilder().build(), anchor))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("trustAnchor.serviceAddress must be 20 bytes");
            }
        }

        @Nested
        class Participation {

            @Test
            void participationOneBelowSupermajority_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.bits = firstNBitsSet(341);
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("participation 341/512 is below the 2/3 supermajority");
            }

            @Test
            void participationAtSupermajority_passes() {
                var builder = validPayloadBuilder();
                builder.bits = firstNBitsSet(342);
                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);
                assertThat(result).isNotNull();
            }
        }

        @Nested
        class BlsSignature {

            @Test
            void blsRejecting_throwsProofException() {
                FakeBls bls = new FakeBls();
                bls.result = false;
                assertThatThrownBy(() ->
                                verifier(bls).verifyBundle(validPayloadBuilder().build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("BLS signature verification failed");
            }

            @Test
            void blsReceivesParticipantPubkeysInCommitteeOrder() {
                FakeBls bls = new FakeBls();
                var builder = validPayloadBuilder();
                builder.bits = firstNBitsSet(342);
                verifier(bls).verifyBundle(builder.build(), TRUST_ANCHOR);

                assertThat(bls.lastPublicKeys).hasSize(342);
                for (int i = 0; i < 342; i++) {
                    assertThat(bls.lastPublicKeys.get(i)).isEqualTo(PUBKEYS[i]);
                }
            }

            @Test
            void blsReceivesIndependentlyComputedSigningRootAndSignature() {
                FakeBls bls = new FakeBls();
                var builder = validPayloadBuilder();
                builder.signature = deterministicBytes(96, 0x61);
                verifier(bls).verifyBundle(builder.build(), TRUST_ANCHOR);

                // Independently re-derive the signing root with test-local hashing only.
                byte[][] headerLeaves = {
                    uint64LeafLittleEndian(builder.slot),
                    uint64LeafLittleEndian(builder.proposerIndex),
                    builder.parentRoot,
                    builder.stateRoot,
                    builder.bodyRoot(),
                    new byte[32],
                    new byte[32],
                    new byte[32]
                };
                byte[] headerRoot = merkleizeIndependently(headerLeaves);
                byte[] paddedVersion = new byte[32];
                System.arraycopy(FORK_VERSION, 0, paddedVersion, 0, 4);
                byte[] forkDataRoot = sha256(paddedVersion, GENESIS_VALIDATORS_ROOT);
                byte[] domain = new byte[32];
                domain[0] = 0x07;
                System.arraycopy(forkDataRoot, 0, domain, 4, 28);
                byte[] expectedSigningRoot = sha256(headerRoot, domain);

                assertThat(bls.lastMessage).isEqualTo(expectedSigningRoot);
                assertThat(bls.lastSignature).isEqualTo(builder.signature);
            }
        }

        @Nested
        class ExecutionStateRootBranch {

            @Test
            void tamperedBodyRoot_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.bodyRootOverride = deterministicBytes(32, 0x2D);
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("execution state root branch does not verify");
            }

            @Test
            void executionBranchWrongDepth_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.executionBranch = zeroBranch(8);
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("executionBranch must have 9 nodes");
            }
        }

        @Nested
        class NextSyncCommitteeRotation {

            @Test
            void absentRotationProof_returnsNullNextAnchor() {
                var result = verifier(new FakeBls())
                        .verifyBundle(validPayloadBuilder().build(), TRUST_ANCHOR);
                assertThat(result.nextTrustAnchor()).isNull();
                assertThat(result.nextTrustAnchorId()).isNull();
            }

            @Test
            void validRotationProof_returnsSuccessorAnchor() {
                // The rotation bundle carries the full next committee; its root is computed and
                // proven against the attested stateRoot, and the successor anchor embeds it.
                var nextCommittee = new SyncCommittee(PUBKEYS, deterministicBytes(48, 0x99));
                byte[] nextRoot = nextCommittee.hashTreeRoot();
                byte[][] branch = zeroBranch(6);
                var builder = validPayloadBuilder();
                builder.nextCommittee = nextCommittee;
                builder.nextCommitteeBranch = branch;
                builder.stateRoot = foldBranch(nextRoot, branch, 23); // BeaconState.next_sync_committee leaf index

                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);

                byte[] expectedAnchor =
                        TrustAnchor.encode(nextCommittee, GENESIS_VALIDATORS_ROOT, FORK_VERSION, SERVICE_ADDR);
                assertThat(result.nextTrustAnchor()).isEqualTo(expectedAnchor);
                assertThat(result.nextTrustAnchorId())
                        .isEqualTo(BigInteger.valueOf(builder.slot).toByteArray());
            }

            @Test
            void rotationBranchNotMatchingStateRoot_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.nextCommittee = new SyncCommittee(PUBKEYS, deterministicBytes(48, 0x99));
                builder.nextCommitteeBranch = zeroBranch(6);
                // stateRoot left at its default — the branch cannot verify against it
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("nextSyncCommittee branch does not verify");
            }

            @Test
            void rotationCommitteeWithoutBranch_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.nextCommittee = new SyncCommittee(PUBKEYS, deterministicBytes(48, 0x99));
                // nextCommitteeBranch left absent
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("must be both present or both absent");
            }
        }

        @Nested
        class AccountAndStorageProof {

            @Test
            void accountAbsentFromStateTrie_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.executionStateRoot = EMPTY_TRIE_ROOT;
                builder.accountProofNodes = new byte[0][];
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("contract account is absent from state trie");
            }

            @Test
            void wrongStorageProofEntryCount_throwsProofException() {
                var builder = validPayloadBuilder();
                builder.storageEntries = List.of(storageEntry(slotKey(0), List.of()));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("storageProof has 1 entries; expected 5");
            }

            @Test
            void threeParamStorageEntryRejected() {
                var builder = validPayloadBuilder();
                byte[] legacyEntry = Rlp.encodeList(List.of(
                        Rlp.encodeBytes(new byte[32]), Rlp.encodeBytes(new byte[32]), Rlp.encodeList(List.of())));
                builder.storageEntries = List.of(legacyEntry, legacyEntry, legacyEntry, legacyEntry, legacyEntry);
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("is not a [key, proof[]] RLP list");
            }

            @Test
            void provenStorageValueSurfacesInQueueMetadata() {
                byte[] runningHash = deterministicBytes(32, 0xAB);
                byte[][] storageMpt = buildStorageMptProof(slotKey(4), runningHash);
                byte[][] accountMpt = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);

                var builder = validPayloadBuilder();
                builder.executionStateRoot = accountMpt[0];
                builder.accountProofNodes = new byte[][] {accountMpt[1]};
                // Entries are sorted by slot key; the highest key (slotKey(4)) lands in the
                // lastMessageRunningHash position, so it proves the running hash. Entries 0..3 use
                // diverging keys that resolve to absent (all-zero) values against the single-leaf trie.
                List<byte[]> entries = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    entries.add(storageEntry(slotKey(i), List.of(storageMpt[1])));
                }
                builder.storageEntries = entries;

                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);

                assertThat(result.queueMetadata().lastMessageRunningHash()).isEqualTo(runningHash);
                assertThat(result.queueMetadata().sentRunningHash()).isEqualTo(new byte[32]);
                assertThat(result.queueMetadata().nextMessageId()).isZero();
            }
        }

        @Nested
        class SuccessPath {

            @Test
            void fullPayloadVerifies_returnsBeaconRootContentAndMetadata() {
                byte[] content = {0x0A, 0x0B, 0x0C};
                var builder = validPayloadBuilder();
                builder.bundleContent = content;

                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);

                byte[] expectedBeaconRoot = beaconHeaderRoot(
                        builder.slot, builder.proposerIndex, builder.parentRoot, builder.stateRoot, builder.bodyRoot());
                assertThat(result.beaconBlockRoot32()).isEqualTo(expectedBeaconRoot);
                assertThat(result.bundleContentBytes()).isEqualTo(content);
                assertThat(result.queueMetadata()).isNotNull();
                assertThat(result.nextTrustAnchor()).isNull();
                assertThat(result.newEndpointManifest()).isNull();
            }
        }

        @Nested
        @DisplayName("endpoint manifest advance (spec §4.9)")
        class EndpointManifest {

            @Test
            void bundleWithoutManifestReturnsNullManifest() {
                var result = verifier(new FakeBls())
                        .verifyBundle(validPayloadBuilder().build(), TRUST_ANCHOR);
                assertThat(result.newEndpointManifest()).isNull();
            }

            @Test
            void emptyManifestReconstructed() {
                byte[] bytes = serializeManifest(manifest(1L, 0));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);
                assertThat(result.newEndpointManifest()).isEqualTo(manifest(1L, 0));
                assertThat(result.newEndpointManifest().endpoints()).isEmpty();
            }

            @Test
            void singleEndpointManifestReconstructed() {
                byte[] bytes = serializeManifest(manifest(2L, 1));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);
                assertThat(result.newEndpointManifest()).isEqualTo(manifest(2L, 1));
            }

            @Test
            void manyEndpointManifestReconstructed() {
                byte[] bytes = serializeManifest(manifest(5L, 3));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);
                assertThat(result.newEndpointManifest().endpoints()).hasSize(3);
            }

            @Test
            void commitmentMismatchThrowsProofException() {
                byte[] bytes = serializeManifest(manifest(1L, 0));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), deterministicBytes(32, 0x99));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("does not match the proven commitment");
            }

            @Test
            void wrongCommitmentSlotThrowsProofException() {
                byte[] bytes = serializeManifest(manifest(1L, 0));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                // Point the manifest entry at a slot other than the pinned commitment slot.
                builder.manifestStorageEntry = storageEntry(slotKey(0x13), List.of(new byte[] {0x01}));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("not for the endpoint-manifest commitment slot");
            }

            @Test
            void commitmentSlotAbsentFromTrieThrowsProofException() {
                byte[] bytes = serializeManifest(manifest(1L, 0));
                // Storage trie proves a different slot, so the commitment slot resolves to absent.
                var builder = validPayloadBuilder().withManifest(bytes, slotKey(0x00), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("commitment slot absent from the storage trie");
            }

            @Test
            void manifestVersionZeroThrowsProofException() {
                byte[] bytes = serializeManifest(manifest(0L, 0));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("version is 0");
            }

            @Test
            void emptyManifestBytesTreatedAsNoAdvance() {
                // Empty (non-null) manifest bytes mean "no manifest advance": the bundle must still verify and
                // keep its messages, NOT fail with "version is 0" (parseStrict on empty yields version 0).
                var builder = validPayloadBuilder().withManifest(new byte[0], manifestSlot(), keccak256(new byte[0]));
                var result = verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR);
                assertThat(result.newEndpointManifest()).isNull();
            }

            @Test
            void serviceAddressMismatchThrowsProofException() {
                byte[] bytes = serializeManifest(ClprEndpointManifest.newBuilder()
                        .version(1L)
                        .serviceAddress(Bytes.wrap(deterministicBytes(20, 0x77)))
                        .build());
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("service_address does not match");
            }

            @Test
            void unknownFieldInManifestThrowsProofException() {
                byte[] bytes = appendUnknownField(serializeManifest(manifest(1L, 0)));
                var builder = validPayloadBuilder().withManifest(bytes, manifestSlot(), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(builder.build(), TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("not a valid ClprEndpointManifest");
            }
        }

        @Nested
        @DisplayName("manifest-only recovery bundle (spec §8.1.4)")
        class ManifestOnlyBundle {

            @Test
            void manifestOnlyBundleReturnsEmptyContentAndManifest() {
                byte[] bytes = serializeManifest(manifest(3L, 1));
                byte[] payload = manifestOnlyPayload(bytes, manifestSlot(), manifestSlot(), keccak256(bytes));

                var result = verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR);

                // No queue state, no message content — only the proven manifest is surfaced.
                assertThat(result.bundleContentBytes()).isEmpty();
                assertThat(result.newEndpointManifest()).isEqualTo(manifest(3L, 1));
                assertThat(result.nextTrustAnchor()).isNull();
                assertThat(result.nextTrustAnchorId()).isNull();
                // Absent queue metadata is the all-zero sentinel (nextMessageId == 0), not null.
                assertThat(result.queueMetadata().nextMessageId()).isZero();
                assertThat(result.queueMetadata().receivedMessageId()).isZero();
                assertThat(result.queueMetadata().sentRunningHash()).isEqualTo(new byte[32]);
                assertThat(result.queueMetadata().receivedRunningHash()).isEqualTo(new byte[32]);
            }

            @Test
            void manifestOnlyBundleStillEnforcesBls() {
                // The recovery bundle drops the queue proof but still authenticates the whole chain.
                FakeBls bls = new FakeBls();
                bls.result = false;
                byte[] bytes = serializeManifest(manifest(3L, 1));
                byte[] payload = manifestOnlyPayload(bytes, manifestSlot(), manifestSlot(), keccak256(bytes));
                assertThatThrownBy(() -> verifier(bls).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("BLS signature verification failed");
            }

            @Test
            void manifestOnlyBundleCommitmentMismatchThrows() {
                byte[] bytes = serializeManifest(manifest(3L, 1));
                // The proven slot value is not keccak256(manifestBytes).
                byte[] payload =
                        manifestOnlyPayload(bytes, manifestSlot(), manifestSlot(), deterministicBytes(32, 0x99));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("does not match the proven commitment");
            }

            @Test
            void manifestOnlyBundleWrongCommitmentSlotThrows() {
                byte[] bytes = serializeManifest(manifest(3L, 1));
                // The storage entry (and the slot it proves) is not the pinned commitment slot (18).
                byte[] payload = manifestOnlyPayload(bytes, slotKey(0x13), slotKey(0x13), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("not for the endpoint-manifest commitment slot");
            }

            @Test
            void manifestOnlyBundleVersionZeroThrows() {
                byte[] bytes = serializeManifest(manifest(0L, 0));
                byte[] payload = manifestOnlyPayload(bytes, manifestSlot(), manifestSlot(), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("version is 0");
            }

            @Test
            void manifestOnlyBundleServiceAddressMismatchThrows() {
                byte[] bytes = serializeManifest(ClprEndpointManifest.newBuilder()
                        .version(3L)
                        .serviceAddress(Bytes.wrap(deterministicBytes(20, 0x77)))
                        .build());
                byte[] payload = manifestOnlyPayload(bytes, manifestSlot(), manifestSlot(), keccak256(bytes));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("service_address does not match");
            }

            @Test
            void emptyBundleWithNoQueueContentOrManifestThrows() {
                // A bundle with no queue metadata (empty storage proof), no content, and no manifest advance is
                // empty/meaningless — the verifier rejects it. This is the §8.1.4 invariant: when both queue
                // metadata and content are absent, the bundle MUST carry a manifest. Decoded as an ordinary
                // 11-item bundle; there is no distinct manifest-only shape or path.
                byte[] payload =
                        manifestOnlyPayload(new byte[0], manifestSlot(), manifestSlot(), keccak256(new byte[0]));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyBundle(payload, TRUST_ANCHOR))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("no queue metadata, no content, and no endpoint-manifest advance");
            }
        }
    }

    @Nested
    @DisplayName("verifyConfigPayload(...)")
    class VerifyConfigPayload {

        @Nested
        class PayloadDecoding {

            @Test
            void garbagePayload_throwsProofException() {
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyConfigPayload(new byte[] {0x01, 0x02}))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("configPayload is not a valid RLP item");
            }

            @Test
            void wrongTopLevelItemCount_throwsProofException() {
                byte[] payload = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[0])));
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyConfigPayload(payload))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("expected top-level RLP list of 5 items");
            }
        }

        @Nested
        class LedgerConfiguration {

            @Test
            void invalidLedgerConfigurationBytes_throwsProofException() {
                var builder = validConfigBuilder();
                // 0x08 alone = field 1 wire-type 0 (varint), then EOF → PBJ parse error
                builder.ledgerConfigBytes = new byte[] {0x08};
                assertThatThrownBy(() -> verifier(new FakeBls()).verifyConfigPayload(builder.build()))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("not a valid ClprLedgerConfiguration");
            }

            @Test
            void unknownFieldInLedgerConfig_throwsProofException() {
                // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
                // Serialize a valid ClprLedgerConfiguration, append a record for proto field #255
                // (which the schema doesn't define), and expect the strict parse to reject it.
                byte[] validLedgerConfig = serializeLedgerConfig(ClprLedgerConfiguration.newBuilder()
                        .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                        .build());
                var builder = validConfigBuilder();
                builder.ledgerConfigBytes = appendUnknownField(validLedgerConfig);

                assertThatThrownBy(() -> verifier(new FakeBls()).verifyConfigPayload(builder.build()))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("not a valid ClprLedgerConfiguration");
            }
        }

        @Nested
        class SuccessPath {

            @Test
            void successPath_derivesInitialTrustAnchorFromCommittee() {
                var result = verifier(new FakeBls())
                        .verifyConfigPayload(validConfigBuilder().build());

                byte[] expectedAnchor =
                        TrustAnchor.encode(COMMITTEE, GENESIS_VALIDATORS_ROOT, FORK_VERSION, SERVICE_ADDR);
                assertThat(result.ledgerConfiguration().initialTrustAnchor().toByteArray())
                        .isEqualTo(expectedAnchor);
                assertThat(result.ledgerConfiguration().initialTrustAnchorId().toByteArray())
                        .isEqualTo(expectedAnchor);
                assertThat(result.ledgerConfiguration().serviceAddress().toByteArray())
                        .isEqualTo(SERVICE_ADDR);
                assertThat(result.slot()).isEqualTo(CONFIG_SLOT);
            }

            @Test
            void payloadTrustAnchorFieldsAreIgnored() {
                // Any initialTrustAnchor / initialTrustAnchorId baked into the ledgerConfiguration
                // must be overwritten by the anchor derived from the proven sync committee.
                var builder = validConfigBuilder();
                builder.ledgerConfigBytes = serializeLedgerConfig(ClprLedgerConfiguration.newBuilder()
                        .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                        .initialTrustAnchor(Bytes.wrap(deterministicBytes(20, 0xDE)))
                        .initialTrustAnchorId(Bytes.wrap(deterministicBytes(32, 0xBE)))
                        .build());

                var result = verifier(new FakeBls()).verifyConfigPayload(builder.build());

                byte[] expectedAnchor =
                        TrustAnchor.encode(COMMITTEE, GENESIS_VALIDATORS_ROOT, FORK_VERSION, SERVICE_ADDR);
                assertThat(result.ledgerConfiguration().initialTrustAnchor().toByteArray())
                        .isEqualTo(expectedAnchor);
                assertThat(result.ledgerConfiguration().initialTrustAnchorId().toByteArray())
                        .isEqualTo(expectedAnchor);
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Payload construction
    // -----------------------------------------------------------------------------------

    /**
     * Builds a structurally complete payload that passes every verification step with a {@link FakeBls} returning true:
     * the committee comes from {@link #TRUST_ANCHOR} (not the payload), all bits are set, the execution branch folds to
     * the header's body root, and the account/storage proofs use single-leaf tries.
     */
    private static PayloadBuilder validPayloadBuilder() {
        return new PayloadBuilder();
    }

    private static final class PayloadBuilder {

        long slot = 12345;
        long proposerIndex = 7;
        byte[] parentRoot = deterministicBytes(32, 0x21);
        byte[] stateRoot = deterministicBytes(32, 0x22);
        byte[] bodyRootOverride; // when null, computed from the execution branch fold
        byte[] bits = allBitsSet();
        byte[] signature = new byte[96];
        byte[][] executionBranch = zeroBranch(9);
        byte[] executionStateRoot;
        SyncCommittee nextCommittee; // absent when null
        byte[][] nextCommitteeBranch; // absent when null
        byte[][] accountProofNodes;
        List<byte[]> storageEntries;
        byte[] bundleContent = {0x01, 0x02};
        byte[] attestedHeaderOverride; // raw RLP override for structural tests
        byte[] manifestBytes; // null → 9-item bundle; non-null → 11-item (manifest advance)
        byte[] manifestStorageEntry; // RLP [key, proof[]] for the commitment slot (set with manifestBytes)

        PayloadBuilder() {
            // Default execution state: account with empty storage in a single-leaf state trie.
            byte[][] accountMpt = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            executionStateRoot = accountMpt[0];
            accountProofNodes = new byte[][] {accountMpt[1]};
            List<byte[]> entries = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                entries.add(storageEntry(slotKey(i), List.of()));
            }
            storageEntries = entries;
        }

        /**
         * Turns this into an 11-item bundle carrying an endpoint-manifest advance: proves {@code storedCommitment}
         * at {@code slotToProve} (and the queue-metadata slots as absent) against a single-leaf storage trie, and
         * attaches {@code manifestBytes} + its commitment-slot storage proof.
         */
        PayloadBuilder withManifest(byte[] manifestBytes, byte[] slotToProve, byte[] storedCommitment) {
            byte[][] storageMpt = buildStorageMptProof(slotToProve, storedCommitment);
            byte[][] accountMpt = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
            this.executionStateRoot = accountMpt[0];
            this.accountProofNodes = new byte[][] {accountMpt[1]};
            List<byte[]> entries = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                entries.add(storageEntry(slotKey(i), List.of(storageMpt[1])));
            }
            this.storageEntries = entries;
            this.manifestBytes = manifestBytes;
            this.manifestStorageEntry = storageEntry(manifestSlot(), List.of(storageMpt[1]));
            return this;
        }

        byte[] bodyRoot() {
            return bodyRootOverride != null
                    ? bodyRootOverride
                    : foldBranch(executionStateRoot, executionBranch, 290); // execution_payload.state_root leaf index
        }

        byte[] build() {
            byte[] attestedHeader = attestedHeaderOverride != null
                    ? attestedHeaderOverride
                    : Rlp.encodeList(List.of(
                            Rlp.encodeUint(slot),
                            Rlp.encodeUint(proposerIndex),
                            Rlp.encodeBytes(parentRoot),
                            Rlp.encodeBytes(stateRoot),
                            Rlp.encodeBytes(bodyRoot())));

            byte[] syncAggregate = Rlp.encodeList(List.of(Rlp.encodeBytes(bits), Rlp.encodeBytes(signature)));

            List<byte[]> encodedExecBranch = new ArrayList<>();
            for (byte[] node : executionBranch) {
                encodedExecBranch.add(Rlp.encodeBytes(node));
            }

            byte[] nextCommitteeEncoded = nextCommittee == null
                    ? Rlp.encodeBytes(new byte[0])
                    : committeeRlp(nextCommittee.pubkeys(), nextCommittee.aggregatePubkey48());
            byte[] nextBranchEncoded;
            if (nextCommitteeBranch == null) {
                nextBranchEncoded = Rlp.encodeList(List.of());
            } else {
                List<byte[]> encodedNextBranch = new ArrayList<>();
                for (byte[] node : nextCommitteeBranch) {
                    encodedNextBranch.add(Rlp.encodeBytes(node));
                }
                nextBranchEncoded = Rlp.encodeList(encodedNextBranch);
            }

            List<byte[]> encodedAccountProof = new ArrayList<>();
            for (byte[] node : accountProofNodes) {
                encodedAccountProof.add(Rlp.encodeBytes(node));
            }

            List<byte[]> items = new ArrayList<>(List.of(
                    attestedHeader,
                    syncAggregate,
                    Rlp.encodeBytes(executionStateRoot),
                    Rlp.encodeList(encodedExecBranch),
                    nextCommitteeEncoded,
                    nextBranchEncoded,
                    Rlp.encodeList(encodedAccountProof),
                    Rlp.encodeList(storageEntries),
                    Rlp.encodeBytes(bundleContent)));
            if (manifestBytes != null) {
                items.add(Rlp.encodeBytes(manifestBytes));
                items.add(manifestStorageEntry);
            }
            return Rlp.encodeList(items);
        }
    }

    /**
     * Builds a manifest-only recovery bundle (spec §8.1.4): the distinct 7-item shape
     * {@code [attestedHeader, syncAggregate, executionStateRoot, executionBranch, accountProof,
     * manifestStorageProof, manifestPreimage]}. It authenticates the chain down to the CLPR account exactly
     * as a normal bundle (reusing the same single-leaf MPT/SSZ fixtures) but carries no queue proof and no
     * bundle content. {@code storedCommitment} is the value the storage trie proves at {@code slotToProve};
     * {@code entryKey} is the slot key the manifest storage entry advertises (equal to {@code slotToProve}
     * for a well-formed proof, or divergent to exercise the wrong-slot rejection).
     */
    private static byte[] manifestOnlyPayload(
            byte[] manifestBytes, byte[] entryKey, byte[] slotToProve, byte[] storedCommitment) {
        byte[][] storageMpt = buildStorageMptProof(slotToProve, storedCommitment);
        byte[][] accountMpt = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
        byte[] executionStateRoot = accountMpt[0];
        byte[][] executionBranch = zeroBranch(9);

        long slot = 12345;
        long proposerIndex = 7;
        byte[] parentRoot = deterministicBytes(32, 0x21);
        byte[] stateRoot = deterministicBytes(32, 0x22);
        byte[] bodyRoot = foldBranch(executionStateRoot, executionBranch, 290); // execution_payload.state_root leaf
        byte[] attestedHeader = Rlp.encodeList(List.of(
                Rlp.encodeUint(slot),
                Rlp.encodeUint(proposerIndex),
                Rlp.encodeBytes(parentRoot),
                Rlp.encodeBytes(stateRoot),
                Rlp.encodeBytes(bodyRoot)));

        byte[] syncAggregate = Rlp.encodeList(List.of(Rlp.encodeBytes(allBitsSet()), Rlp.encodeBytes(new byte[96])));

        List<byte[]> encodedExecBranch = new ArrayList<>();
        for (byte[] node : executionBranch) {
            encodedExecBranch.add(Rlp.encodeBytes(node));
        }
        byte[] accountProof = Rlp.encodeList(List.of(Rlp.encodeBytes(accountMpt[1])));
        byte[] manifestStorageEntry = storageEntry(entryKey, List.of(storageMpt[1]));

        // A manifest-only recovery bundle (spec §8.1.4) is a NORMAL 11-item bundle, not a distinct shape: no
        // rotation (empty committee + empty branch), an EMPTY queue storage proof, EMPTY bundle content, and the
        // manifest advance (preimage + commitment-slot storage proof). It is decoded and verified as an ordinary
        // bundle; the verifier reads the empty queue proof as "absent metadata" (nextMessageId == 0 sentinel).
        return Rlp.encodeList(List.of(
                attestedHeader,
                syncAggregate,
                Rlp.encodeBytes(executionStateRoot),
                Rlp.encodeList(encodedExecBranch),
                Rlp.encodeBytes(new byte[0]), // nextCommittee absent
                Rlp.encodeList(List.of()), // nextCommitteeBranch absent
                accountProof,
                Rlp.encodeList(List.of()), // queue storage proof empty
                Rlp.encodeBytes(new byte[0]), // bundle content empty
                Rlp.encodeBytes(manifestBytes), // manifest preimage
                manifestStorageEntry)); // manifest commitment-slot storage proof
    }

    /**
     * Builds a self-describing config payload: the initial committee, the chain-pinning fields, and the ledger
     * configuration. No proof or signature is carried.
     */
    private static ConfigPayloadBuilder validConfigBuilder() {
        return new ConfigPayloadBuilder();
    }

    private static final class ConfigPayloadBuilder {

        byte[] genesisValidatorsRoot = GENESIS_VALIDATORS_ROOT;
        byte[] forkVersion = FORK_VERSION;
        byte[] ledgerConfigBytes;
        long slot = CONFIG_SLOT;

        ConfigPayloadBuilder() {
            ledgerConfigBytes = serializeLedgerConfig(ClprLedgerConfiguration.newBuilder()
                    .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                    .build());
        }

        byte[] build() {
            List<byte[]> encodedKeys = new ArrayList<>(COMMITTEE_SIZE);
            for (byte[] key : PUBKEYS) {
                encodedKeys.add(Rlp.encodeBytes(key));
            }
            byte[] syncCommittee =
                    Rlp.encodeList(List.of(Rlp.encodeList(encodedKeys), Rlp.encodeBytes(AGGREGATE_PUBKEY)));

            return Rlp.encodeList(List.of(
                    Rlp.encodeUint(slot),
                    syncCommittee,
                    Rlp.encodeBytes(genesisValidatorsRoot),
                    Rlp.encodeBytes(forkVersion),
                    Rlp.encodeBytes(ledgerConfigBytes)));
        }
    }

    private static byte[] serializeLedgerConfig(ClprLedgerConfiguration config) {
        return ClprLedgerConfiguration.PROTOBUF.toBytes(config).toByteArray();
    }

    private static byte[] serializeManifest(ClprEndpointManifest manifest) {
        return ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
    }

    /** A manifest bound to {@link #SERVICE_ADDR} at the given version with {@code endpointCount} placeholder endpoints. */
    private static ClprEndpointManifest manifest(long version, int endpointCount) {
        List<ClprEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < endpointCount; i++) {
            endpoints.add(ClprEndpoint.newBuilder()
                    .tlsCertificate(Bytes.wrap(deterministicBytes(48, 0x30 + i)))
                    .build());
        }
        return ClprEndpointManifest.newBuilder()
                .version(version)
                .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                .endpoints(endpoints)
                .build();
    }

    /** The 32-byte commitment slot the verifier pins the manifest proof to (slot 18). */
    private static byte[] manifestSlot() {
        return slotKey(0x12);
    }

    /**
     * RLP {@code [key, proofNodes[]]} storage-proof entry.
     */
    private static byte[] storageEntry(byte[] key32, List<byte[]> proofNodes) {
        List<byte[]> encodedNodes = new ArrayList<>();
        for (byte[] node : proofNodes) {
            encodedNodes.add(Rlp.encodeBytes(node));
        }
        return Rlp.encodeList(List.of(Rlp.encodeBytes(key32), Rlp.encodeList(encodedNodes)));
    }

    private static byte[] slotKey(int index) {
        byte[] key = new byte[32];
        key[31] = (byte) index;
        return key;
    }

    // -----------------------------------------------------------------------------------
    // Committee / bits helpers
    // -----------------------------------------------------------------------------------

    private static byte[][] buildPubkeys() {
        byte[][] keys = new byte[COMMITTEE_SIZE][];
        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            keys[i] = new byte[48];
            for (int j = 0; j < 48; j++) {
                keys[i][j] = (byte) (i * 7 + j);
            }
        }
        return keys;
    }

    private static byte[] allBitsSet() {
        byte[] bits = new byte[64];
        Arrays.fill(bits, (byte) 0xFF);
        return bits;
    }

    /**
     * SSZ Bitvector[512] with the first {@code n} bits set (bit i = bit i%8 of byte i/8).
     */
    private static byte[] firstNBitsSet(int n) {
        byte[] bits = new byte[64];
        for (int i = 0; i < n; i++) {
            bits[i / 8] |= (byte) (1 << (i % 8));
        }
        return bits;
    }

    private static byte[][] zeroBranch(int depth) {
        byte[][] branch = new byte[depth][];
        for (int i = 0; i < depth; i++) {
            branch[i] = new byte[32];
        }
        return branch;
    }

    // -----------------------------------------------------------------------------------
    // MPT fixture helpers (same single-leaf technique as QbftProofVerifierTest)
    // -----------------------------------------------------------------------------------

    /**
     * Builds a single-leaf MPT proof for the given contract account.
     *
     * @return {@code [stateRoot32, leafNodeRlp]}
     */
    private static byte[][] buildAccountMptProof(byte[] contractAddr20, byte[] storageRoot32, byte[] codeHash32) {
        byte[] accountKey32 = keccak256(contractAddr20);
        byte[] accountRlp = Rlp.encodeList(List.of(
                Rlp.encodeUint(0L), Rlp.encodeUint(0L), Rlp.encodeBytes(storageRoot32), Rlp.encodeBytes(codeHash32)));

        // Hex-prefix for 64-nibble path: flag byte 0x20 (leaf, even) + 32 key bytes
        byte[] hexPrefix = new byte[33];
        hexPrefix[0] = 0x20;
        System.arraycopy(accountKey32, 0, hexPrefix, 1, 32);

        byte[] leafNode = Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix), Rlp.encodeBytes(accountRlp)));
        return new byte[][] {keccak256(leafNode), leafNode};
    }

    /**
     * Builds a single-leaf MPT proof for a storage slot.
     *
     * @return {@code [storageRoot32, leafNodeRlp]}
     */
    private static byte[][] buildStorageMptProof(byte[] storageSlotKey32, byte[] provenValue32) {
        byte[] storageKeyHash = keccak256(storageSlotKey32);

        byte[] hexPrefix = new byte[33];
        hexPrefix[0] = 0x20;
        System.arraycopy(storageKeyHash, 0, hexPrefix, 1, 32);

        // The leaf value is Rlp(Rlp(bytes32)); decodeTrieStorageValueAsBytes32 peels one layer.
        byte[] encodedValue = Rlp.encodeBytes(provenValue32);
        byte[] leafNode = Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix), Rlp.encodeBytes(encodedValue)));
        return new byte[][] {keccak256(leafNode), leafNode};
    }

    private static byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }
}
