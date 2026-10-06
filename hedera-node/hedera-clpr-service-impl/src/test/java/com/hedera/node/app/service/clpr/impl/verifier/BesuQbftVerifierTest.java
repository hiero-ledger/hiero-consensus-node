// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.node.app.service.clpr.impl.verifier.PbjTestUtils.appendUnknownField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.BlockHeader;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprQbftLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.StorageProofEntry;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.sun.jna.ptr.IntByReference;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BesuQbftVerifierTest {

    private static final BesuQbftVerifier SUBJECT =
            new BesuQbftVerifier(new BesuQbftVerifier.Config(null, null, 30_000L));

    // Deterministic secp256k1 test key pairs
    private static final byte[] VALIDATOR_PRIV = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };
    private static final byte[] VALIDATOR_ADDR = EthSigsUtils.recoverAddressFromPrivateKey(VALIDATOR_PRIV);

    private static final byte[] OTHER_PRIV = {
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30,
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40
    };
    private static final byte[] OTHER_ADDR = EthSigsUtils.recoverAddressFromPrivateKey(OTHER_PRIV);

    // Stable 20-byte CLPR service contract address
    private static final byte[] SERVICE_ADDR = {
        (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE,
        (byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44, (byte) 0x55,
        (byte) 0x66, (byte) 0x77, (byte) 0x88, (byte) 0x99, (byte) 0x00,
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05
    };

    // keccak256(RLP(empty)) — the canonical empty MPT root
    private static final byte[] EMPTY_TRIE_ROOT =
            HexFormat.of().parseHex("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421");

    // 32-byte big-endian encoding of EVM storage slot 25: ClprService._config.serviceAddress
    // (_config base 23 + field offset 2, per SC-189's storage layout)
    private static final byte[] SERVICE_ADDR_STORAGE_SLOT = new byte[32];

    static {
        SERVICE_ADDR_STORAGE_SLOT[31] = (byte) 25;
    }

    // Convenience: encode a validator set from varargs addresses
    private static byte[] validatorSet(byte[]... addrs) {
        return BesuQbftVerifier.encodeValidatorSet(Arrays.asList(addrs));
    }

    @Nested
    class ParseStructural {
        @Test
        void nullBytes_throwsNpe() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void emptyBytes_throwsProofException() {
            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(new byte[0])).isInstanceOf(ProofException.class);
        }

        @Test
        void garbageBytes_throwsProofException() {
            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(new byte[] {0x08}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not a valid ClprQbftLedgerConfigurationPayload");
        }

        @Test
        void missingGenesisBlockHeader_throwsProofException() {
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .currentBlockHeader(header(buildGenesisHeader(VALIDATOR_ADDR)))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("genesisBlockHeader is missing or empty");
        }

        @Test
        void missingCurrentBlockHeader_throwsProofException() {
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(buildGenesisHeader(VALIDATOR_ADDR)))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("currentBlockHeader is missing or empty");
        }

        @Test
        void missingLedgerConfiguration_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(genesisHeader))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("ledgerConfiguration is missing");
        }

        @Test
        void genesisHeaderTooFewRlpFields_throwsProofException() {
            byte[] tooShort = buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of()), 14);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(tooShort))
                    .currentBlockHeader(header(tooShort))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("genesisBlockHeader is not an RLP list of 15..23 fields");
        }

        @Test
        void genesisHeaderTooManyRlpFields_throwsProofException() {
            byte[] tooLong = buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of()), 24);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(tooLong))
                    .currentBlockHeader(header(tooLong))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("genesisBlockHeader is not an RLP list of 15..23 fields");
        }

        @Test
        void currentHeaderTooFewRlpFields_throwsProofException() {
            byte[] validGenesis = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] badCurrent = buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of()), 14);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(validGenesis))
                    .currentBlockHeader(header(badCurrent))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("currentBlockHeader is not an RLP list of 15..23 fields");
        }

        @Test
        void genesisExtraDataNotValidRlp_throwsProofException() {
            byte[] headerRlp = buildRlpHeader(new byte[32], new byte[] {0x01, 0x02, 0x03}, 15);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(headerRlp))
                    .currentBlockHeader(header(headerRlp))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("extraData is not valid RLP");
        }

        @Test
        void genesisExtraDataNot5FieldQbftList_throwsProofException() {
            byte[] badExtra = Rlp.encodeList(List.of(
                    Rlp.encodeBytes(new byte[32]),
                    Rlp.encodeList(List.of()),
                    Rlp.encodeBytes(new byte[0]),
                    Rlp.encodeUint(0L)));
            byte[] headerRlp = buildRlpHeader(new byte[32], badExtra, 15);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(headerRlp))
                    .currentBlockHeader(header(headerRlp))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("extraData is not a QBFT extra-data RLP list of 5 fields");
        }

        @Test
        void genesisZeroValidators_throwsProofException() {
            byte[] headerRlp = buildRlpHeader(new byte[32], buildQbftExtra(List.of(), List.of()), 15);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(headerRlp))
                    .currentBlockHeader(header(headerRlp))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            // Multi-node verifier requires at least one validator; zero is still rejected.
            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validators list is empty");
        }

        @Test
        void genesisTwoValidators_accepted() {
            // Multi-node verifier accepts any non-empty validator list; two validators is valid.
            byte[] headerRlp =
                    buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR, OTHER_ADDR), List.of()), 15);
            Rlp.Item item = Rlp.decodeOne(headerRlp);
            // extractValidators must not throw and must return both addresses
            List<byte[]> validators = BesuQbftVerifier.extractValidators(item);
            assertThat(validators).hasSize(2);
        }

        @Test
        void genesisValidatorAddressNot20Bytes_throwsIllegalArgument() {
            byte[] shortAddr = new byte[19];
            byte[] headerRlp = buildRlpHeader(new byte[32], buildQbftExtra(List.of(shortAddr), List.of()), 15);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(headerRlp))
                    .currentBlockHeader(header(headerRlp))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be 20 bytes");
        }

        @Test
        void unknownField_throwsProofException() {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            // Serialize a valid ClprQbftLedgerConfigurationPayload, append a record for
            // proto field #255 (which the schema doesn't define), and expect the strict
            // parse in verifyConfigPayload to reject it.
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] validPayload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(genesisHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());
            byte[] payloadWithUnknown = appendUnknownField(validPayload);

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payloadWithUnknown))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not a valid ClprQbftLedgerConfigurationPayload");
        }
    }

    @Nested
    class QbftSeal {
        @Test
        void noCommittedSeals_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of()), 15);
            byte[] payload = minimalPayload(genesisHeader, currentHeader);

            // For a 1-validator set quorum is 1; zero seals is still below quorum.
            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected at least 1 committed seal");
        }

        @Test
        void duplicateSealFromSingleValidator_throwsProofException() {
            // Single-validator set: signing twice produces a duplicate signer.
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] extraNoSeals = buildQbftExtra(List.of(VALIDATOR_ADDR), List.of());
            byte[] headerForHash = buildRlpHeader(new byte[32], extraNoSeals, 15);
            Rlp.Item hi = Rlp.decodeOne(headerForHash);
            Rlp.Item ei = Rlp.decodeOne(hi.children().get(12).asBytes());
            byte[] seal = sign(BesuQbftVerifier.buildCommitSealHash(hi, ei), VALIDATOR_PRIV);
            byte[] currentHeader =
                    buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of(seal, seal)), 15);
            byte[] payload = minimalPayload(genesisHeader, currentHeader);

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("duplicate committed seal from");
        }

        @Test
        void committedSealNot65Bytes_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] shortSeal = new byte[64];
            byte[] currentHeader =
                    buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of(shortSeal)), 15);
            byte[] payload = minimalPayload(genesisHeader, currentHeader);

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("must be 65 bytes");
        }

        @Test
        void sealSignedByKeyNotInValidatorSet_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            // Signed with OTHER_PRIV, but genesis validator set is only {VALIDATOR_ADDR}
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, new byte[32], OTHER_PRIV);
            byte[] payload = minimalPayload(genesisHeader, currentHeader);

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }
    }

    @Nested
    class ServiceAddress {
        @Test
        void serviceAddressEmpty_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, new byte[32], VALIDATOR_PRIV);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .serviceAddress(Bytes.EMPTY)
                            .build())
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address must be 20 bytes");
        }

        @Test
        void serviceAddressWrongLength_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, new byte[32], VALIDATOR_PRIV);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .serviceAddress(Bytes.wrap(new byte[21]))
                            .build())
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address must be 20 bytes");
        }
    }

    @Nested
    class AccountProof {
        @Test
        void accountAbsentFromStateTrie_throwsProofException() {
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, EMPTY_TRIE_ROOT, VALIDATOR_PRIV);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("CLPR service account absent from state trie");
        }

        @Test
        void proofNodesMissingForHashReference_throwsProofException() {
            byte[] nonEmptyRoot = keccak256(new byte[] {0x01});
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, nonEmptyRoot, VALIDATOR_PRIV);
            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("missing proof node for hash reference");
        }

        @Test
        void codeHashMismatch_throwsProofException() {
            byte[] actualCodeHash = new byte[32];
            byte[] expectedCodeHash = new byte[32];
            expectedCodeHash[0] = (byte) 0xFF;

            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, actualCodeHash);
            byte[] stateRoot = accountProof[0];

            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);

            var strictVerifier = new BesuQbftVerifier(new BesuQbftVerifier.Config(null, expectedCodeHash, 30_000L));

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(new byte[32]))
                            .value(Bytes.EMPTY)
                            .build()))
                    .build());

            assertThatThrownBy(() -> strictVerifier.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("codeHash does not match");
        }
    }

    @Nested
    class StorageProofTests {
        @Test
        void zeroStorageProofEntries_throwsProofException() {
            byte[] payload = payloadWithStorageEntries(List.of());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("clprServiceStorageProofs must contain at least 1 entry");
        }

        @Test
        void storageProofsWithNoServiceAddressSlotKey_throwsProofException() {
            // Multiple entries are allowed, but at least one must have key == SERVICE_ADDR_STORAGE_SLOT.
            var entry = StorageProofEntry.newBuilder()
                    .key(Bytes.wrap(new byte[32])) // slot 0 — not the serviceAddress slot
                    .value(Bytes.EMPTY)
                    .build();
            byte[] payload = payloadWithStorageEntries(List.of(entry, entry));

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining(
                            " clprServiceStorageProofs does not contain an entry for the serviceAddress slot");
        }

        @Test
        void provenSlotIsEmpty_throwsProofException() {
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.EMPTY)
                            .build()))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address storage slot does not match proven value");
        }

        @Test
        void provenSlotHoldsDifferentAddress_throwsProofException() {
            byte[] wrongAddr = new byte[20];
            byte[] wrongSlotValue = serviceAddrSlotValue(wrongAddr);

            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, wrongSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.wrap(wrongSlotValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address storage slot does not match proven value");
        }

        @Test
        void provenSlotCorrectAddressBytesWrongLengthByte_throwsProofException() {
            byte[] wrongValue = new byte[32];
            System.arraycopy(SERVICE_ADDR, 0, wrongValue, 0, 20);
            // wrongValue[31] = 0x00; expected is 0x28

            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, wrongValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.wrap(wrongValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address storage slot does not match proven value");
        }

        @Test
        void serviceAddressProofAtSecondPosition_succeedsVerification() {
            // Even though the proof didn't arrive as the first storage slot, it's still valid
            byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, correctSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            StorageProofEntry decoy = StorageProofEntry.newBuilder()
                    .key(Bytes.wrap(new byte[32])) // slot 0 — NOT the serviceAddress slot
                    .build();
            StorageProofEntry real = StorageProofEntry.newBuilder()
                    .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                    .value(Bytes.wrap(correctSlotValue))
                    .proof(List.of(Bytes.wrap(storageMpt[1])))
                    .build();

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(decoy, real)) // real entry at index 1
                    .build());

            // Verifier locates the real entry by slot key
            assertThat(SUBJECT.verifyConfigPayload(payload)).isNotNull();
        }

        @Test
        void storageProofWithWrongSlotKey_throwsProofException() {
            // A proof entry whose key doesn't match the expected slot must be rejected
            byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
            byte[] wrongSlot = new byte[32]; // slot 0, not slot 23
            byte[][] storageMpt = buildStorageMptProof(wrongSlot, correctSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
            byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeader))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(wrongSlot))
                            .value(Bytes.wrap(correctSlotValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not contain an entry for the serviceAddress slot");
        }
    }

    @Nested
    class SuccessPath {
        @Test
        void trustAnchorAndIdDerivedFromGenesisHeader() {
            byte[] codeHash = new byte[32];
            byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, correctSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], codeHash);

            byte[] genesisHeaderBytes = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeaderBytes))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.wrap(correctSlotValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            var result = SUBJECT.verifyConfigPayload(payload);

            // Trust anchor = RLP([RLP([validatorAddr]), serviceAddr, codeHash])
            // The validator set is encoded as a sorted RLP list.
            byte[] expectedAnchor = qbftTrustAnchor(List.of(VALIDATOR_ADDR), SERVICE_ADDR, codeHash);
            assertThat(result.ledgerConfiguration().initialTrustAnchor().toByteArray())
                    .isEqualTo(expectedAnchor);
            assertThat(result.ledgerConfiguration().initialTrustAnchorId().toByteArray())
                    .isEqualTo(BigInteger.ZERO.toByteArray());
        }

        @Test
        void twoGenesisValidators_trustAnchorEncodesFullSet() {
            byte[] codeHash = new byte[32];
            byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, correctSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], codeHash);

            // Genesis header declares two validators
            byte[] genesisHeaderBytes =
                    buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR, OTHER_ADDR), List.of()), 15);
            // Current header needs quorum=2 seals from both validators
            byte[] currentHeader = buildSignedHeaderMultiSeal(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), accountProof[0], List.of(VALIDATOR_PRIV, OTHER_PRIV));

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeaderBytes))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.wrap(correctSlotValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            var result = SUBJECT.verifyConfigPayload(payload);

            // The trust anchor must encode both validators (sorted).
            byte[] expectedAnchor = qbftTrustAnchor(List.of(VALIDATOR_ADDR, OTHER_ADDR), SERVICE_ADDR, codeHash);
            assertThat(result.ledgerConfiguration().initialTrustAnchor().toByteArray())
                    .isEqualTo(expectedAnchor);
        }

        @Test
        void payloadTrustAnchorFieldsAreIgnored() {
            byte[] codeHash = new byte[32];
            byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, correctSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], codeHash);

            byte[] genesisHeaderBytes = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] arbitraryAnchor = new byte[20];
            arbitraryAnchor[0] = (byte) 0xDE;
            byte[] arbitraryAnchorId = new byte[32];
            arbitraryAnchorId[0] = (byte) 0xBE;

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeaderBytes))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                            .initialTrustAnchor(Bytes.wrap(arbitraryAnchor))
                            .initialTrustAnchorId(Bytes.wrap(arbitraryAnchorId))
                            .build())
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.wrap(correctSlotValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            var result = SUBJECT.verifyConfigPayload(payload);

            byte[] expectedAnchor = qbftTrustAnchor(List.of(VALIDATOR_ADDR), SERVICE_ADDR, codeHash);
            assertThat(result.ledgerConfiguration().initialTrustAnchor().toByteArray())
                    .isEqualTo(expectedAnchor);
            assertThat(result.ledgerConfiguration().initialTrustAnchorId().toByteArray())
                    .isEqualTo(java.math.BigInteger.ZERO.toByteArray());
        }

        @Test
        void trustAnchorFromConfigPayloadIsDecodableAsThreeByteStrings() {
            // This test exercises the full verifyConfigPayload -> trust-anchor-consumer path.
            // BesuQBFTVerifyBundleCall.decodeRlpListOfByteStrings rejects nested RLP lists,
            // so item 0 (the validator set) must be a byte string, not a list.
            byte[] codeHash = new byte[32];
            byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
            byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, correctSlotValue);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], codeHash);

            byte[] genesisHeaderBytes = buildGenesisHeader(VALIDATOR_ADDR);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            byte[] payload = serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                    .genesisBlockHeader(header(genesisHeaderBytes))
                    .currentBlockHeader(header(currentHeader))
                    .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                    .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                    .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                            .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                            .value(Bytes.wrap(correctSlotValue))
                            .proof(List.of(Bytes.wrap(storageMpt[1])))
                            .build()))
                    .build());

            byte[] trustAnchor = SUBJECT.verifyConfigPayload(payload)
                    .ledgerConfiguration()
                    .initialTrustAnchor()
                    .toByteArray();

            Rlp.Item outerList = Rlp.decodeOne(trustAnchor);
            assertThat(outerList.isList()).isTrue();
            assertThat(outerList.children()).hasSize(3);

            // Item 0: validator set — must be a byte string (not a nested list)
            Rlp.Item item0 = outerList.children().getFirst();
            assertThat(item0.isList())
                    .as("item 0 must be a byte string, not a nested RLP list")
                    .isFalse();
            assertThat(item0.asBytes()).isEqualTo(BesuQbftVerifier.encodeValidatorSet(List.of(VALIDATOR_ADDR)));

            // Item 1: 20-byte contract address
            Rlp.Item item1 = outerList.children().get(1);
            assertThat(item1.isList()).isFalse();
            assertThat(item1.asBytes()).isEqualTo(SERVICE_ADDR);

            // Item 2: 32-byte code hash
            Rlp.Item item2 = outerList.children().get(2);
            assertThat(item2.isList()).isFalse();
            assertThat(item2.asBytes()).hasSize(32);
        }
    }

    @Nested
    class VerifyBundleTests {

        private static final BesuQbftVerifier BUNDLE_SUBJECT =
                new BesuQbftVerifier(new BesuQbftVerifier.Config(SERVICE_ADDR, null, 30_000L));

        @Test
        void storageProofWith3ParamEntriesRejected() {
            byte[] encodedStorageEntry = Rlp.encodeList(
                    List.of(Rlp.encodeBytes(new byte[32]), Rlp.encodeBytes(new byte[32]), Rlp.encodeList(List.of())));
            byte[] bundlePayload = buildBundlePayload(encodedStorageEntry);
            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("is not a [key, proof[]] RLP list");
        }

        @Test
        void storageProofWithKeyAndProofParamEntriesAccepted() {
            byte[] encodedStorageEntry =
                    Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[32]), Rlp.encodeList(List.of())));
            byte[] bundlePayload = buildBundlePayload(encodedStorageEntry);
            var result = BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR));
            assertThat(result).isNotNull();
            assertThat(result.blockHash32()).hasSize(32);
        }

        @Test
        void fourItemBundleRejected() {
            byte[] bundlePayload = buildLegacyFourItemBundlePayload(validStorageEntry());
            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected top-level RLP list of 5 or 7 items, got 4");
        }

        @Test
        void emptyBundleWithNoQueueContentOrManifestThrows() {
            // A bundle with an empty queue storage proof (no queue metadata), empty content, and no manifest
            // advance is empty/meaningless — the verifier rejects it. This is the §8.1.4 invariant, mirroring
            // the Ethereum verifier: when both queue metadata and content are absent, the bundle MUST carry a
            // manifest.
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            byte[] signedHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);
            byte[] accountProofRlp = Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1])));
            byte[] emptyBundle = Rlp.encodeList(List.of(
                    signedHeader,
                    Rlp.encodeList(List.of()), // epoch headers (empty)
                    accountProofRlp,
                    Rlp.encodeList(List.of()), // queue storage proof EMPTY → absent queue metadata
                    Rlp.encodeBytes(new byte[0]))); // bundle content EMPTY
            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(emptyBundle, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("no queue metadata, no content, and no endpoint-manifest advance");
        }

        @Test
        void emptyQueueManifestOnlyBundleReturnsManifest() {
            // A manifest-only advance: a 7-item bundle with an EMPTY queue storage proof, EMPTY content, and a
            // valid endpoint-manifest proof. It succeeds — the manifest is surfaced and queue metadata is the
            // absent sentinel (nextMessageId == 0). This is the positive side of the §8.1.4 invariant (mirrors
            // the Ethereum verifier: manifest-only is a normal bundle with empty content, not a special shape).
            byte[] preimage = manifestPreimage(1L, SERVICE_ADDR);
            byte[] commitment = keccak256(preimage);
            byte[][] storageMpt = buildStorageMptProof(manifestCommitmentSlot(), commitment);
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
            byte[] signedHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);
            byte[] manifestStorageProof = Rlp.encodeList(
                    List.of(buildBundleStorageEntry(manifestCommitmentSlot(), new byte[][] {storageMpt[1]})));
            byte[] bundle = Rlp.encodeList(List.of(
                    signedHeader,
                    Rlp.encodeList(List.of()), // epoch headers (empty)
                    Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1]))), // account proof
                    Rlp.encodeList(List.of()), // queue storage proof EMPTY → absent queue metadata
                    Rlp.encodeBytes(new byte[0]), // bundle content EMPTY
                    manifestStorageProof, // manifest commitment-slot storage proof
                    Rlp.encodeBytes(preimage))); // manifest preimage

            var result = BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR));

            assertThat(result.bundleContentBytes()).isEmpty();
            assertThat(result.newEndpointManifestBytes()).isEqualTo(preimage);
            assertThat(result.queueMetadata().nextMessageId()).isZero();
        }

        private static byte[] validStorageEntry() {
            return Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[32]), Rlp.encodeList(List.of())));
        }

        private static byte[] buildLegacyFourItemBundlePayload(byte[] storageEntry) {
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            byte[] signedHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);
            byte[] accountProofRlp = Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1])));
            byte[] storageProofRlp =
                    Rlp.encodeList(List.of(storageEntry, storageEntry, storageEntry, storageEntry, storageEntry));
            return Rlp.encodeList(
                    List.of(signedHeader, accountProofRlp, storageProofRlp, Rlp.encodeBytes(new byte[0])));
        }

        private static byte[] buildBundlePayload(byte[] storageEntry) {
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            byte[] signedHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);
            byte[] accountProofRlp = Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1])));
            byte[] storageProofRlp =
                    Rlp.encodeList(List.of(storageEntry, storageEntry, storageEntry, storageEntry, storageEntry));
            byte[] epochBlockHeadersRlp = Rlp.encodeList(List.of());
            return Rlp.encodeList(List.of(
                    signedHeader,
                    epochBlockHeadersRlp,
                    accountProofRlp,
                    storageProofRlp,
                    Rlp.encodeBytes(new byte[0])));
        }

        @Test
        void reorderedStorageProofs_decodesCorrectQueueMetadata() {
            // FAILS with current code: positional access reads wrong slot values when proof order is reversed.
            // PASSES after fix: sort by slot key (big-endian unsigned) ensures correct field-to-value mapping.

            // Slot values encoding known queue metadata
            byte[] slot0Value = new byte[32]; // status/nextMsgId: nextMessageId=42, status=2
            ByteBuffer.wrap(slot0Value, 3, 8).putLong(42L);
            slot0Value[11] = 0x02;
            byte[] slot1Value = new byte[32]; // receivedMsgId: receivedMessageId=7
            ByteBuffer.wrap(slot1Value, 16, 8).putLong(7L);
            byte[] slot2Value = new byte[32];
            slot2Value[0] = (byte) 0xAA; // sentRunningHash
            byte[] slot3Value = new byte[32];
            slot3Value[0] = (byte) 0xBB; // receivedRunningHash

            // endpointManifestVersion slot (SC-189 Channel offset 16) — proven but dropped by the verifier.
            byte[] slot4Value = new byte[32];
            slot4Value[31] = (byte) 0xCC;

            // Channel-struct slot keys at offsets {1,2,4,5,16} (base 0), matching what the relay proves.
            byte[] slotKey1off = new byte[32];
            slotKey1off[31] = 1; // status | nextMessageId
            byte[] slotKey2off = new byte[32];
            slotKey2off[31] = 2; // acked | received | nextExpectedReply
            byte[] slotKey4off = new byte[32];
            slotKey4off[31] = 4; // sentRunningHash
            byte[] slotKey5off = new byte[32];
            slotKey5off[31] = 5; // receivedRunningHash
            byte[] slotKey16off = new byte[32];
            slotKey16off[31] = 16; // endpointManifestVersion (dropped)

            // Build a real 5-entry storage MPT (leaf i ↔ slotKeys[i]).
            byte[][][] mpt = buildFiveSlotStorageMpt(
                    new byte[][] {slotKey1off, slotKey2off, slotKey4off, slotKey5off, slotKey16off},
                    new byte[][] {slot0Value, slot1Value, slot2Value, slot3Value, slot4Value});
            byte[] storageRoot = mpt[0][0];

            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageRoot, new byte[32]);
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

            // Deliver proofs in REVERSED slot order to exercise the key-sort + cluster reorder.
            byte[] storageProofRlp = Rlp.encodeList(List.of(
                    buildBundleStorageEntry(slotKey16off, mpt[5]),
                    buildBundleStorageEntry(slotKey5off, mpt[4]),
                    buildBundleStorageEntry(slotKey4off, mpt[3]),
                    buildBundleStorageEntry(slotKey2off, mpt[2]),
                    buildBundleStorageEntry(slotKey1off, mpt[1])));

            byte[] bundlePayload = Rlp.encodeList(List.of(
                    currentHeader,
                    Rlp.encodeList(List.of()), // no epoch headers
                    Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1]))), // account proof
                    storageProofRlp,
                    Rlp.encodeBytes(new byte[0]))); // empty bundle content

            var result = BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR));

            // After fix: sort by key → provenSlotValues[0]=slot0, [1]=slot1, [2]=slot2, [3]=slot3
            assertThat(result.queueMetadata().nextMessageId()).isEqualTo(42L);
            assertThat(result.queueMetadata().status()).isEqualTo(2);
            assertThat(result.queueMetadata().receivedMessageId()).isEqualTo(7L);
            assertThat(result.queueMetadata().sentRunningHash()[0]).isEqualTo((byte) 0xAA);
            assertThat(result.queueMetadata().receivedRunningHash()[0]).isEqualTo((byte) 0xBB);
        }
    }

    @Nested
    class VerifyBundleEndpointManifest {

        private static final BesuQbftVerifier BUNDLE_SUBJECT =
                new BesuQbftVerifier(new BesuQbftVerifier.Config(SERVICE_ADDR, null, 30_000L));

        @Test
        void sevenItemBundle_validManifestProof_returnsVerifiedPreimage() {
            byte[] preimage = manifestPreimage(1L, SERVICE_ADDR);
            byte[] bundle = buildManifestBundle(preimage, preimage, manifestCommitmentSlot(), 1);

            var result = BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR));

            // Step 1b: the proof-verified manifest preimage is threaded back verbatim.
            assertThat(result.newEndpointManifestBytes()).isEqualTo(preimage);
        }

        @Test
        void fiveItemBundle_carriesNoManifest() {
            // A plain 5-item bundle (no manifest extension) → empty newEndpointManifestBytes.
            byte[] bundle =
                    buildManifestBundle(manifestPreimage(1L, SERVICE_ADDR), new byte[0], manifestCommitmentSlot(), 0);
            var result = BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR));
            assertThat(result.newEndpointManifestBytes()).isEmpty();
        }

        @Test
        void sevenItemBundle_tamperedPreimage_throwsProofException() {
            byte[] committed = manifestPreimage(1L, SERVICE_ADDR); // commitment slot binds this
            byte[] tampered = manifestPreimage(2L, SERVICE_ADDR); // different keccak → mismatch
            byte[] bundle = buildManifestBundle(committed, tampered, manifestCommitmentSlot(), 1);

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("endpoint-manifest preimage does not match the proven commitment");
        }

        @Test
        void sevenItemBundle_wrongCommitmentSlot_throwsProofException() {
            byte[] preimage = manifestPreimage(1L, SERVICE_ADDR);
            byte[] wrongSlot = new byte[32];
            wrongSlot[31] = 17; // not the manifest commitment slot (18)
            byte[] bundle = buildManifestBundle(preimage, preimage, wrongSlot, 1);

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("is not for the commitment slot (18)");
        }

        @Test
        void sevenItemBundle_multipleManifestEntries_throwsProofException() {
            byte[] preimage = manifestPreimage(1L, SERVICE_ADDR);
            byte[] bundle = buildManifestBundle(preimage, preimage, manifestCommitmentSlot(), 2);

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("must contain exactly 1 entry");
        }

        @Test
        void sevenItemBundle_manifestServiceAddressMismatch_throwsProofException() {
            byte[] otherAddr = SERVICE_ADDR.clone();
            otherAddr[0] = (byte) (otherAddr[0] ^ 0xFF); // manifest bound to a different service address
            byte[] preimage = manifestPreimage(1L, otherAddr);
            byte[] bundle = buildManifestBundle(preimage, preimage, manifestCommitmentSlot(), 1);

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address does not match the trusted peer service address");
        }
    }

    @Nested
    class VerifyConfigEndpointManifest {

        @Test
        void emptyManifestProof_yieldsEmptyManifestBytes() {
            byte[] payload = validConfigPayload();
            // Both the 1-arg overload and an explicit empty proof leave the manifest bytes empty.
            assertThat(SUBJECT.verifyConfigPayload(payload).endpointManifestBytes())
                    .isEmpty();
            assertThat(SUBJECT.verifyConfigPayload(payload, new byte[0]).endpointManifestBytes())
                    .isEmpty();
        }

        @Test
        void validManifestProof_returnsVerifiedPreimage() {
            byte[] payload = validConfigPayload();
            byte[] preimage = manifestPreimage(1L, SERVICE_ADDR);
            byte[] manifestProof = buildConfigManifestProof(preimage, preimage, manifestCommitmentSlot());

            var verified = SUBJECT.verifyConfigPayload(payload, manifestProof);

            assertThat(verified.endpointManifestBytes()).isEqualTo(preimage);
        }

        @Test
        void tamperedPreimage_throwsProofException() {
            byte[] payload = validConfigPayload();
            byte[] committed = manifestPreimage(1L, SERVICE_ADDR);
            byte[] tampered = manifestPreimage(2L, SERVICE_ADDR);
            byte[] manifestProof = buildConfigManifestProof(committed, tampered, manifestCommitmentSlot());

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload, manifestProof))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("endpoint-manifest preimage does not match the proven commitment");
        }

        @Test
        void wrongFieldCount_throwsProofException() {
            byte[] payload = validConfigPayload();
            byte[] threeFieldProof = Rlp.encodeList(
                    List.of(Rlp.encodeBytes(new byte[0]), Rlp.encodeBytes(new byte[0]), Rlp.encodeBytes(new byte[0])));

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload, threeFieldProof))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("endpoint-manifest config proof must be an RLP list of 4 items");
        }

        @Test
        void headerSealNotFromConfigValidator_throwsProofException() {
            byte[] payload = validConfigPayload();
            byte[] preimage = manifestPreimage(1L, SERVICE_ADDR);
            // Sign the manifest proof header with a key NOT in the config validator set.
            byte[] manifestProof = buildConfigManifestProof(preimage, preimage, manifestCommitmentSlot(), OTHER_PRIV);

            assertThatThrownBy(() -> SUBJECT.verifyConfigPayload(payload, manifestProof))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }
    }

    @Nested
    class VerifyBundleExactConnIdMatching {

        private static final BesuQbftVerifier BUNDLE_SUBJECT =
                new BesuQbftVerifier(new BesuQbftVerifier.Config(SERVICE_ADDR, null, 30_000L));

        @Test
        void exactConnId_ackOnly_decodesQueueMetadataFromCanonicalSlots() {
            byte[] connId = findConnId(false);
            byte[] base = connSlotBase(connId);

            byte[] vStatus = new byte[32]; // offset 1: nextMessageId=42, status=2
            ByteBuffer.wrap(vStatus, 3, 8).putLong(42L);
            vStatus[11] = 0x02;
            byte[] vReceived = new byte[32]; // offset 2: receivedMessageId=7
            ByteBuffer.wrap(vReceived, 16, 8).putLong(7L);
            byte[] vSent = new byte[32];
            vSent[0] = (byte) 0xAA; // offset 4
            byte[] vRecv = new byte[32];
            vRecv[0] = (byte) 0xBB; // offset 5
            byte[] vManifestVer = new byte[32];
            vManifestVer[31] = 0x09; // offset 16 — proven but dropped

            byte[][] keys = {
                plusOffset(base, 1), plusOffset(base, 2), plusOffset(base, 4), plusOffset(base, 5), plusOffset(base, 16)
            };
            byte[][] vals = {vStatus, vReceived, vSent, vRecv, vManifestVer};
            byte[] bundle = buildBundleWithSlots(keys, vals);

            var result = BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR), connId);

            assertThat(result.queueMetadata().nextMessageId()).isEqualTo(42L);
            assertThat(result.queueMetadata().status()).isEqualTo(2);
            assertThat(result.queueMetadata().receivedMessageId()).isEqualTo(7L);
            assertThat(result.queueMetadata().sentRunningHash()[0]).isEqualTo((byte) 0xAA);
            assertThat(result.queueMetadata().receivedRunningHash()[0]).isEqualTo((byte) 0xBB);
        }

        @Test
        void exactConnId_withMessageSlot_identifiesRunningHashOutlier() {
            byte[] connId = findConnId(true);
            byte[] base = connSlotBase(connId);
            byte[] vRunningHash = new byte[32];
            vRunningHash[0] = (byte) 0xCC;
            byte[][] keys = {
                plusOffset(base, 1), plusOffset(base, 2), plusOffset(base, 4),
                plusOffset(base, 5), plusOffset(base, 16), keccak256(connId)
            };
            byte[][] vals = {new byte[32], new byte[32], new byte[32], new byte[32], new byte[32], vRunningHash};
            byte[] bundle = buildBundleWithSlots(keys, vals);

            var result = BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR), connId);

            // The lone non-Channel slot is bound as the last message's running hash, not mis-mapped.
            assertThat(result.queueMetadata().lastMessageRunningHash()[0]).isEqualTo((byte) 0xCC);
        }

        @Test
        void exactConnId_wrongChannelId_failsSafeInsteadOfMisDecoding() {
            // A bundle that proves connId A's slots, verified against connId B, must be rejected —
            // none of the proven keys match B's canonical Channel keys, so the verifier throws
            // rather than silently binding the wrong slots.
            byte[] connIdA = findConnId(false);
            byte[] base = connSlotBase(connIdA);
            byte[][] keys = {
                plusOffset(base, 1), plusOffset(base, 2), plusOffset(base, 4), plusOffset(base, 5), plusOffset(base, 16)
            };
            byte[][] vals = {new byte[32], new byte[32], new byte[32], new byte[32], new byte[32]};
            byte[] bundle = buildBundleWithSlots(keys, vals);

            byte[] connIdB = connIdA.clone();
            connIdB[0] ^= (byte) 0xFF;

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR), connIdB))
                    .isInstanceOf(ProofException.class);
        }

        @Test
        void emptyConnId_fallsBackToHeuristic() {
            // The 2-arg overload (empty connId) must still decode via the span heuristic, unchanged.
            byte[] connId = findConnId(false);
            byte[] base = connSlotBase(connId);
            byte[] vStatus = new byte[32];
            ByteBuffer.wrap(vStatus, 3, 8).putLong(5L);
            byte[][] keys = {
                plusOffset(base, 1), plusOffset(base, 2), plusOffset(base, 4), plusOffset(base, 5), plusOffset(base, 16)
            };
            byte[][] vals = {vStatus, new byte[32], new byte[32], new byte[32], new byte[32]};
            byte[] bundle = buildBundleWithSlots(keys, vals);

            // Heuristic path (no connId): the 5 canonical slots cluster within span 15, so it still works.
            var result = BUNDLE_SUBJECT.verifyBundle(bundle, validatorSet(VALIDATOR_ADDR));
            assertThat(result.queueMetadata().nextMessageId()).isEqualTo(5L);
        }
    }

    @Nested
    class VerifyQbftSealAgainstValidatorSet {

        @Test
        void emptyValidatorSet_throwsProofException() {
            Rlp.Item headerItem = Rlp.decodeOne(buildGenesisHeader(VALIDATOR_ADDR));
            assertThatThrownBy(() -> BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of()))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validator set is empty");
        }

        @Test
        void extraDataNotValidRlp_throwsProofException() {
            byte[] headerRlp = buildRlpHeader(new byte[32], new byte[] {0x01, 0x02, 0x03}, 15);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            assertThatThrownBy(() ->
                            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("blockHeader.extraData is not valid RLP");
        }

        @Test
        void extraDataWrongNumberOfQbftFields_throwsProofException() {
            byte[] twoFieldExtra = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[32]), Rlp.encodeList(List.of())));
            byte[] headerRlp = buildRlpHeader(new byte[32], twoFieldExtra, 15);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            assertThatThrownBy(() ->
                            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("blockHeader.extraData is not a QBFT extra-data RLP list of 5 fields");
        }

        @Test
        void zeroSeals_throwsProofException() {
            byte[] headerRlp = buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of()), 15);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            assertThatThrownBy(() ->
                            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected at least 1 committed seal");
        }

        @Test
        void sealNot65Bytes_throwsProofException() {
            byte[] shortSeal = new byte[32];
            byte[] headerRlp =
                    buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of(shortSeal)), 15);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            assertThatThrownBy(() ->
                            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("must be 65 bytes");
        }

        @Test
        void signerNotInValidatorSet_throwsProofException() {
            byte[] headerRlp = buildSignedHeader(VALIDATOR_ADDR, new byte[32], OTHER_PRIV);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            assertThatThrownBy(() ->
                            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        @Test
        void duplicateSigner_throwsProofException() {
            // Build a header with two seals from the same key.
            byte[] extraNoSeals = buildQbftExtra(List.of(VALIDATOR_ADDR), List.of());
            byte[] headerForHash = buildRlpHeader(new byte[32], extraNoSeals, 15);
            Rlp.Item hi = Rlp.decodeOne(headerForHash);
            Rlp.Item ei = Rlp.decodeOne(hi.children().get(12).asBytes());
            byte[] seal = sign(BesuQbftVerifier.buildCommitSealHash(hi, ei), VALIDATOR_PRIV);
            byte[] headerRlp =
                    buildRlpHeader(new byte[32], buildQbftExtra(List.of(VALIDATOR_ADDR), List.of(seal, seal)), 15);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            // Validator set has two members so quorum=2 is satisfied, but duplicate signer is rejected first.
            assertThatThrownBy(() -> BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(
                            headerItem, List.of(VALIDATOR_ADDR, OTHER_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("duplicate committed seal from");
        }

        @Test
        void singleValidator_validSeal_noException() {
            byte[] headerRlp = buildSignedHeader(VALIDATOR_ADDR, new byte[32], VALIDATOR_PRIV);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR));
        }

        @Test
        void twoValidators_bothSign_quorumMet_noException() {
            // 2-validator set: quorum = 2, both must sign.
            byte[] headerRlp = buildSignedHeaderMultiSeal(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), new byte[32], List.of(VALIDATOR_PRIV, OTHER_PRIV));
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(VALIDATOR_ADDR, OTHER_ADDR));
        }

        @Test
        void twoValidators_onlyOneSigns_belowQuorum_throwsProofException() {
            // 2-validator set: quorum = 2, but only 1 seal provided.
            byte[] headerRlp = buildSignedHeader(VALIDATOR_ADDR, new byte[32], VALIDATOR_PRIV);
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            assertThatThrownBy(() -> BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(
                            headerItem, List.of(VALIDATOR_ADDR, OTHER_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected at least 2 committed seals");
        }

        @Test
        void validatorSetOrderDoesNotAffectVerification() {
            // Both orderings of the same set must accept the same seals.
            byte[] headerRlp = buildSignedHeaderMultiSeal(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), new byte[32], List.of(VALIDATOR_PRIV, OTHER_PRIV));
            Rlp.Item headerItem = Rlp.decodeOne(headerRlp);
            // Pass validators in the opposite order from the header declaration.
            BesuQbftVerifier.verifyQbftSealAgainstValidatorSet(headerItem, List.of(OTHER_ADDR, VALIDATOR_ADDR));
        }
    }

    @Nested
    class EncodeDecodeValidatorSet {

        @Test
        void encodeDecodeRoundTrip_singleValidator() {
            List<byte[]> original = List.of(VALIDATOR_ADDR);
            byte[] encoded = BesuQbftVerifier.encodeValidatorSet(original);
            List<byte[]> decoded = BesuQbftVerifier.decodeValidatorSet(encoded, "test");
            assertThat(decoded).hasSize(1);
            assertThat(decoded.getFirst()).isEqualTo(VALIDATOR_ADDR);
        }

        @Test
        void encodeDecodeRoundTrip_twoValidators() {
            List<byte[]> original = List.of(VALIDATOR_ADDR, OTHER_ADDR);
            byte[] encoded = BesuQbftVerifier.encodeValidatorSet(original);
            List<byte[]> decoded = BesuQbftVerifier.decodeValidatorSet(encoded, "test");
            assertThat(decoded).hasSize(2);
            // Decoded list is sorted; check both addresses are present.
            assertThat(decoded).anySatisfy(a -> assertThat(a).isEqualTo(VALIDATOR_ADDR));
            assertThat(decoded).anySatisfy(a -> assertThat(a).isEqualTo(OTHER_ADDR));
        }

        @Test
        void encodeIsOrderIndependent() {
            // The same two addresses in either order must produce identical bytes.
            byte[] enc1 = BesuQbftVerifier.encodeValidatorSet(List.of(VALIDATOR_ADDR, OTHER_ADDR));
            byte[] enc2 = BesuQbftVerifier.encodeValidatorSet(List.of(OTHER_ADDR, VALIDATOR_ADDR));
            assertThat(enc1).isEqualTo(enc2);
        }

        @Test
        void decodeEmpty_throwsProofException() {
            byte[] emptyList = Rlp.encodeList(List.of());
            assertThatThrownBy(() -> BesuQbftVerifier.decodeValidatorSet(emptyList, "test"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("no validators");
        }

        @Test
        void decodeAddressWrongLength_throwsIllegalArgument() {
            byte[] badAddr = new byte[19];
            byte[] encoded = Rlp.encodeList(List.of(Rlp.encodeBytes(badAddr)));
            assertThatThrownBy(() -> BesuQbftVerifier.decodeValidatorSet(encoded, "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be 20 bytes");
        }
    }

    @Nested
    class BuildCommitSealHash {
        @Test
        void sealsStrippedFromHashInput_sealedAndUnsealedProduceSameHash() {
            byte[] stateRoot = keccak256(new byte[] {0x42});

            byte[] headerWithSeal = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);
            byte[] headerWithNoSeals =
                    buildRlpHeader(stateRoot, buildQbftExtra(List.of(VALIDATOR_ADDR), List.of()), 15);

            Rlp.Item sealedItem = Rlp.decodeOne(headerWithSeal);
            Rlp.Item sealedExtra = Rlp.decodeOne(sealedItem.children().get(12).asBytes());

            Rlp.Item unsealedItem = Rlp.decodeOne(headerWithNoSeals);
            Rlp.Item unsealedExtra =
                    Rlp.decodeOne(unsealedItem.children().get(12).asBytes());

            byte[] hashFromSealed = BesuQbftVerifier.buildCommitSealHash(sealedItem, sealedExtra);
            byte[] hashFromUnsealed = BesuQbftVerifier.buildCommitSealHash(unsealedItem, unsealedExtra);

            assertThat(hashFromSealed).isEqualTo(hashFromUnsealed);
        }

        @Test
        void differentRoundProducesDifferentHash() {
            byte[] stateRoot = keccak256(new byte[] {(byte) 0x99});

            byte[] extraRound0 = buildQbftExtraWithRound(List.of(VALIDATOR_ADDR), 0L, List.of());
            byte[] extraRound1 = buildQbftExtraWithRound(List.of(VALIDATOR_ADDR), 1L, List.of());

            Rlp.Item item0 = Rlp.decodeOne(buildRlpHeader(stateRoot, extraRound0, 15));
            Rlp.Item item1 = Rlp.decodeOne(buildRlpHeader(stateRoot, extraRound1, 15));
            Rlp.Item extra0 = Rlp.decodeOne(item0.children().get(12).asBytes());
            Rlp.Item extra1 = Rlp.decodeOne(item1.children().get(12).asBytes());

            byte[] hash0 = BesuQbftVerifier.buildCommitSealHash(item0, extra0);
            byte[] hash1 = BesuQbftVerifier.buildCommitSealHash(item1, extra1);

            assertThat(hash0).isNotEqualTo(hash1);
        }
    }

    @Nested
    class RecoverEthereumAddress {
        @Test
        void msgHashNot32Bytes_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> BesuQbftVerifier.recoverEthereumAddress(new byte[31], new byte[65]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("msgHash must be 32 bytes");
        }

        @Test
        void sealNot65Bytes_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> BesuQbftVerifier.recoverEthereumAddress(new byte[32], new byte[64]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("seal must be 65 bytes");
        }

        @Test
        void v27AndV28NormalizedCorrectly() {
            byte[] hash = keccak256(new byte[] {0x01});
            byte[] sig = sign(hash, VALIDATOR_PRIV);

            byte[] sigWithOffset = Arrays.copyOf(sig, 65);
            sigWithOffset[64] = (byte) (sig[64] + 27);

            assertThat(BesuQbftVerifier.recoverEthereumAddress(hash, sigWithOffset))
                    .isEqualTo(VALIDATOR_ADDR);
        }

        @Test
        void vEquals2_throwsProofException() {
            byte[] hash = keccak256(new byte[] {0x02});
            byte[] sig = sign(hash, VALIDATOR_PRIV);
            sig[64] = 2;

            assertThatThrownBy(() -> BesuQbftVerifier.recoverEthereumAddress(hash, sig))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("v byte must be 0/1 (or 27/28)");
        }

        @Test
        void validSignatureRecoversCorrectAddress() {
            byte[] hash = keccak256(new byte[] {0x03, 0x04, 0x05});
            byte[] sig = sign(hash, VALIDATOR_PRIV);
            assertThat(BesuQbftVerifier.recoverEthereumAddress(hash, sig)).isEqualTo(VALIDATOR_ADDR);
        }
    }

    @Nested
    class EpochHeaderTests {

        private static final BesuQbftVerifier BUNDLE_SUBJECT =
                new BesuQbftVerifier(new BesuQbftVerifier.Config(SERVICE_ADDR, null, 30_000L));

        @Test
        void emptyEpochHeaders_trustAnchorUnchanged() {
            byte[] bundlePayload = buildBundlePayloadWithEpochHeaders(List.of());
            var result = BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR));
            assertThat(result.newTrustAnchor()).isEmpty();
            assertThat(result.newTrustAnchorId()).isEmpty();
        }

        @Test
        void singleEpochHeader_advancesTrustAnchorToNewValidatorSet() {
            // Epoch header: current 1-validator set {VALIDATOR_ADDR} → new 1-validator set {OTHER_ADDR}
            byte[] epochHeader = buildEpochHeader(List.of(OTHER_ADDR), 30_000L, new byte[32], List.of(VALIDATOR_PRIV));

            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            // Current header must be signed by the new validator set {OTHER_ADDR} (quorum=1).
            byte[] currentHeader = buildSignedHeader(OTHER_ADDR, stateRoot, OTHER_PRIV);

            byte[] bundlePayload = buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                    List.of(epochHeader), currentHeader, accountProof);
            var result = BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR));

            // newTrustAnchor = encoded validator set {OTHER_ADDR}
            assertThat(result.newTrustAnchor()).isEqualTo(validatorSet(OTHER_ADDR));
            assertThat(result.newTrustAnchorId())
                    .isEqualTo(java.math.BigInteger.valueOf(1L).toByteArray());
        }

        @Test
        void twoEpochHeaders_chainsCorrectly() {
            // First epoch: {VALIDATOR_ADDR} → {OTHER_ADDR}
            byte[] epochHeader1 = buildEpochHeader(List.of(OTHER_ADDR), 30_000L, new byte[32], List.of(VALIDATOR_PRIV));
            // Second epoch: {OTHER_ADDR} → {VALIDATOR_ADDR}
            byte[] epochHeader2 = buildEpochHeader(List.of(VALIDATOR_ADDR), 60_000L, new byte[32], List.of(OTHER_PRIV));

            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);

            byte[] bundlePayload = buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                    List.of(epochHeader1, epochHeader2), currentHeader, accountProof);
            var result = BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR));

            assertThat(result.newTrustAnchor()).isEqualTo(validatorSet(VALIDATOR_ADDR));
            assertThat(result.newTrustAnchorId())
                    .isEqualTo(java.math.BigInteger.valueOf(2L).toByteArray());
        }

        @Test
        void epochHeaderAdvancesToMultiValidatorSet() {
            // Epoch header advances from {VALIDATOR_ADDR} (quorum=1) to {VALIDATOR_ADDR, OTHER_ADDR} (quorum=2).
            byte[] epochHeader = buildEpochHeader(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), 30_000L, new byte[32], List.of(VALIDATOR_PRIV));

            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            // Current header needs quorum=2 seals from the new 2-validator set.
            byte[] currentHeader = buildSignedHeaderMultiSeal(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), stateRoot, List.of(VALIDATOR_PRIV, OTHER_PRIV));

            byte[] bundlePayload = buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                    List.of(epochHeader), currentHeader, accountProof);
            var result = BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR));

            assertThat(result.newTrustAnchor()).isEqualTo(validatorSet(VALIDATOR_ADDR, OTHER_ADDR));
        }

        @Test
        void epochHeaderValidatorSetIsOrderIndependent() {
            // Two epoch headers that declare the same 2-validator set in different orders must produce
            // the same encoded trust anchor bytes.
            byte[] epochHeaderAB = buildEpochHeader(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), 30_000L, new byte[32], List.of(VALIDATOR_PRIV));
            byte[] epochHeaderBA = buildEpochHeader(
                    List.of(OTHER_ADDR, VALIDATOR_ADDR), 30_000L, new byte[32], List.of(VALIDATOR_PRIV));

            byte[][] accountProofAB = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[][] accountProofBA = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] currentAB = buildSignedHeaderMultiSeal(
                    List.of(VALIDATOR_ADDR, OTHER_ADDR), accountProofAB[0], List.of(VALIDATOR_PRIV, OTHER_PRIV));
            byte[] currentBA = buildSignedHeaderMultiSeal(
                    List.of(OTHER_ADDR, VALIDATOR_ADDR), accountProofBA[0], List.of(VALIDATOR_PRIV, OTHER_PRIV));

            var resultAB = BUNDLE_SUBJECT.verifyBundle(
                    buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                            List.of(epochHeaderAB), currentAB, accountProofAB),
                    validatorSet(VALIDATOR_ADDR));
            var resultBA = BUNDLE_SUBJECT.verifyBundle(
                    buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                            List.of(epochHeaderBA), currentBA, accountProofBA),
                    validatorSet(VALIDATOR_ADDR));

            assertThat(resultAB.newTrustAnchor()).isEqualTo(resultBA.newTrustAnchor());
        }

        @Test
        void outOfOrderEpochHeaders_throwsProofException() {
            byte[] epochHeader1 = buildEpochHeader(List.of(OTHER_ADDR), 60_000L, new byte[32], List.of(VALIDATOR_PRIV));
            byte[] epochHeader2 = buildEpochHeader(List.of(VALIDATOR_ADDR), 30_000L, new byte[32], List.of(OTHER_PRIV));

            byte[] bundlePayload = buildBundlePayloadWithEpochHeaders(List.of(epochHeader1, epochHeader2));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("is not strictly greater than previous");
        }

        @Test
        void epochHeaderSignedByKeyNotInCurrentSet_throwsProofException() {
            // Epoch header signed by OTHER_PRIV, but trust anchor is {VALIDATOR_ADDR}
            byte[] epochHeader = buildEpochHeader(List.of(OTHER_ADDR), 30_000L, new byte[32], List.of(OTHER_PRIV));
            byte[] bundlePayload = buildBundlePayloadWithEpochHeaders(List.of(epochHeader));

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        @Test
        void currentHeaderSignedByOldKeyAfterEpochAdvance_throwsProofException() {
            // Epoch advances to {OTHER_ADDR}, but currentHeader is still signed by VALIDATOR_PRIV
            byte[] epochHeader = buildEpochHeader(List.of(OTHER_ADDR), 30_000L, new byte[32], List.of(VALIDATOR_PRIV));

            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);

            byte[] bundlePayload = buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                    List.of(epochHeader), currentHeader, accountProof);

            assertThatThrownBy(() -> BUNDLE_SUBJECT.verifyBundle(bundlePayload, validatorSet(VALIDATOR_ADDR)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not in the validator set");
        }

        private static byte[] buildBundlePayloadWithEpochHeaders(List<byte[]> epochHeaders) {
            byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
            byte[] stateRoot = accountProof[0];
            byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, stateRoot, VALIDATOR_PRIV);
            return buildBundlePayloadWithEpochHeadersAndCurrentHeader(epochHeaders, currentHeader, accountProof);
        }

        private static byte[] buildBundlePayloadWithEpochHeadersAndCurrentHeader(
                List<byte[]> epochHeaders, byte[] currentHeader, byte[][] accountProof) {
            byte[] accountProofRlp = Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1])));
            byte[] storageEntry = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[32]), Rlp.encodeList(List.of())));
            byte[] storageProofRlp =
                    Rlp.encodeList(List.of(storageEntry, storageEntry, storageEntry, storageEntry, storageEntry));
            byte[] epochBlockHeadersRlp = Rlp.encodeList(epochHeaders);
            return Rlp.encodeList(List.of(
                    currentHeader,
                    epochBlockHeadersRlp,
                    accountProofRlp,
                    storageProofRlp,
                    Rlp.encodeBytes(new byte[0])));
        }
    }

    @Nested
    class DecodeQueueMetadata {

        @Test
        void fiveSlotBundle_decodesAllFields() {
            byte[] slot0 = new byte[32];
            ByteBuffer.wrap(slot0, 3, 8).putLong(42L);
            slot0[11] = 0x02;

            byte[] slot1 = new byte[32];
            ByteBuffer.wrap(slot1, 16, 8).putLong(7L);

            byte[] slot2 = new byte[32];
            slot2[0] = (byte) 0xAA;

            byte[] slot3 = new byte[32];
            slot3[0] = (byte) 0xBB;

            byte[] slot4 = new byte[32];
            slot4[0] = (byte) 0xCC;

            var meta = BesuQbftVerifier.decodeQueueMetadata(new byte[][] {slot0, slot1, slot2, slot3, slot4});

            assertThat(meta.nextMessageId()).isEqualTo(42L);
            assertThat(meta.status()).isEqualTo(2);
            assertThat(meta.receivedMessageId()).isEqualTo(7L);
            assertThat(meta.sentRunningHash()[0]).isEqualTo((byte) 0xAA);
            assertThat(meta.receivedRunningHash()[0]).isEqualTo((byte) 0xBB);
            assertThat(meta.lastMessageRunningHash()[0]).isEqualTo((byte) 0xCC);
        }

        @Test
        void fourSlotBundle_lastMsgHashIsZero() {
            byte[] slot0 = new byte[32];
            ByteBuffer.wrap(slot0, 3, 8).putLong(1L);
            byte[] slot1 = new byte[32];
            byte[] slot2 = new byte[32];
            byte[] slot3 = new byte[32];

            var meta = BesuQbftVerifier.decodeQueueMetadata(new byte[][] {slot0, slot1, slot2, slot3});

            assertThat(meta.nextMessageId()).isEqualTo(1L);
            assertThat(meta.lastMessageRunningHash()).isEqualTo(new byte[32]);
        }

        @Test
        void threeSlotBundle_throws() {
            byte[] slot = new byte[32];
            assertThatThrownBy(() -> BesuQbftVerifier.decodeQueueMetadata(new byte[][] {slot, slot, slot}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected 4 or 5 proven slot values");
        }

        @Test
        void sixSlotBundle_throws() {
            byte[] slot = new byte[32];
            assertThatThrownBy(() ->
                            BesuQbftVerifier.decodeQueueMetadata(new byte[][] {slot, slot, slot, slot, slot, slot}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected 4 or 5 proven slot values");
        }
    }

    // ── Signing helpers ──────────────────────────────────────────────────────

    /** Signs {@code hash32} with secp256k1; returns r‖s‖v (65 bytes, v = 0 or 1). */
    private static byte[] sign(byte[] hash32, byte[] privKey32) {
        var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, hash32, privKey32, null, null);
        var compact = ByteBuffer.allocate(64);
        var recId = new IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compact, recId, recoverableSig);
        byte[] seal = new byte[65];
        System.arraycopy(compact.array(), 0, seal, 0, 64);
        seal[64] = (byte) recId.getValue();
        return seal;
    }

    // ── Header builders ──────────────────────────────────────────────────────

    /** RLP[vanity(32), validators[], vote, round(0), committedSeals[]] */
    private static byte[] buildQbftExtra(List<byte[]> validators, List<byte[]> seals) {
        return buildQbftExtraWithRound(validators, 0L, seals);
    }

    private static byte[] buildQbftExtraWithRound(List<byte[]> validators, long round, List<byte[]> seals) {
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(new byte[32]),
                Rlp.encodeList(validators.stream().map(Rlp::encodeBytes).toList()),
                Rlp.encodeBytes(new byte[0]),
                Rlp.encodeUint(round),
                Rlp.encodeList(seals.stream().map(Rlp::encodeBytes).toList())));
    }

    private static byte[] buildRlpHeader(byte[] stateRoot32, byte[] extraDataBytes, int fieldCount) {
        List<byte[]> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(
                    switch (i) {
                        case 3 -> Rlp.encodeBytes(stateRoot32);
                        case 12 -> Rlp.encodeBytes(extraDataBytes);
                        default -> Rlp.encodeBytes(new byte[0]);
                    });
        }
        return Rlp.encodeList(fields);
    }

    private static byte[] buildRlpHeaderWithBlockNumber(
            byte[] stateRoot32, byte[] extraDataBytes, long blockNumber, int fieldCount) {
        List<byte[]> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(
                    switch (i) {
                        case 3 -> Rlp.encodeBytes(stateRoot32);
                        case 8 -> Rlp.encodeUint(blockNumber);
                        case 12 -> Rlp.encodeBytes(extraDataBytes);
                        default -> Rlp.encodeBytes(new byte[0]);
                    });
        }
        return Rlp.encodeList(fields);
    }

    private static byte[] buildGenesisHeader(byte[] validatorAddr) {
        return buildRlpHeader(new byte[32], buildQbftExtra(List.of(validatorAddr), List.of()), 15);
    }

    /** Single-signer convenience wrapper. */
    private static byte[] buildSignedHeader(byte[] validatorAddr, byte[] stateRoot32, byte[] privKey32) {
        return buildSignedHeaderMultiSeal(List.of(validatorAddr), stateRoot32, List.of(privKey32));
    }

    /**
     * Builds a header with committed seals from all {@code signerPrivKeys}.
     * The validators list in extra-data is set to {@code validatorAddrs}.
     */
    private static byte[] buildSignedHeaderMultiSeal(
            List<byte[]> validatorAddrs, byte[] stateRoot32, List<byte[]> signerPrivKeys) {
        byte[] extraNoSeals = buildQbftExtra(validatorAddrs, List.of());
        byte[] headerForHash = buildRlpHeader(stateRoot32, extraNoSeals, 15);
        Rlp.Item headerItem = Rlp.decodeOne(headerForHash);
        Rlp.Item extraItem = Rlp.decodeOne(headerItem.children().get(12).asBytes());
        byte[] commitHash = BesuQbftVerifier.buildCommitSealHash(headerItem, extraItem);
        List<byte[]> seals =
                signerPrivKeys.stream().map(k -> sign(commitHash, k)).toList();
        return buildRlpHeader(stateRoot32, buildQbftExtra(validatorAddrs, seals), 15);
    }

    /**
     * Builds an epoch header declaring {@code nextValidators} and signed by {@code signerPrivKeys}
     * (the current validator set).
     */
    private static byte[] buildEpochHeader(
            List<byte[]> nextValidators, long blockNumber, byte[] stateRoot, List<byte[]> signerPrivKeys) {
        byte[] extraNoSeals = buildQbftExtra(nextValidators, List.of());
        byte[] headerForHash = buildRlpHeaderWithBlockNumber(stateRoot, extraNoSeals, blockNumber, 15);
        Rlp.Item headerItem = Rlp.decodeOne(headerForHash);
        Rlp.Item extraItem = Rlp.decodeOne(headerItem.children().get(12).asBytes());
        byte[] commitHash = BesuQbftVerifier.buildCommitSealHash(headerItem, extraItem);
        List<byte[]> seals =
                signerPrivKeys.stream().map(k -> sign(commitHash, k)).toList();
        return buildRlpHeaderWithBlockNumber(stateRoot, buildQbftExtra(nextValidators, seals), blockNumber, 15);
    }

    // ── MPT proof builders ───────────────────────────────────────────────────

    private static byte[][] buildAccountMptProof(byte[] contractAddr20, byte[] storageRoot32, byte[] codeHash32) {
        byte[] accountKey32 = keccak256(contractAddr20);
        byte[] accountRlp = Rlp.encodeList(List.of(
                Rlp.encodeUint(0L), Rlp.encodeUint(0L), Rlp.encodeBytes(storageRoot32), Rlp.encodeBytes(codeHash32)));
        byte[] hexPrefix = new byte[33];
        hexPrefix[0] = 0x20;
        System.arraycopy(accountKey32, 0, hexPrefix, 1, 32);
        byte[] leafNode = Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix), Rlp.encodeBytes(accountRlp)));
        return new byte[][] {keccak256(leafNode), leafNode};
    }

    private static byte[][] buildStorageMptProof(byte[] storageSlotKey32, byte[] provenValue32) {
        byte[] storageKeyHash = keccak256(storageSlotKey32);
        byte[] hexPrefix = new byte[33];
        hexPrefix[0] = 0x20;
        System.arraycopy(storageKeyHash, 0, hexPrefix, 1, 32);
        byte[] encodedValue = Rlp.encodeBytes(provenValue32);
        byte[] leafNode = Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix), Rlp.encodeBytes(encodedValue)));
        return new byte[][] {keccak256(leafNode), leafNode};
    }

    // ── Payload / trust-anchor helpers ───────────────────────────────────────

    private static byte[] serviceAddrSlotValue(byte[] addr20) {
        byte[] value = new byte[32];
        System.arraycopy(addr20, 0, value, 0, 20);
        value[31] = (byte) (20 * 2);
        return value;
    }

    private static byte[] payloadWithStorageEntries(List<StorageProofEntry> entries) {
        byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, EMPTY_TRIE_ROOT, new byte[32]);
        byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
        byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);

        return serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                .genesisBlockHeader(header(genesisHeader))
                .currentBlockHeader(header(currentHeader))
                .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                .clprServiceStorageProofs(entries)
                .build());
    }

    private static byte[] minimalPayload(byte[] genesisHeader, byte[] currentHeader) {
        return serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                .genesisBlockHeader(header(genesisHeader))
                .currentBlockHeader(header(currentHeader))
                .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                .build());
    }

    private static BlockHeader header(byte[] rlpBytes) {
        return BlockHeader.newBuilder().rlp(Bytes.wrap(rlpBytes)).build();
    }

    private static ClprLedgerConfiguration ledgerConfig(byte[] serviceAddr) {
        return ClprLedgerConfiguration.newBuilder()
                .serviceAddress(Bytes.wrap(serviceAddr))
                .build();
    }

    private static byte[] serialize(ClprQbftLedgerConfigurationPayload payload) {
        return ClprQbftLedgerConfigurationPayload.PROTOBUF.toBytes(payload).toByteArray();
    }

    /**
     * Builds the expected {@code initialTrustAnchor} bytes produced by
     * {@link BesuQbftVerifier#verifyConfigPayload}:
     * {@code RLP([RLP_BYTES(encodeValidatorSet(validators)), serviceAddr, codeHash])}.
     */
    private static byte[] qbftTrustAnchor(List<byte[]> validators, byte[] serviceAddr, byte[] codeHash) {
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(BesuQbftVerifier.encodeValidatorSet(validators)),
                Rlp.encodeBytes(serviceAddr),
                Rlp.encodeBytes(codeHash)));
    }

    private static byte[] buildTrieLeafNode(byte[] pathHash32, byte[] value32) {
        // Hex prefix for an odd-length leaf: flag nibble 0x3, followed by nibble[1..63] of pathHash
        // byte[0] = 0x30 | low nibble of pathHash32[0]  (nibble[1] of full path)
        // bytes[1..31] = pathHash32[1..31]               (nibbles[2..63] packed)
        byte[] hexPrefix = new byte[32];
        hexPrefix[0] = (byte) (0x30 | (pathHash32[0] & 0x0F));
        System.arraycopy(pathHash32, 1, hexPrefix, 1, 31);
        byte[] encodedValue = Rlp.encodeBytes(value32);
        return Rlp.encodeList(List.of(Rlp.encodeBytes(hexPrefix), Rlp.encodeBytes(encodedValue)));
    }

    private static byte[][][] buildFourSlotStorageMpt(
            byte[] slotKey0,
            byte[] value0,
            byte[] slotKey1,
            byte[] value1,
            byte[] slotKey2,
            byte[] value2,
            byte[] slotKey3,
            byte[] value3) {

        byte[] path0 = keccak256(slotKey0);
        byte[] path1 = keccak256(slotKey1);
        byte[] path2 = keccak256(slotKey2);
        byte[] path3 = keccak256(slotKey3);

        int n0 = (path0[0] >>> 4) & 0xF;
        int n1 = (path1[0] >>> 4) & 0xF;
        int n2 = (path2[0] >>> 4) & 0xF;
        int n3 = (path3[0] >>> 4) & 0xF;
        if (n0 == n1 || n0 == n2 || n0 == n3 || n1 == n2 || n1 == n3 || n2 == n3) {
            throw new IllegalArgumentException("slot keys must have distinct first nibbles in keccak256 hash");
        }

        byte[] leaf0 = buildTrieLeafNode(path0, value0);
        byte[] leaf1 = buildTrieLeafNode(path1, value1);
        byte[] leaf2 = buildTrieLeafNode(path2, value2);
        byte[] leaf3 = buildTrieLeafNode(path3, value3);

        List<byte[]> branchItems = new ArrayList<>();
        for (int i = 0; i < 17; i++) branchItems.add(Rlp.encodeBytes(new byte[0]));
        branchItems.set(n0, Rlp.encodeBytes(keccak256(leaf0)));
        branchItems.set(n1, Rlp.encodeBytes(keccak256(leaf1)));
        branchItems.set(n2, Rlp.encodeBytes(keccak256(leaf2)));
        branchItems.set(n3, Rlp.encodeBytes(keccak256(leaf3)));
        byte[] branchNode = Rlp.encodeList(branchItems);
        byte[] storageRoot = keccak256(branchNode);

        return new byte[][][] {
            {storageRoot}, {branchNode, leaf0}, {branchNode, leaf1}, {branchNode, leaf2}, {branchNode, leaf3}
        };
    }

    /**
     * Build a 5-slot storage MPT (SC-189 Channel layout: the 4 queue slots + the
     * endpointManifestVersion slot). Returns {storageRoot, [branch,leaf0], …, [branch,leaf4]}.
     */
    private static byte[][][] buildFiveSlotStorageMpt(final byte[][] slotKeys, final byte[][] values) {
        final int[] nibbles = new int[5];
        final byte[][] leaves = new byte[5][];
        for (int i = 0; i < 5; i++) {
            final byte[] path = keccak256(slotKeys[i]);
            nibbles[i] = (path[0] >>> 4) & 0xF;
            leaves[i] = buildTrieLeafNode(path, values[i]);
        }
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                if (nibbles[i] == nibbles[j]) {
                    throw new IllegalArgumentException("slot keys must have distinct first nibbles in keccak256 hash");
                }
            }
        }
        final List<byte[]> branchItems = new ArrayList<>();
        for (int i = 0; i < 17; i++) branchItems.add(Rlp.encodeBytes(new byte[0]));
        for (int i = 0; i < 5; i++) branchItems.set(nibbles[i], Rlp.encodeBytes(keccak256(leaves[i])));
        final byte[] branchNode = Rlp.encodeList(branchItems);
        final byte[] storageRoot = keccak256(branchNode);
        return new byte[][][] {
            {storageRoot},
            {branchNode, leaves[0]},
            {branchNode, leaves[1]},
            {branchNode, leaves[2]},
            {branchNode, leaves[3]},
            {branchNode, leaves[4]}
        };
    }

    // ── Endpoint-manifest proof helpers ──────────────────────────────────────

    /** The 32-byte encoding of the endpoint-manifest commitment slot (18). */
    private static byte[] manifestCommitmentSlot() {
        return com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot();
    }

    /** Serializes a minimal {@code ClprEndpointManifest} (empty endpoint list) as its proof preimage. */
    private static byte[] manifestPreimage(long version, byte[] serviceAddr20) {
        return ClprEndpointManifest.PROTOBUF
                .toBytes(ClprEndpointManifest.newBuilder()
                        .version(version)
                        .serviceAddress(Bytes.wrap(serviceAddr20))
                        .build())
                .toByteArray();
    }

    /**
     * Builds an N-slot storage MPT (single branch node, one leaf per slot keyed on its keccak256's
     * first nibble). Returns {@code {storageRoot}, {branch,leaf0}, …, {branch,leafN-1}}. Throws
     * {@link IllegalArgumentException} if two slot keys share a first nibble.
     */
    private static byte[][][] buildStorageMpt(final byte[][] slotKeys, final byte[][] values) {
        final int n = slotKeys.length;
        final int[] nibbles = new int[n];
        final byte[][] leaves = new byte[n][];
        for (int i = 0; i < n; i++) {
            final byte[] path = keccak256(slotKeys[i]);
            nibbles[i] = (path[0] >>> 4) & 0xF;
            leaves[i] = buildTrieLeafNode(path, values[i]);
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (nibbles[i] == nibbles[j]) {
                    throw new IllegalArgumentException("slot keys must have distinct first nibbles in keccak256 hash");
                }
            }
        }
        final List<byte[]> branchItems = new ArrayList<>();
        for (int i = 0; i < 17; i++) branchItems.add(Rlp.encodeBytes(new byte[0]));
        for (int i = 0; i < n; i++) branchItems.set(nibbles[i], Rlp.encodeBytes(keccak256(leaves[i])));
        final byte[] branchNode = Rlp.encodeList(branchItems);
        final byte[] storageRoot = keccak256(branchNode);
        final byte[][][] out = new byte[n + 1][][];
        out[0] = new byte[][] {storageRoot};
        for (int i = 0; i < n; i++) out[i + 1] = new byte[][] {branchNode, leaves[i]};
        return out;
    }

    /**
     * Builds a bundle payload with a Step-1b endpoint-manifest extension. The 5 ACK-only Channel
     * slots {@code {1,2,4,5,16}} (at a collision-free base offset) and the manifest commitment slot
     * (18) share one storage trie; {@code commitmentPreimage} is committed at slot 18, {@code
     * index6Preimage} is placed at RLP index 6, and the manifest storage proof (index 5) carries
     * {@code index5EntryCount} entries keyed on {@code index5SlotKey}. {@code index5EntryCount == 0}
     * yields a plain 5-item bundle (no manifest extension).
     */
    private static byte[] buildManifestBundle(
            byte[] commitmentPreimage, byte[] index6Preimage, byte[] index5SlotKey, int index5EntryCount) {
        final byte[] commitment = keccak256(commitmentPreimage);
        final int[] offs = {1, 2, 4, 5, 16};
        for (int b = 0; b < 256; b++) {
            boolean clashesManifestSlot = false;
            for (int o : offs) {
                if (b + o == 18) clashesManifestSlot = true;
            }
            if (clashesManifestSlot) continue;
            final byte[][] connKeys = new byte[5][];
            for (int i = 0; i < 5; i++) {
                final byte[] k = new byte[32];
                ByteBuffer.wrap(k, 24, 8).putLong((long) b + offs[i]);
                connKeys[i] = k;
            }
            final byte[][] slotKeys = {
                connKeys[0], connKeys[1], connKeys[2], connKeys[3], connKeys[4], manifestCommitmentSlot()
            };
            final byte[][] values = {new byte[32], new byte[32], new byte[32], new byte[32], new byte[32], commitment};
            final byte[][][] mpt;
            try {
                mpt = buildStorageMpt(slotKeys, values);
            } catch (final IllegalArgumentException nibbleClash) {
                continue; // try the next base offset
            }
            final byte[] storageRoot = mpt[0][0];
            final byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageRoot, new byte[32]);
            final byte[] signedHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);
            final List<byte[]> connEntries = new ArrayList<>();
            for (int i = 0; i < 5; i++) connEntries.add(buildBundleStorageEntry(connKeys[i], mpt[i + 1]));
            final byte[] accountProofRlp = Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1])));
            if (index5EntryCount == 0) {
                return Rlp.encodeList(List.of(
                        signedHeader,
                        Rlp.encodeList(List.of()),
                        accountProofRlp,
                        Rlp.encodeList(connEntries),
                        Rlp.encodeBytes(new byte[0])));
            }
            final List<byte[]> manifestEntries = new ArrayList<>();
            for (int i = 0; i < index5EntryCount; i++) {
                manifestEntries.add(buildBundleStorageEntry(index5SlotKey, mpt[6]));
            }
            return Rlp.encodeList(List.of(
                    signedHeader,
                    Rlp.encodeList(List.of()),
                    accountProofRlp,
                    Rlp.encodeList(connEntries),
                    Rlp.encodeBytes(new byte[0]),
                    Rlp.encodeList(manifestEntries),
                    Rlp.encodeBytes(index6Preimage)));
        }
        throw new IllegalStateException("no collision-free storage layout found for the manifest bundle");
    }

    /** A structurally-valid config payload (VALIDATOR_ADDR genesis, SERVICE_ADDR, slot-25 proof). */
    private static byte[] validConfigPayload() {
        byte[] correctSlotValue = serviceAddrSlotValue(SERVICE_ADDR);
        byte[][] storageMpt = buildStorageMptProof(SERVICE_ADDR_STORAGE_SLOT, correctSlotValue);
        byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
        byte[] genesisHeader = buildGenesisHeader(VALIDATOR_ADDR);
        byte[] currentHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);
        return serialize(ClprQbftLedgerConfigurationPayload.newBuilder()
                .genesisBlockHeader(header(genesisHeader))
                .currentBlockHeader(header(currentHeader))
                .ledgerConfiguration(ledgerConfig(SERVICE_ADDR))
                .clprServiceAccountProof(List.of(Bytes.wrap(accountProof[1])))
                .clprServiceStorageProofs(List.of(StorageProofEntry.newBuilder()
                        .key(Bytes.wrap(SERVICE_ADDR_STORAGE_SLOT))
                        .value(Bytes.wrap(correctSlotValue))
                        .proof(List.of(Bytes.wrap(storageMpt[1])))
                        .build()))
                .build());
    }

    /** Config-path manifest proof {@code RLP([header, accountProof, manifestStorageProof, preimage])}. */
    private static byte[] buildConfigManifestProof(byte[] commitmentPreimage, byte[] preimageField, byte[] slotKey) {
        return buildConfigManifestProof(commitmentPreimage, preimageField, slotKey, VALIDATOR_PRIV);
    }

    private static byte[] buildConfigManifestProof(
            byte[] commitmentPreimage, byte[] preimageField, byte[] slotKey, byte[] headerSignerPriv) {
        byte[] commitment = keccak256(commitmentPreimage);
        byte[][] storageMpt = buildStorageMptProof(manifestCommitmentSlot(), commitment);
        byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageMpt[0], new byte[32]);
        byte[] hdr = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], headerSignerPriv);
        byte[] manifestStorageProof =
                Rlp.encodeList(List.of(buildBundleStorageEntry(slotKey, new byte[][] {storageMpt[1]})));
        return Rlp.encodeList(List.of(
                hdr,
                Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1]))),
                manifestStorageProof,
                Rlp.encodeBytes(preimageField)));
    }

    // ── Exact connId-based Channel-slot matching helpers ──────────────────

    /** The SC-189 `_channels[connId]` struct base slot: keccak256(connId(32) || uint256(15)). */
    private static byte[] connSlotBase(byte[] connId32) {
        byte[] pre = new byte[64];
        System.arraycopy(connId32, 0, pre, 0, 32);
        pre[63] = 15; // CHANNELS_MAPPING_SLOT
        return keccak256(pre);
    }

    /** base32 + offset (big-endian, offset small). */
    private static byte[] plusOffset(byte[] base32, int offset) {
        byte[] out = base32.clone();
        int carry = offset;
        for (int i = 31; i >= 0 && carry != 0; i--) {
            int sum = (out[i] & 0xff) + (carry & 0xff);
            out[i] = (byte) (sum & 0xff);
            carry = (carry >>> 8) + (sum >>> 8);
        }
        return out;
    }

    /**
     * Builds a 5-item bundle whose storage proof proves exactly {@code slotKeys}→{@code values}
     * against one trie (used to exercise exact connId slot-matching). Retries with an incremented
     * connId-derived layout is the caller's job; this throws on a first-nibble collision.
     */
    private static byte[] buildBundleWithSlots(byte[][] slotKeys, byte[][] values) {
        byte[][][] mpt = buildStorageMpt(slotKeys, values);
        byte[] storageRoot = mpt[0][0];
        byte[][] accountProof = buildAccountMptProof(SERVICE_ADDR, storageRoot, new byte[32]);
        byte[] signedHeader = buildSignedHeader(VALIDATOR_ADDR, accountProof[0], VALIDATOR_PRIV);
        List<byte[]> entries = new ArrayList<>();
        for (int i = 0; i < slotKeys.length; i++) entries.add(buildBundleStorageEntry(slotKeys[i], mpt[i + 1]));
        return Rlp.encodeList(List.of(
                signedHeader,
                Rlp.encodeList(List.of()),
                Rlp.encodeList(List.of(Rlp.encodeBytes(accountProof[1]))),
                Rlp.encodeList(entries),
                Rlp.encodeBytes(new byte[0])));
    }

    /** A 32-byte channelId whose {1,2,4,5,16}(+optional message) slot keccaks have distinct nibbles. */
    private static byte[] findConnId(boolean withMessage) {
        for (int seed = 1; seed < 4096; seed++) {
            byte[] connId = new byte[32];
            ByteBuffer.wrap(connId, 24, 8).putLong(seed);
            byte[] base = connSlotBase(connId);
            List<byte[]> keys = new ArrayList<>(List.of(
                    plusOffset(base, 1),
                    plusOffset(base, 2),
                    plusOffset(base, 4),
                    plusOffset(base, 5),
                    plusOffset(base, 16)));
            byte[][] vals = {new byte[32], new byte[32], new byte[32], new byte[32], new byte[32]};
            if (withMessage) {
                keys.add(keccak256(connId)); // a far outlier, standing in for the message running-hash slot
                vals = new byte[][] {new byte[32], new byte[32], new byte[32], new byte[32], new byte[32], new byte[32]
                };
            }
            try {
                buildStorageMpt(keys.toArray(new byte[0][]), vals);
                return connId;
            } catch (IllegalArgumentException nibbleClash) {
                // try next seed
            }
        }
        throw new IllegalStateException("no collision-free channelId found");
    }

    private static byte[] buildBundleStorageEntry(byte[] slotKey32, byte[][] proofNodes) {
        List<byte[]> nodeItems = new ArrayList<>();
        for (byte[] node : proofNodes) nodeItems.add(Rlp.encodeBytes(node));
        return Rlp.encodeList(List.of(Rlp.encodeBytes(slotKey32), Rlp.encodeList(nodeItems)));
    }

    private static byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }
}
