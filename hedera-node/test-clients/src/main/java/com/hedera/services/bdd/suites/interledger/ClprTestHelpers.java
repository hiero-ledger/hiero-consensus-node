// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * Static helpers for constructing CLPR smoke-test artifacts.
 *
 * <p>Provides secp256k1 crypto helpers, synthetic StateProof builders, and CLPR proto factories
 * needed by CLPR smoke tests. All crypto uses a fixed 32-byte test secret key
 * so that commitment/signature values can be reproduced deterministically.
 */
public final class ClprTestHelpers {

    /** Fixed 32-byte secp256k1 secret key used for the smoke-test channel keypair. */
    public static final byte[] CHANNEL_SECRET_KEY = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };

    /** Fixed 32-byte channel ID used for the smoke-test channel. */
    public static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42
    });

    /** Fixed 32-byte secp256k1 secret key used for the connector keypair (distinct from channel key). */
    public static final byte[] CONNECTOR_SECRET_KEY = {
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30,
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40
    };

    /** Fixed 32-byte salt used when deriving the connector id. */
    public static final Bytes CONNECTOR_SALT = Bytes.wrap(new byte[] {
        (byte) 0xa0, (byte) 0xa1, (byte) 0xa2, (byte) 0xa3, (byte) 0xa4, (byte) 0xa5, (byte) 0xa6, (byte) 0xa7,
        (byte) 0xa8, (byte) 0xa9, (byte) 0xaa, (byte) 0xab, (byte) 0xac, (byte) 0xad, (byte) 0xae, (byte) 0xaf,
        (byte) 0xb0, (byte) 0xb1, (byte) 0xb2, (byte) 0xb3, (byte) 0xb4, (byte) 0xb5, (byte) 0xb6, (byte) 0xb7,
        (byte) 0xb8, (byte) 0xb9, (byte) 0xba, (byte) 0xbb, (byte) 0xbc, (byte) 0xbd, (byte) 0xbe, (byte) 0xbf
    });

    /** Hardcoded 20-byte address of the CLPR system contract (0x16e). Mirrors the value embedded in
     * {@code ClprCompleteConnectorHandler.CLPR_SERVICE_ADDRESS} for connector signature messages. */
    public static final byte[] CLPR_SERVICE_ADDRESS_20 = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x6e
    };

    /** 48-byte placeholder accepted by the Phase-1 parse-only verifier (not a valid TSS signature). */
    private static final byte[] DUMMY_TSS_SIG = new byte[48];

    private ClprTestHelpers() {}

    /**
     * Builds a synthetic config {@link StateProof} that the native {@code HieroTssVerifier}
     * accepts via its singleton-leaf path.
     *
     * <p>The proof contains a single {@link StateItem} leaf whose key is the
     * {@code CLPRSERVICE_I_LEDGER_CONFIGURATION} singleton and whose value carries the supplied
     * {@link ClprLedgerConfiguration}. Returns the protobuf-serialized {@link StateProof} bytes.
     */
    public static Bytes buildSyntheticConfigProof(final ClprLedgerConfiguration config) {
        final var stateKey = StateKey.newBuilder()
                .singleton(SingletonType.CLPRSERVICE_I_LEDGER_CONFIGURATION)
                .build();
        final var stateValue =
                StateValue.newBuilder().clprServiceILedgerConfiguration(config).build();
        final var leafBytes = StateItem.PROTOBUF.toBytes(new StateItem(stateKey, stateValue));
        return StateProof.PROTOBUF.toBytes(buildSingleLeafStateProof(leafBytes));
    }

    /**
     * Builds a synthetic bundle {@link StateProof} authenticating an empty bundle (no messages).
     * The proof contains a single {@link StateItem} leaf carrying the supplied {@link ClprChannel};
     * the verifier reconstructs {@code ClprQueueMetadata} from the channel's fields and returns an
     * empty messages list.
     *
     * <p>Multi-message synthetic proofs require a real (or carefully fabricated) binary Merkle tree
     * so all leaves share a root that {@link com.hedera.node.app.hapi.utils.blocks.StateProofVerifier#computeBlockRootHash} accepts;
     * smoke tests that need messages should drive enqueue→sync from real network state instead.</p>
     *
     * @param channelId 32-byte channel identifier
     * @param channel the {@link ClprChannel} state row supplying the queue metadata
     */
    public static Bytes buildSyntheticBundleProof(final Bytes channelId, final ClprChannel channel) {
        final var connItemBytes = StateItem.PROTOBUF.toBytes(new StateItem(
                StateKey.newBuilder()
                        .clprServiceIChannels(
                                ProtoBytes.newBuilder().value(channelId).build())
                        .build(),
                StateValue.newBuilder().clprServiceIChannels(channel).build()));
        return StateProof.PROTOBUF.toBytes(buildSingleLeafStateProof(connItemBytes));
    }

    /** Pair of a {@link ClprMessageKey} and its corresponding {@link ClprMessageValue} for proof building. */
    public record MessageEntry(ClprMessageKey key, ClprMessageValue value) {}

    /**
     * Builds a synthetic bundle {@link StateProof} that aggregates a {@link ClprChannel} leaf and
     * a single {@link ClprMessageValue} leaf under one synthetic root. The verifier reconstructs
     * {@link com.hedera.hapi.node.state.clpr.ClprBundleContent} by walking both leaves: the
     * channel leaf supplies {@code ClprQueueMetadata}, the message leaf supplies the lone payload.
     *
     * <p>Layout:
     * <pre>
     *   path 0: channel state_item_leaf, nextPathIndex = 2
     *   path 1: message state_item_leaf,    nextPathIndex = 2
     *   path 2: internal node (no leaf, no hash, no siblings), nextPathIndex = -1 (root)
     * </pre>
     *
     * <p>{@link com.hedera.node.app.hapi.utils.blocks.StateProofVerifier#computeBlockRootHash} pushes both leaf
     * hashes onto its stack, then path 2 pops them as the two children of the root and combines via
     * SHA-384 {@code joinHashes}. No siblings are required because the root is the synthetic parent.
     *
     * @param channelId 32-byte channel identifier
     * @param channel the {@link ClprChannel} state row supplying queue metadata
     * @param messageKey the message key (carries channel_id + message_id used for ordering)
     * @param messageValue the message value (carries the payload to dispatch)
     */
    public static Bytes buildSyntheticDataBundleProof(
            final Bytes channelId,
            final ClprChannel channel,
            final ClprMessageKey messageKey,
            final ClprMessageValue messageValue) {
        final var connItemBytes = StateItem.PROTOBUF.toBytes(new StateItem(
                StateKey.newBuilder()
                        .clprServiceIChannels(
                                ProtoBytes.newBuilder().value(channelId).build())
                        .build(),
                StateValue.newBuilder().clprServiceIChannels(channel).build()));
        final var msgItemBytes = StateItem.PROTOBUF.toBytes(new StateItem(
                StateKey.newBuilder().clprServiceIMessageQueue(messageKey).build(),
                StateValue.newBuilder().clprServiceIMessageQueue(messageValue).build()));

        final int rootIndex = 2;
        final var paths = new ArrayList<MerklePath>(3);
        paths.add(MerklePath.newBuilder()
                .stateItemLeaf(connItemBytes)
                .nextPathIndex(rootIndex)
                .build());
        paths.add(MerklePath.newBuilder()
                .stateItemLeaf(msgItemBytes)
                .nextPathIndex(rootIndex)
                .build());
        // Root: internal path with no leaf, no explicit hash, no siblings — the verifier will
        // pop the two leaf hashes off the stack and compute joinHashes(leaf0, leaf1) for the root.
        paths.add(MerklePath.newBuilder().nextPathIndex(-1).build());

        return StateProof.PROTOBUF.toBytes(StateProof.newBuilder()
                .paths(paths)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(DUMMY_TSS_SIG))
                        .build())
                .build());
    }

    /**
     * Computes {@code SHA-256(previousHash || protobuf_encode(payload))} — the running-hash chain
     * step the {@code ClprSubmitBundleHandler} uses to validate metadata.sentRunningHash matches
     * the receiver's replay over each enqueued message.
     */
    public static Bytes computeRunningHash(final Bytes previousHash, final ClprMessagePayload payload) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousHash.toByteArray());
            digest.update(ClprMessagePayload.PROTOBUF.toBytes(payload).toByteArray());
            return Bytes.wrap(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Builds a single-leaf {@link StateProof} (one leaf, root-marker, no siblings) suitable for
     * synthetic bring-up tests.
     */
    private static StateProof buildSingleLeafStateProof(final Bytes leafBytes) {
        return StateProof.newBuilder()
                .paths(List.of(MerklePath.newBuilder()
                        .stateItemLeaf(leafBytes)
                        .nextPathIndex(-1)
                        .build()))
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(DUMMY_TSS_SIG))
                        .build())
                .build();
    }

    /**
     * Returns a minimal {@link ClprLedgerConfiguration} for testing.
     *
     * @param chainId CAIP-2 chain identifier (e.g., "hiero:localnet")
     */
    public static ClprLedgerConfiguration testLedgerConfig(final String chainId) {
        return ClprLedgerConfiguration.newBuilder()
                .chainId(chainId)
                .protocolVersion(1)
                .serviceAddress(Bytes.wrap(new byte[] {0x01, 0x02, 0x03}))
                .build();
    }

    /**
     * Builds a synthetic empty-bundle {@link ClprChannel} reflecting a freshly opened channel.
     *
     * <p>The channel state contributes the {@code ClprQueueMetadata} fields the verifier
     * reconstructs from the proven channel leaf:
     * <ul>
     *   <li>{@code next_message_id = 1} (matches {@code ClprCompleteChannelHandler} init)</li>
     *   <li>both running hashes initialised to 32 zero bytes</li>
     *   <li>{@code status = ACTIVE}</li>
     * </ul>
     */
    public static ClprChannel emptyChannel(final Bytes channelId) {
        final var emptyHash = Bytes.wrap(new byte[32]);
        return ClprChannel.newBuilder()
                .channelId(channelId)
                .nextMessageId(1L)
                .receivedMessageId(0L)
                .sentRunningHash(emptyHash)
                .receivedRunningHash(emptyHash)
                .status(ClprChannelStatus.ACTIVE)
                .build();
    }

    /**
     * Computes the CLPR ownership commitment: {@code keccak256(channelId || publicKey)}. Used both
     * for the channel ownership commit and (with {@code connectorId} substituted for
     * {@code channelId}) for the connector commit phase.
     *
     * @param channelId 32-byte identifier (channel id or connector id depending on caller)
     * @param publicKey    64-byte uncompressed secp256k1 public key (without 0x04 prefix)
     */
    public static Bytes computeCommitment(final Bytes channelId, final Bytes publicKey) {
        final var payload = new byte[(int) (channelId.length() + publicKey.length())];
        channelId.getBytes(0, payload, 0, (int) channelId.length());
        publicKey.getBytes(0, payload, (int) channelId.length(), (int) publicKey.length());
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(payload));
    }

    /**
     * Derives the connector id: {@code keccak256(channelId || pubKey || salt)}.
     */
    public static Bytes deriveConnectorId(final Bytes channelId, final Bytes pubKey, final Bytes salt) {
        final var payload = new byte[(int) (channelId.length() + pubKey.length() + salt.length())];
        channelId.getBytes(0, payload, 0, (int) channelId.length());
        pubKey.getBytes(0, payload, (int) channelId.length(), (int) pubKey.length());
        salt.getBytes(0, payload, (int) (channelId.length() + pubKey.length()), (int) salt.length());
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(payload));
    }

    /**
     * Signs the connector identity message {@code keccak256(connectorId || 0x16e_address_bytes)} with
     * the given secp256k1 secret key. The CLPR service uses this signature in
     * {@code ClprCompleteConnectorHandler} to verify ownership of the connector public key.
     *
     * @return 64-byte compact recoverable ECDSA signature
     */
    public static byte[] signConnectorMessage(final byte[] secKey, final Bytes connectorId) {
        final var preimage = new byte[(int) connectorId.length() + CLPR_SERVICE_ADDRESS_20.length];
        connectorId.getBytes(0, preimage, 0, (int) connectorId.length());
        System.arraycopy(
                CLPR_SERVICE_ADDRESS_20, 0, preimage, (int) connectorId.length(), CLPR_SERVICE_ADDRESS_20.length);
        final var msgHash =
                MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage)).toByteArray();
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        final var signResult = LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, msgHash, secKey, null, null);
        if (signResult != 1) {
            throw new IllegalStateException("secp256k1_ecdsa_sign_recoverable failed: " + signResult);
        }
        final var compactSigBuffer = ByteBuffer.allocate(64);
        final var recId = new com.sun.jna.ptr.IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compactSigBuffer, recId, recoverableSig);
        return compactSigBuffer.array();
    }

    /**
     * Derives an uncompressed secp256k1 public key (64 bytes, without the 0x04 prefix) from a
     * 32-byte secret key.
     */
    public static byte[] deriveEcdsaPublicKey(final byte[] secKey) {
        final var nativePubKey = new LibSecp256k1.secp256k1_pubkey();
        final var result = LibSecp256k1.secp256k1_ec_pubkey_create(LibSecp256k1.CONTEXT, nativePubKey, secKey);
        if (result != 1) {
            throw new IllegalStateException("secp256k1_ec_pubkey_create failed: " + result);
        }
        final var outputBuffer = ByteBuffer.allocate(65);
        final var outputLength = new com.sun.jna.ptr.LongByReference(65);
        LibSecp256k1.secp256k1_ec_pubkey_serialize(
                LibSecp256k1.CONTEXT, outputBuffer, outputLength, nativePubKey, LibSecp256k1.SECP256K1_EC_UNCOMPRESSED);
        final var rawKey = new byte[64];
        outputBuffer.position(1); // skip 0x04 prefix
        outputBuffer.get(rawKey);
        return rawKey;
    }

    /**
     * Signs {@code keccak256(channelId.toByteArray())} with the given secp256k1 secret key.
     *
     * @return 64-byte compact recoverable ECDSA signature
     */
    public static byte[] signChannelId(final byte[] secKey, final Bytes channelId) {
        final var messageHash = MiscCryptoUtils.keccak256DigestOf(channelId.toByteArray());
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        final var signResult = LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, messageHash, secKey, null, null);
        if (signResult != 1) {
            throw new IllegalStateException("secp256k1_ecdsa_sign_recoverable failed: " + signResult);
        }
        final var compactSigBuffer = ByteBuffer.allocate(64);
        final var recId = new com.sun.jna.ptr.IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compactSigBuffer, recId, recoverableSig);
        return compactSigBuffer.array();
    }
}
