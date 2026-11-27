// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr.canton;

import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.ContractID;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.hiero.base.crypto.Ed25519;

/**
 * Self-contained generator for the built-in Canton verifier's ({@code 0x173}) config and bundle proofs, used by the
 * CLPR HAPI tests that open a Canton channel and deliver a Canton bundle.
 *
 * <p>The bytes are re-derived here independently of the production decoder, so the node's Canton verifier is
 * exercised as a black box. The layout follows the verifier's unit-test builders: the verdict typed content is a real
 * Canton verdict whose root hash is replaced (same length) by the root of a constructed transaction tree, signed by a
 * deterministic Ed25519 mediator that forms a 1-of-1 mediator set. The config proof's transaction creates the
 * {@code ChannelConfig} for the channel; the bundle proof's transaction writes the next {@code Channel} version, which
 * carries the queue metadata after one sent message. The bundle verdict carries the config verdict's request id plus
 * one, since the channel's trust anchor rejects a request id that is not above the last one it saw.
 *
 * <p>A REPLY bundle ({@link #replyBundleProof}) differs in two ways the verifier requires of replies: the write's view
 * is also confirmed by the channel's endpoint party, and the bundle carries exactly the write's own messages. Its
 * channel version also records the Hiero messages the Canton side has received, which the REPLY acknowledges.
 */
public final class CantonProofs {
    private static final HexFormat HEX = HexFormat.of();

    /** The built-in Canton verifier system contract (contract num 0x173). */
    public static final ContractID CANTON_VERIFIER =
            ContractID.newBuilder().setContractNum(0x173L).build();

    /** The peer (Canton) chain id that the config proof declares. */
    public static final String PEER_CHAIN = "canton:localnet";

    private static final byte[] PACKAGE_ID = repeat((byte) 0x5a, 32);
    private static final byte[] OPERATOR = utf8("operator::1220" + "ab".repeat(32));
    private static final byte[] SERVICE_ADDRESS = concat(PACKAGE_ID, OPERATOR);
    /** The Canton app party; every DATA message's sender must be this party. */
    public static final String APP = "app::1220" + "cd".repeat(32);

    private static final String ENDPOINTS = "endpoints::1220" + "ee".repeat(32);
    private static final byte[] SUBMITTER = sha256(utf8("submitter-metadata"));
    private static final byte[] PARTICIPANT = sha256(utf8("participant-metadata"));
    private static final int GROUP = 7;
    private static final byte[] OWNER_SET = repeat((byte) 0x77, 40);
    private static final long NANOS = 1_700_000_000_123_456_789L;
    private static final long[] THROTTLES = {50, 1_000_000, 5_000_000, 100, 10_000_000, 20, 30};
    private static final int VERSION_V30 = 30;
    private static final int VERSION_V31 = 31;

    /** A real Canton verdict (protocol v35); its root hash and request id are replaced per proof. */
    private static final byte[] TYPED = HEX.parseHex("0a96011a93010a8e010a59676c6f62616c2d646f6d61696e3a3a3132"
            + "3230633664636230636237636431653634333861313165653730663761613633643462336332663335336131656137"
            + "323534633437316431303565336564333364343a3a33352d30100118888892eac3a7970322221220aec33071bc09ce"
            + "aa6e4994806d6a0f8410788709e1cc8fd08049ff97b28fd2fe2a020a00101e101e");

    private static final byte[] MEDIATOR_SEED = repeat((byte) 9, Ed25519.SEED_BYTES);
    private static final byte[] MEDIATOR_PUB = new byte[Ed25519.PUBLIC_KEY_BYTES];
    private static final byte[] MEDIATOR_SECRET = new byte[Ed25519.SECRET_KEY_BYTES];

    static {
        Ed25519.newKeypair(MEDIATOR_PUB, MEDIATOR_SECRET, MEDIATOR_SEED);
    }

    private static final byte[] MEDIATOR_FP = sha256(MEDIATOR_PUB);
    private static final byte[] MEDIATOR_SET = mediatorSet();

    private CantonProofs() {}

    /** The peer service address: {@code packageId(32) || UTF-8(operator party)}. */
    public static byte[] serviceAddress() {
        return SERVICE_ADDRESS.clone();
    }

