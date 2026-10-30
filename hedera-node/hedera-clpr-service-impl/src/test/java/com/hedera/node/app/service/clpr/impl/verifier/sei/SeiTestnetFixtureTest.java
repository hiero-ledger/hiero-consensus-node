// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiCommit;
import com.hedera.hapi.node.state.clpr.SeiCommitSig;
import com.hedera.hapi.node.state.clpr.SeiHeader;
import com.hedera.hapi.node.state.clpr.SeiSignedHeader;
import com.hedera.hapi.node.state.clpr.SeiStateProof;
import com.hedera.hapi.node.state.clpr.SeiStorageProofEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies every Sei primitive against a golden fixture captured from Sei testnet
 * ({@code atlantic-2}): an ICS-23 storage proof for an EVM contract slot at height H,
 * the signed header at H+1 (whose {@code app_hash} commits the state of H), and the
 * validator set. Captured by {@code sei-proof-test/fetch-fixture.js}.
 */
class SeiTestnetFixtureTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Decoder B64 = Base64.getDecoder();
    private static final JsonNode FIXTURE = loadFixture();
    private static final JsonNode HEADER = FIXTURE.path("signedHeader").path("header");
    private static final JsonNode COMMIT = FIXTURE.path("signedHeader").path("commit");

    private static JsonNode loadFixture() {
        try (final var in = SeiTestnetFixtureTest.class.getResourceAsStream("/sei/sei-testnet-fixture.json")) {
            return new ObjectMapper().readTree(in);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SeiValidatorEntry> fixtureValidators() {
        final List<SeiValidatorEntry> validators = new ArrayList<>();
        for (final JsonNode v : FIXTURE.path("validators")) {
            validators.add(SeiValidatorEntry.newBuilder()
                    .ed25519PubKey(Bytes.wrap(
                            B64.decode(v.path("pub_key").path("value").asText())))
                    .votingPower(v.path("voting_power").asLong())
                    .build());
        }
        return validators;
    }

    private static SeiValidatorSet fixtureValidatorSet() {
        return SeiValidatorSet.newBuilder().validators(fixtureValidators()).build();
    }

    private static SeiStateProof fixtureStateProof() {
        return SeiStateProof.newBuilder()
                .signedHeader(SeiSignedHeader.newBuilder()
                        .header(fixtureHeaderProto())
                        .commit(fixtureCommitProto())
                        .build())
                .storeKey(Bytes.wrap("evm".getBytes(StandardCharsets.UTF_8)))
                .multistoreProof(Bytes.wrap(
                        B64.decode(proofOp("ics23:simple").path("data").asText())))
                .storageProofs(List.of(SeiStorageProofEntry.newBuilder()
                        .key(Bytes.wrap(
                                B64.decode(FIXTURE.path("query").path("key").asText())))
                        .value(Bytes.wrap(
                                B64.decode(FIXTURE.path("query").path("value").asText())))
                        .iavlProof(Bytes.wrap(
                                B64.decode(proofOp("ics23:iavl").path("data").asText())))
                        .build()))
                .build();
    }

    private static SeiHeader fixtureHeaderProto() {
        final var time = Instant.parse(HEADER.path("time").asText());
        return SeiHeader.newBuilder()
                .versionBlock(HEADER.path("version").path("block").asLong())
                .versionApp(HEADER.path("version").path("app").asLong())
                .chainId(HEADER.path("chain_id").asText())
                .height(HEADER.path("height").asLong())
                .time(timestamp(time))
                .lastBlockId(blockRef(HEADER.path("last_block_id")))
                .lastCommitHash(
                        Bytes.wrap(HEX.parseHex(HEADER.path("last_commit_hash").asText())))
                .dataHash(Bytes.wrap(HEX.parseHex(HEADER.path("data_hash").asText())))
                .validatorsHash(
                        Bytes.wrap(HEX.parseHex(HEADER.path("validators_hash").asText())))
                .nextValidatorsHash(Bytes.wrap(
                        HEX.parseHex(HEADER.path("next_validators_hash").asText())))
                .consensusHash(
                        Bytes.wrap(HEX.parseHex(HEADER.path("consensus_hash").asText())))
                .appHash(Bytes.wrap(HEX.parseHex(HEADER.path("app_hash").asText())))
                .lastResultsHash(
                        Bytes.wrap(HEX.parseHex(HEADER.path("last_results_hash").asText())))
                .evidenceHash(
                        Bytes.wrap(HEX.parseHex(HEADER.path("evidence_hash").asText())))
                .proposerAddress(
                        Bytes.wrap(HEX.parseHex(HEADER.path("proposer_address").asText())))
                .build();
    }

    private static SeiCommit fixtureCommitProto() {
        final List<SeiCommitSig> signatures = new ArrayList<>();
        final byte[] signersBits = new byte[(fixtureValidators().size() + 7) / 8];
        final JsonNode validatorNodes = FIXTURE.path("validators");
        for (int i = 0; i < validatorNodes.size(); i++) {
            final byte[] pubKey = B64.decode(
                    validatorNodes.get(i).path("pub_key").path("value").asText());
            final byte[] address = SeiHashing.validatorAddress(pubKey);
            final JsonNode sig = commitSignatureForAddress(address);
            if (sig == null || sig.path("block_id_flag").asInt() != 2) {
                continue;
            }
            signersBits[i / 8] |= (byte) (0x80 >>> (i % 8));
            final var timestamp = Instant.parse(sig.path("timestamp").asText());
            signatures.add(SeiCommitSig.newBuilder()
                    .timestamp(timestamp(timestamp))
                    .signature(Bytes.wrap(B64.decode(sig.path("signature").asText())))
                    .build());
        }
        return SeiCommit.newBuilder()
                .round(COMMIT.path("round").asInt())
                .partSetTotal(
                        COMMIT.path("block_id").path("parts").path("total").asInt())
                .partSetHash(Bytes.wrap(HEX.parseHex(
                        COMMIT.path("block_id").path("parts").path("hash").asText())))
                .signersBits(Bytes.wrap(signersBits))
                .signatures(signatures)
                .build();
    }

    private static JsonNode commitSignatureForAddress(final byte[] address) {
        final String hexAddress = HEX.formatHex(address);
        for (final JsonNode sig : COMMIT.path("signatures")) {
            if (hexAddress.equalsIgnoreCase(sig.path("validator_address").asText())) {
                return sig;
            }
        }
        return null;
    }

    private static SeiBlockRef blockRef(final JsonNode blockId) {
        return SeiBlockRef.newBuilder()
                .hash(Bytes.wrap(HEX.parseHex(blockId.path("hash").asText())))
                .partSetTotal(blockId.path("parts").path("total").asInt())
                .partSetHash(Bytes.wrap(
                        HEX.parseHex(blockId.path("parts").path("hash").asText())))
                .build();
    }

    private static Timestamp timestamp(final Instant instant) {
        return Timestamp.newBuilder()
                .seconds(instant.getEpochSecond())
                .nanos(instant.getNano())
                .build();
    }

    private static byte[] fixtureContractAddress() {
        final String hexAddress = FIXTURE.path("contract").asText().replaceFirst("^0x", "");
        return HEX.parseHex(hexAddress);
    }

    private static SeiBlockRef commitBlockId() {
        return blockRef(COMMIT.path("block_id"));
    }

    private static JsonNode proofOp(final String type) {
        for (final JsonNode op : FIXTURE.path("query").path("proofOps").path("ops")) {
            if (type.equals(op.path("type").asText())) {
                return op;
            }
        }
        throw new IllegalStateException("no proof op of type " + type);
    }

    @Nested
    class StateProofVerifier {
        @Test
        void verifierAcceptsCapturedSeiTestnetStateProof() {
            final var proven = SeiCometBftProofVerifier.verifyStateProof(
                    fixtureStateProof(), fixtureValidatorSet(), fixtureContractAddress());
            final byte[] value = B64.decode(FIXTURE.path("query").path("value").asText());

            assertThat(proven.header().chainId()).isEqualTo("atlantic-2");
            assertThat(proven.header().height())
                    .isEqualTo(FIXTURE.path("headerHeight").asLong());
            assertThat(proven.headerHash32())
                    .isEqualTo(HEX.parseHex(COMMIT.path("block_id").path("hash").asText()));
            assertThat(proven.slotValues().length).isEqualTo(1);
            assertThat(proven.slotValues()[0]).isEqualTo(value);
        }
    }

    @Nested
    class HeaderHashing {
        @Test
        void headerHashMatchesSignedBlockId() {
            assertThat(SeiHashing.headerHash(fixtureHeaderProto()))
                    .isEqualTo(HEX.parseHex(COMMIT.path("block_id").path("hash").asText()));
        }

        @Test
        void tamperedAppHashChangesHeaderHash() {
            final var header = fixtureHeaderProto();
            final byte[] tamperedAppHash = header.appHash().toByteArray();
            tamperedAppHash[0] ^= 0x01;
            final var tampered =
                    header.copyBuilder().appHash(Bytes.wrap(tamperedAppHash)).build();
            assertThat(SeiHashing.headerHash(tampered)).isNotEqualTo(SeiHashing.headerHash(header));
        }
    }

    @Nested
    class ValidatorSetHashing {
        @Test
        void validatorSetHashMatchesHeader() {
            assertThat(SeiHashing.validatorSetHash(fixtureValidatorSet()))
                    .isEqualTo(HEX.parseHex(HEADER.path("validators_hash").asText()));
        }

        @Test
        void reorderedValidatorSetHashDiffers() {
            final var validators = fixtureValidators();
            final var reordered = new ArrayList<>(validators.reversed());
            final var reorderedSet =
                    SeiValidatorSet.newBuilder().validators(reordered).build();
            assertThat(SeiHashing.validatorSetHash(reorderedSet))
                    .isNotEqualTo(HEX.parseHex(HEADER.path("validators_hash").asText()));
        }

        @Test
        void addressesDeriveFromPubKeys() {
            final var validatorNodes = FIXTURE.path("validators");
            for (int i = 0; i < validatorNodes.size(); i++) {
                final var node = validatorNodes.get(i);
                final byte[] pubKey =
                        B64.decode(node.path("pub_key").path("value").asText());
                assertThat(SeiHashing.validatorAddress(pubKey))
                        .as("address of validator %d", i)
                        .isEqualTo(HEX.parseHex(node.path("address").asText()));
            }
        }
    }

    @Nested
    class CommitSignatures {
        @Test
        void everyCommitSignatureVerifiesAndQuorumIsMet() {
            final var validators = fixtureValidators();
            final var blockId = commitBlockId();
            final long height = COMMIT.path("height").asLong();
            final long round = COMMIT.path("round").asLong();
            final String chainId = HEADER.path("chain_id").asText();

            long totalPower = 0;
            for (final var v : validators) {
                totalPower += v.votingPower();
            }
            long signedPower = 0;
            int verified = 0;
            for (final JsonNode sig : COMMIT.path("signatures")) {
                if (sig.path("block_id_flag").asInt() != 2) {
                    continue; // only BLOCK_ID_FLAG_COMMIT counts toward the block's quorum
                }
                final byte[] address =
                        HEX.parseHex(sig.path("validator_address").asText());
                final var validator = validators.stream()
                        .filter(v -> java.util.Arrays.equals(
                                SeiHashing.validatorAddress(v.ed25519PubKey().toByteArray()), address))
                        .findFirst()
                        .orElseThrow();
                final var timestamp = Instant.parse(sig.path("timestamp").asText());
                final byte[] signBytes =
                        SeiHashing.precommitSignBytes(chainId, height, round, blockId, timestamp(timestamp));
                assertThat(SeiHashing.verifyEd25519(
                                validator.ed25519PubKey().toByteArray(),
                                signBytes,
                                B64.decode(sig.path("signature").asText())))
                        .as(
                                "signature of validator %s",
                                sig.path("validator_address").asText())
                        .isTrue();
                signedPower += validator.votingPower();
                verified++;
            }
            assertThat(verified).isGreaterThan(0);
            assertThat(signedPower * 3)
                    .as("quorum: %d of %d", signedPower, totalPower)
                    .isGreaterThan(totalPower * 2);
        }

        @Test
        void signBytesWithWrongTimestampDoNotVerify() {
            final var validators = fixtureValidators();
            final var blockId = commitBlockId();
            final String chainId = HEADER.path("chain_id").asText();
            final JsonNode sig = COMMIT.path("signatures").get(0);
            final byte[] address = HEX.parseHex(sig.path("validator_address").asText());
            final var validator = validators.stream()
                    .filter(v -> java.util.Arrays.equals(
                            SeiHashing.validatorAddress(v.ed25519PubKey().toByteArray()), address))
                    .findFirst()
                    .orElseThrow();
            final var timestamp = Instant.parse(sig.path("timestamp").asText());
            final var wrongTimestamp = Timestamp.newBuilder()
                    .seconds(timestamp.getEpochSecond())
                    .nanos(timestamp.getNano() + 1)
                    .build();
            final byte[] signBytes = SeiHashing.precommitSignBytes(
                    chainId,
                    COMMIT.path("height").asLong(),
                    COMMIT.path("round").asLong(),
                    blockId,
                    wrongTimestamp);
            assertThat(SeiHashing.verifyEd25519(
                            validator.ed25519PubKey().toByteArray(),
                            signBytes,
                            B64.decode(sig.path("signature").asText())))
                    .isFalse();
        }

        @Test
        void signBytesWithWrongChainIdDoNotVerify() {
            final var validators = fixtureValidators();
            final var blockId = commitBlockId();
            final JsonNode sig = COMMIT.path("signatures").get(0);
            final byte[] address = HEX.parseHex(sig.path("validator_address").asText());
            final var validator = validators.stream()
                    .filter(v -> java.util.Arrays.equals(
                            SeiHashing.validatorAddress(v.ed25519PubKey().toByteArray()), address))
                    .findFirst()
                    .orElseThrow();
            final var timestamp = Instant.parse(sig.path("timestamp").asText());
            final byte[] signBytes = SeiHashing.precommitSignBytes(
                    "pacific-1",
                    COMMIT.path("height").asLong(),
                    COMMIT.path("round").asLong(),
                    blockId,
                    timestamp(timestamp));
            assertThat(SeiHashing.verifyEd25519(
                            validator.ed25519PubKey().toByteArray(),
                            signBytes,
                            B64.decode(sig.path("signature").asText())))
                    .isFalse();
        }
    }

    @Nested
    class Ics23Proofs {
        private SeiIcs23.ExistenceProof iavlProof() {
            return SeiIcs23.parseCommitmentProof(
                    B64.decode(proofOp("ics23:iavl").path("data").asText()));
        }

        private SeiIcs23.ExistenceProof simpleProof() {
            return SeiIcs23.parseCommitmentProof(
                    B64.decode(proofOp("ics23:simple").path("data").asText()));
        }

        @Test
        void iavlProofBindsStorageSlotToEvmStoreRoot() {
            final byte[] key = B64.decode(FIXTURE.path("query").path("key").asText());
            final byte[] value = B64.decode(FIXTURE.path("query").path("value").asText());
            final var proof = iavlProof();
            // sei-chain x/evm key layout: 0x03 || 20-byte address || 32-byte slot
            assertThat(key[0]).isEqualTo((byte) 0x03);
            assertThat(key).hasSize(1 + 20 + 32);
            final byte[] storeRoot = SeiIcs23.existenceRoot(proof, SeiIcs23.IAVL_SPEC);
            SeiIcs23.verifyMembership(proof, SeiIcs23.IAVL_SPEC, storeRoot, key, value);
        }

        @Test
        void simpleProofChainsEvmStoreRootToAppHash() {
            final byte[] storeRoot = SeiIcs23.existenceRoot(iavlProof(), SeiIcs23.IAVL_SPEC);
            final var simple = simpleProof();
            assertThat(simple.key()).isEqualTo("evm".getBytes(StandardCharsets.UTF_8));
            assertThat(simple.value()).isEqualTo(storeRoot);
            final byte[] appHash = SeiIcs23.existenceRoot(simple, SeiIcs23.TENDERMINT_SPEC);
            // the app-hash lag: state at H is committed by the header at H+1
            assertThat(appHash).isEqualTo(HEX.parseHex(HEADER.path("app_hash").asText()));
            SeiIcs23.verifyMembership(
                    simple, SeiIcs23.TENDERMINT_SPEC, appHash, "evm".getBytes(StandardCharsets.UTF_8), storeRoot);
        }

        @Test
        void provenValueIsTheStoredContractSlotValue() {
            final byte[] value = B64.decode(FIXTURE.path("query").path("value").asText());
            // the test contract stored uint256(12345) in slot 0
            assertThat(HEX.formatHex(value))
                    .isEqualTo("0000000000000000000000000000000000000000000000000000000000003039");
        }

        @Test
        void tamperedValueFailsMembership() {
            final byte[] key = B64.decode(FIXTURE.path("query").path("key").asText());
            final byte[] value = B64.decode(FIXTURE.path("query").path("value").asText());
            final var proof = iavlProof();
            final byte[] storeRoot = SeiIcs23.existenceRoot(proof, SeiIcs23.IAVL_SPEC);
            final byte[] tampered = value.clone();
            tampered[tampered.length - 1] ^= 0x01;
            assertThatThrownBy(() -> SeiIcs23.verifyMembership(proof, SeiIcs23.IAVL_SPEC, storeRoot, key, tampered))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("different value");
        }

        @Test
        void tamperedRootFailsMembership() {
            final byte[] key = B64.decode(FIXTURE.path("query").path("key").asText());
            final byte[] value = B64.decode(FIXTURE.path("query").path("value").asText());
            final var proof = iavlProof();
            final byte[] badRoot =
                    SeiIcs23.existenceRoot(proof, SeiIcs23.IAVL_SPEC).clone();
            badRoot[0] ^= 0x01;
            assertThatThrownBy(() -> SeiIcs23.verifyMembership(proof, SeiIcs23.IAVL_SPEC, badRoot, key, value))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("root does not match");
        }

        @Test
        void iavlProofRejectedUnderTendermintSpec() {
            // an IAVL leaf/inner shape must not satisfy the multistore spec (op confusion guard)
            assertThatThrownBy(() -> SeiIcs23.existenceRoot(iavlProof(), SeiIcs23.TENDERMINT_SPEC))
                    .isInstanceOf(ProofException.class);
        }

        @Test
        void garbageBytesRejected() {
            assertThatThrownBy(() -> SeiIcs23.parseCommitmentProof(new byte[] {0x12, 0x03, 0x01, 0x02, 0x03}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("only existence proofs");
        }
    }
}