    /**
     * A config proof whose {@code ChannelConfig} names {@code channelId} and this ledger's {@code localChainId}.
     *
     * @param channelId the 32-byte channel id
     * @param localChainId the chain id of the ledger that verifies the proof
     * @return the RLP config proof
     */
    public static byte[] configProof(final byte[] channelId, final String localChainId) {
        final byte[] ex = new Pb()
                .field(2, utf8("CreateChannel"))
                .field(10, utf8(HEX.formatHex(PACKAGE_ID) + ":Clpr.Service:ClprApp"))
                .build();
        final byte[] vpd = new Pb()
                .field(3, lenField(1, channelConfig(channelId, localChainId)))
                .field(6, lenField(2, ex))
                .build();
        final Tree tree = tree(vpd, APP);
        final byte[][] throttles = new byte[THROTTLES.length][];
        for (int i = 0; i < THROTTLES.length; ++i) {
            throttles[i] = rlpString(uintBytes(THROTTLES[i]));
        }
        final byte[][] descriptor = new byte[10][];
        descriptor[0] = rlpString(utf8(synchronizerId()));
        descriptor[1] = rlpString(uintBytes(GROUP));
        descriptor[2] = rlpString(OWNER_SET);
        descriptor[3] = rlpString(MEDIATOR_SET);
        for (int i = 4; i < descriptor.length; ++i) {
            descriptor[i] = new byte[] {(byte) 0x80};
        }
        return rlpList(
                rlpString(SERVICE_ADDRESS),
                rlpString(utf8(PEER_CHAIN)),
                rlpString(uintBytes(NANOS)),
                rlpList(throttles),
                rlpList(descriptor),
                signedVerdict(withRoot(TYPED, tree.root())),
                rlpList(tree.opening(), rlpString(new byte[0])));
    }

    /**
     * A DATA payload from the Canton app, to an unknown (all-zero) connector and target.
     *
     * @return the payload
     */
    public static ClprMessagePayload probePayload() {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(Bytes.wrap(new byte[32]))
                        .targetApplication(Bytes.wrap(new byte[20]))
                        .sender(Bytes.wrap(utf8(APP)))
                        .messageData(Bytes.wrap(utf8("probe"))))
                .build();
    }

    /**
     * A REPLY from the Canton app to this ledger's outbound message {@code messageId}.
     *
     * @param messageId the id of the outbound DATA message being answered
     * @param status the reply status; the Canton verifier accepts only {@code SUCCESS} and {@code APPLICATION_ERROR}
     * @param data the reply data
     * @return the payload
     */
    public static ClprMessagePayload replyPayload(
            final long messageId, final ClprMessageReplyStatus status, final byte[] data) {
        return ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(messageId)
                        .status(status)
                        .messageReplyData(Bytes.wrap(data)))
                .build();
    }

    /**
     * A bundle proof delivering {@code payload} as message 1 on a fresh channel: the written channel version has
     * {@code nextMessageId = 2}, {@code writeFirstMessageId = 1}, nothing received, and the running hash of the one
     * message from 32 zero bytes. Its verdict's request id is the config verdict's plus one.
     *
     * @param channelId the 32-byte channel id
     * @param payload the one message
     * @return the RLP bundle proof
     */
    public static byte[] singleMessageBundleProof(final byte[] channelId, final ClprMessagePayload payload) {
        return oneMessageBundleProof(channelId, payload, 0, APP);
    }

    /**
     * A bundle proof delivering the REPLY {@code reply} as message 1 on a fresh channel, as the one message of a write
     * confirmed by both the app and the endpoint party. The written channel version records that the Canton side has
     * received this ledger's messages up to {@code receivedMessageId}; its received running hash is 32 zero bytes,
     * which this ledger does not check against its own sent hash. Its verdict's request id is the config verdict's plus
     * one.
     *
     * @param channelId the 32-byte channel id
     * @param reply the REPLY payload
     * @param receivedMessageId the last of this ledger's messages that the Canton side has received
     * @return the RLP bundle proof
     */
    public static byte[] replyBundleProof(
            final byte[] channelId, final ClprMessagePayload reply, final long receivedMessageId) {
        return oneMessageBundleProof(channelId, reply, receivedMessageId, APP, ENDPOINTS);
    }

    private static byte[] oneMessageBundleProof(
            final byte[] channelId,
            final ClprMessagePayload payload,
            final long receivedMessageId,
            final String... confirmers) {
        final byte[] sentRunningHash = ClprHashUtils.computeRunningHash(Bytes.wrap(new byte[32]), payload)
                .toByteArray();
        final byte[] ex = new Pb()
                .field(2, utf8("SendMessage"))
                .field(10, utf8("template"))
                .build();
        final byte[] vpd = new Pb()
                .field(3, lenField(1, channel(channelId, 2, 1, sentRunningHash, receivedMessageId)))
                .field(6, lenField(2, ex))
                .build();
        final Tree tree = tree(vpd, confirmers);
        final byte[] typed = withNextRequestId(withRoot(TYPED, tree.root()));
        final byte[] payloadBytes = ClprMessagePayload.PROTOBUF.toBytes(payload).toByteArray();
        return rlpList(
                signedVerdict(typed),
                tree.opening(),
                rlpString(new byte[0]),
                rlpString(MEDIATOR_SET),
                new byte[] {(byte) 0x80},
                rlpString(new byte[0]),
                rlpList(rlpString(payloadBytes)));
    }

    // ── The ChannelConfig and Channel contracts ───────────────────────────

    /** {@code ChannelConfig [operator, channelId, peerChainId, configTimeNanos, throttles, manifestHash, app, endpoints]}. */
    private static byte[] channelConfig(final byte[] channelId, final String localChainId) {
        final Pb throttles = new Pb();
        for (final long t : THROTTLES) {
            throttles.field(1, lenField(1, int64(t)));
        }
        final byte[][] values = {
            lenField(7, OPERATOR),
            text(HEX.formatHex(channelId)),
            text(localChainId),
            int64(NANOS),
            lenField(13, throttles.build()),
            text(""),
            lenField(7, utf8(APP)),
            lenField(7, utf8(ENDPOINTS)),
        };
        return contract("ChannelConfig", values);
    }

    /** The {@code Channel} contract: the fields the verifier reads in their positions, the rest empty text. */
    private static byte[] channel(
            final byte[] channelId,
            final long nextMessageId,
            final long writeFirst,
            final byte[] sentRunningHash,
            final long receivedMessageId) {
        final List<byte[]> values = new ArrayList<>();
        for (int i = 0; i < 29; ++i) {
            values.add(text(""));
        }
        values.set(0, lenField(7, OPERATOR));
        values.set(1, lenField(7, utf8(APP)));
        values.set(2, text(HEX.formatHex(channelId)));
        values.set(5, int64(nextMessageId));
        values.set(6, text(HEX.formatHex(sentRunningHash)));
        values.set(10, int64(0));
        values.set(14, int64(receivedMessageId));
        values.set(17, lenField(15, lenField(1, utf8("Active"))));
        values.set(24, text("00".repeat(32)));
        values.set(25, text(""));
        values.set(28, int64(writeFirst));
        return contract("Channel", values.toArray(byte[][]::new));
    }

    private static byte[] contract(final String entity, final byte[][] values) {
        final Pb record = new Pb();
        for (final byte[] v : values) {
            record.field(1, lenField(1, v));
        }
        final byte[] templateId = concat(
                lenField(1, utf8(HEX.formatHex(PACKAGE_ID))),
                lenField(2, utf8("Clpr")),
                lenField(2, utf8("Service")),
                lenField(3, utf8(entity)));
        final byte[] fat = new Pb()
                .field(3, templateId)
                .field(4, lenField(13, record.build()))
                .field(5, utf8(APP))
                .build();
        return lenField(2, fat);
    }

    private static byte[] text(final String s) {
        return lenField(8, utf8(s));
    }

    private static byte[] int64(final long v) {
        return new Pb().varint(3, v << 1).build();
    }

    // ── The transaction tree and verdict ──────────────────────────────────

    private record Tree(byte[] opening, byte[] root) {}

    /**
     * A root view whose participant data is {@code vpd}, confirmed by {@code confirmers}: each is an informee of
     * weight 1 in one quorum whose threshold is their number, so each is a required confirmer.
     */
    private static Tree tree(final byte[] vpd, final String... confirmers) {
        final Pb quorum = new Pb();
        final Pb vcdBuilder = new Pb();
        for (int i = 0; i < confirmers.length; ++i) {
            quorum.field(1, new Pb().varint(1, i).varint(2, 1).build());
            vcdBuilder.field(2, utf8(confirmers[i]));
        }
        quorum.varint(2, confirmers.length);
        final byte[] vcd = vcdBuilder.field(3, quorum.build()).build();
        final byte[] cm = concat(
                lenField(1, lenField(2, sha256(utf8("cm-salt")))),
                lenField(3, utf8(synchronizerId())),
                lenField(4, utf8("uuid-0000")),
                new Pb().varint(5, GROUP).build());
        final byte[] view = inner(leafHash(8, vcd, VERSION_V30), leafHash(9, vpd, VERSION_V31));
        final byte[] root = inner(SUBMITTER, leafHash(6, cm, VERSION_V30), PARTICIPANT, inner(view));
        final byte[] opening = rlpList(
                rlpString(SUBMITTER),
                leafRlp(cm, VERSION_V30),
                rlpString(PARTICIPANT),
                rlpList(leafRlp(vcd, VERSION_V30), leafRlp(vpd, VERSION_V31)),
                rlpList(rlpList(rlpList(), rlpList())));
        return new Tree(opening, root);
    }

    private static byte[] signedVerdict(final byte[] typed) {
        final byte[] sig = new byte[Ed25519.SIGNATURE_BYTES];
        Ed25519.sign(sig, verdictSigningInput(typed), MEDIATOR_SECRET);
        final byte[] entry = rlpList(rlpString(MEDIATOR_FP), rlpString(sig), new byte[] {(byte) 0x80});
        return rlpList(rlpString(typed), rlpList(entry));
    }

    /** Packed mediator set: one Ed25519 member with one current key, threshold 1. */
    private static byte[] mediatorSet() {
        final var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 0, 0, 1});
        out.writeBytes(new byte[] {0, 0, 0, 1});
        out.writeBytes(new byte[] {0, 0, 0, 1});
        final byte[] id = new byte[32];
        id[0] = 1;
        out.writeBytes(id);
        out.writeBytes(new byte[] {0, 0, 0, 1});
        out.write(0); // delegations
        out.write(1); // current keys
        out.writeBytes(MEDIATOR_FP);
        out.write(1); // Ed25519
        out.write(0);
        out.writeBytes(MEDIATOR_PUB);
        out.writeBytes(new byte[32]);
        out.write(0); // previous keys
        return out.toByteArray();
    }

    // ── The verdict typed content: {1: {3: {1: {1: sync, 3: requestId, 4: multihash(root)}}}} ──

    private record Field(int number, int wireType, int start, int end) {}

    private static Field verdictBody(final byte[] typed) {
        final Field outer = find(typed, 0, typed.length, 1);
        final Field middle = find(typed, outer.start(), outer.end(), 3);
        return find(typed, middle.start(), middle.end(), 1);
    }

    private static String synchronizerId() {
        final Field body = verdictBody(TYPED);
        final Field sync = find(TYPED, body.start(), body.end(), 1);
        return new String(Arrays.copyOfRange(TYPED, sync.start(), sync.end()), StandardCharsets.UTF_8);
    }

    private static byte[] withRoot(final byte[] typed, final byte[] root) {
        final Field body = verdictBody(typed);
        final Field multihash = find(typed, body.start(), body.end(), 4);
        if (multihash.end() - multihash.start() != 2 + root.length) {
            throw new IllegalStateException("verdict root is not a 32-byte multihash");
        }
        final byte[] out = typed.clone();
        System.arraycopy(root, 0, out, multihash.start() + 2, root.length);
        return out;
    }

    private static byte[] withNextRequestId(final byte[] typed) {
        final Field body = verdictBody(typed);
        final Field requestId = find(typed, body.start(), body.end(), 3);
        final long[] decoded = readVarint(typed, requestId.start());
        final byte[] next = varint(decoded[0] + 1);
        if (next.length != requestId.end() - requestId.start()) {
            throw new IllegalStateException("next request id changes the varint length");
        }
        final byte[] out = typed.clone();
        System.arraycopy(next, 0, out, requestId.start(), next.length);
        return out;
    }

    private static Field find(final byte[] b, final int from, final int to, final int number) {
        int i = from;
        while (i < to) {
            final long[] key = readVarint(b, i);
            i = (int) key[1];
            final int wireType = (int) (key[0] & 7);
            final int fieldNumber = (int) (key[0] >>> 3);
            final int start;
            final int end;
            switch (wireType) {
                case 0 -> {
                    start = i;
                    end = (int) readVarint(b, i)[1];
                }
                case 2 -> {
                    final long[] len = readVarint(b, i);
                    start = (int) len[1];
                    end = start + (int) len[0];
                }
                default -> throw new IllegalStateException("unexpected wire type " + wireType);
            }
            if (fieldNumber == number) {
                return new Field(fieldNumber, wireType, start, end);
            }
            i = end;
        }
        throw new IllegalStateException("field " + number + " not found");
    }

    /** Returns {@code {value, nextOffset}}. */
    private static long[] readVarint(final byte[] b, final int from) {
        long v = 0;
        int shift = 0;
        int i = from;
        while (true) {
            final int x = b[i++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                return new long[] {v, i};
            }
            shift += 7;
        }
    }

    // ── Merkle hashing (Canton's sha256 tree) ─────────────────────────────

    private static byte[] leafHash(final int purpose, final byte[] data, final int version) {
        return leaf(purpose, sha256(data), versioned(data, version));
    }

    private static byte[] leafRlp(final byte[] data, final int version) {
        return rlpList(rlpString(sha256(data)), rlpString(versioned(data, version)));
    }

    private static byte[] leaf(final int purpose, final byte[] salt, final byte[] data) {
        return sha256(concat(be32(purpose), be32(salt.length), salt, be32(data.length), data));
    }

    private static byte[] inner(final byte[]... children) {
        final var out = new ByteArrayOutputStream();
        out.writeBytes(be32(3));
        out.writeBytes(be32(children.length));
        for (final byte[] child : children) {
            out.write(0x12);
            out.write(0x20);
            out.writeBytes(child);
        }
        return sha256(out.toByteArray());
    }

    private static byte[] verdictSigningInput(final byte[] typed) {
        return concat(new byte[] {0x12, 0x20}, sha256(concat(be32(38), typed)));
    }

    /** A {@code Versioned{1: data, 2: version}} wrapper. */
    private static byte[] versioned(final byte[] data, final int version) {
        return concat(lenField(1, data), new Pb().varint(2, version).build());
    }

    // ── RLP and protobuf encoding ─────────────────────────────────────────

    private static byte[] rlpString(final byte[] b) {
        if (b.length == 1 && (b[0] & 0xff) < 0x80) {
            return b.clone();
        }
        return concat(rlpHeader(0x80, b.length), b);
    }

    private static byte[] rlpList(final byte[]... items) {
        final byte[] payload = concat(items);
        return concat(rlpHeader(0xc0, payload.length), payload);
    }

    private static byte[] rlpHeader(final int base, final int len) {
        if (len <= 55) {
            return new byte[] {(byte) (base + len)};
        }
        int n = 4;
        while (n > 1 && (len >>> (8 * (n - 1))) == 0) {
            --n;
        }
        final byte[] lenBytes = new byte[n];
        for (int i = 0; i < n; ++i) {
            lenBytes[i] = (byte) (len >>> (8 * (n - 1 - i)));
        }
        return concat(new byte[] {(byte) (base + 55 + n)}, lenBytes);
    }

    /** Minimal big-endian unsigned integer bytes, empty for zero (RLP integer encoding). */
    private static byte[] uintBytes(final long v) {
        final byte[] raw = BigInteger.valueOf(v).toByteArray();
        int i = 0;
        while (i < raw.length && raw[i] == 0) {
            ++i;
        }
        return Arrays.copyOfRange(raw, i, raw.length);
    }

    private static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            out.writeBytes(lenField(number, value));
            return this;
        }

        Pb varint(final int number, final long value) {
            out.writeBytes(CantonProofs.varint((long) number << 3));
            out.writeBytes(CantonProofs.varint(value));
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }
    }

    private static byte[] lenField(final long number, final byte[] value) {
        return concat(varint((number << 3) | 2), varint(value.length), value);
    }

    private static byte[] varint(long v) {
        final var o = new ByteArrayOutputStream();
        while (true) {
            final int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v != 0) {
                o.write(b | 0x80);
            } else {
                o.write(b);
                return o.toByteArray();
            }
        }
    }

    // ── Bytes ─────────────────────────────────────────────────────────────

    private static byte[] be32(final int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }

    private static byte[] concat(final byte[]... parts) {
        final var out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
