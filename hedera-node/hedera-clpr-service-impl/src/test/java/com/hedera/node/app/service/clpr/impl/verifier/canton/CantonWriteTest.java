// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier.OutboundLookup;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier.Service;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier.WriteResult;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for the write/contract/payload reading of {@link CantonProofVerifier} ({@code _readWrite},
 * {@code _metadata}, {@code _created}, {@code _openContract}, {@code _status}, {@code _checkPayload}).
 *
 * <p><b>Provenance.</b> No real Canton {@code SendMessage}/{@code WriteReplies} view exists in the repository's
 * fixtures (the only real transaction is a {@code WalletAppInstall} create), so every vector here is CONSTRUCTED:
 * real protobuf / Daml-LF wire format assembled with {@link Pb} in the exact shape that {@link CantonProto} reads
 * ({@code ViewParticipantData{3: CreatedContract{1: Versioned{2: FatContractInstance{3: template_id,
 * 4: create_arg, 5: signatories}}}, 6: ActionDescription, 7/8: rollback}}, the same contract layout as the real
 * {@code created-event-blob}). The {@code Channel} record carries the nine fields the verifier reads at the
 * documented positions (the rest are filler). Message payloads are REAL {@code ClprMessagePayload} protobuf
 * encoded by PBJ.
 */
class CantonWriteTest {
    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] PACKAGE_ID = repeat((byte) 0x5a, 32);
    private static final byte[] PACKAGE_HEX = HEX.formatHex(PACKAGE_ID).getBytes(StandardCharsets.UTF_8);
    private static final byte[] OPERATOR = utf8("operator::1220" + "ab".repeat(32));
    private static final byte[] APP = utf8("app::1220" + "cd".repeat(32));
    private static final byte[] OTHER_PARTY = utf8("other::1220" + "ef".repeat(32));
    private static final byte[] CHANNEL_ID = repeat((byte) 0x11, 32);
    private static final byte[] CHANNEL_HEX = HEX.formatHex(CHANNEL_ID).getBytes(StandardCharsets.UTF_8);
    private static final byte[] SENT_HASH = repeat((byte) 0x22, 32);
    private static final byte[] RECV_HASH = repeat((byte) 0x33, 32);
    private static final byte[] MANIFEST_HASH = repeat((byte) 0x44, 32);

    private static final Service SERVICE =
            new Service(CHANNEL_ID, CHANNEL_HEX, PACKAGE_HEX, OPERATOR, concat(PACKAGE_ID, OPERATOR));

    // ── readWrite: success ───────────────────────────────────────────────────

    @Test
    void readWriteV31ExtractsMetadataAtDocumentedPositions() {
        final byte[] vpd = vpd(false, exercise(), false, channel().build());
        final WriteResult r = CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP);

        assertEquals(7, r.metadata().nextMessageId());
        assertArrayEquals(SENT_HASH, r.metadata().sentRunningHash().toByteArray());
        assertEquals(4, r.metadata().receivedMessageId());
        assertArrayEquals(RECV_HASH, r.metadata().receivedRunningHash().toByteArray());
        assertEquals(ClprChannelStatus.ACTIVE, r.metadata().status());
        assertEquals(3, r.metadata().endpointManifestVersion());
        assertEquals(0, r.manifestHash().length);
    }

    @Test
    void readWriteV32AndManifestHash() {
        final byte[] vpd = vpd(
                true,
                exercise(),
                false,
                channel().manifestHash(HEX.formatHex(MANIFEST_HASH)).build());
        final WriteResult r = CantonProofVerifier.readWrite(vpd, true, 0, SERVICE, APP);
        assertArrayEquals(MANIFEST_HASH, r.manifestHash());
        assertEquals(7, r.metadata().nextMessageId());
    }

    @Test
    void readWriteSelectsCreatedIndex() {
        final byte[] other = channel().next(99).build();
        final byte[] vpd = vpd(false, exercise(), false, other, channel().build());
        assertEquals(
                99,
                CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP)
                        .metadata()
                        .nextMessageId());
        assertEquals(
                7,
                CantonProofVerifier.readWrite(vpd, false, 1, SERVICE, APP)
                        .metadata()
                        .nextMessageId());
    }

    @Test
    void statusConstructorsMapToClprChannelStatus() {
        final String[] names = {"Active", "Closing", "Drained", "Closed", "Paused", "Pending"};
        final ClprChannelStatus[] expected = {
            ClprChannelStatus.ACTIVE,
            ClprChannelStatus.CLOSING,
            ClprChannelStatus.DRAINED,
            ClprChannelStatus.CLOSED,
            ClprChannelStatus.PAUSED,
            ClprChannelStatus.PENDING
        };
        for (int i = 0; i < names.length; ++i) {
            final byte[] vpd =
                    vpd(false, exercise(), false, channel().status(names[i]).build());
            assertEquals(
                    expected[i],
                    CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP)
                            .metadata()
                            .status(),
                    names[i]);
        }
    }

    // ── readWrite: rejections ────────────────────────────────────────────────

    @Test
    void createActionIsWrongAction() {
        final byte[] vpd =
                vpd(false, Pb.lenField(1, new byte[] {1, 2}), false, channel().build());
        assertRejects("WrongAction", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void rolledBackV31IsRolledBack() {
        final byte[] vpd = vpd(false, exercise(), true, channel().build());
        assertRejects("RolledBack", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void rolledBackV32IsRolledBack() {
        final byte[] vpd = vpd(true, exercise(), true, channel().build());
        assertRejects("RolledBack", () -> CantonProofVerifier.readWrite(vpd, true, 0, SERVICE, APP));
    }

    @Test
    void createdIndexOutOfRangeIsWrongContract() {
        final byte[] vpd = vpd(false, exercise(), false, channel().build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 1, SERVICE, APP));
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, -1, SERVICE, APP));
    }

    @Test
    void noCreatedContractIsWrongContract() {
        final byte[] vpd = vpd(false, exercise(), false);
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void wrongPackageIsWrongContract() {
        final byte[] vpd = vpd(
                false,
                exercise(),
                false,
                channel().packageHex(repeat((byte) 'f', 64)).build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void wrongEntityIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().entity("ChannelConfig").build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void wrongOperatorIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().operator(OTHER_PARTY).build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void wrongChannelIdIsWrongContract() {
        final byte[] vpd = vpd(
                false,
                exercise(),
                false,
                channel().channelHex(utf8("22".repeat(32))).build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void appFieldNotAnchorAppIsWrongContract() {
        final byte[] vpd = vpd(
                false,
                exercise(),
                false,
                channel().app(OTHER_PARTY).signatory(OTHER_PARTY).build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void appNotASignatoryIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().signatory(OPERATOR).build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void tooFewFieldsIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().fieldCount(25).build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void unknownStatusIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().status("Frozen").build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    @Test
    void runningHashOfWrongLengthIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().sentHash("2222").build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
        final byte[] vpd2 = vpd(false, exercise(), false, channel().recvHash("").build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd2, false, 0, SERVICE, APP));
    }

    @Test
    void manifestHashOfWrongLengthIsWrongContract() {
        final byte[] vpd =
                vpd(false, exercise(), false, channel().manifestHash("4444").build());
        assertRejects("WrongContract", () -> CantonProofVerifier.readWrite(vpd, false, 0, SERVICE, APP));
    }

    // ── checkPayload ─────────────────────────────────────────────────────────

    @Test
    void dataFromAppPasses() {
        final byte[] p = data(APP, OTHER_PARTY);
        CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, neverCalled());
    }

    @Test
    void dataFromOtherSenderIsSenderMismatch() {
        final byte[] p = data(OTHER_PARTY, APP);
        assertRejects("SenderMismatch", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, neverCalled()));
    }

    @Test
    void applicationErrorReplyWithoutDataSkipsTargetLookup() {
        final byte[] p = reply(5, ClprMessageReplyStatus.APPLICATION_ERROR, new byte[0]);
        CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, neverCalled());
    }

    @Test
    void replyToDataSentToAppPasses() {
        final byte[] p = reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1});
        final var asked = new AtomicInteger();
        final OutboundLookup lookup = (channelId, messageId) -> {
            asked.incrementAndGet();
            assertArrayEquals(CHANNEL_ID, channelId);
            assertEquals(5, messageId);
            return dataPayload(OTHER_PARTY, APP);
        };
        CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, lookup);
        assertEquals(1, asked.get());
    }

    @Test
    void replyToDataSentToAnotherPartyIsReplyTargetMismatch() {
        final byte[] p = reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1});
        final OutboundLookup lookup = (channelId, messageId) -> dataPayload(APP, OTHER_PARTY);
        assertRejects("ReplyTargetMismatch", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, lookup));
    }

    @Test
    void applicationErrorWithDataIsStillTargetChecked() {
        final byte[] p = reply(5, ClprMessageReplyStatus.APPLICATION_ERROR, new byte[] {9});
        final OutboundLookup lookup = (channelId, messageId) -> dataPayload(APP, OTHER_PARTY);
        assertRejects("ReplyTargetMismatch", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, lookup));
    }

    @Test
    void nonApplicationErrorWithoutDataIsTargetChecked() {
        final byte[] p = reply(5, ClprMessageReplyStatus.SUCCESS, new byte[0]);
        final OutboundLookup lookup = (channelId, messageId) -> dataPayload(APP, OTHER_PARTY);
        assertRejects("ReplyTargetMismatch", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, lookup));
    }

    @Test
    void replyWithNoLiveOutboundMessageOrNonDataPasses() {
        final byte[] p = reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1});
        CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, (c, m) -> null);
        final byte[] storedReply = ClprMessagePayload.PROTOBUF
                .toBytes(ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder().messageId(5))
                        .build())
                .toByteArray();
        assertNotNull(storedReply);
        CantonProofVerifier.checkPayload(
                p,
                CHANNEL_ID,
                APP,
                (c, m) -> ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder().messageId(5))
                        .build());
    }

    @Test
    void lookupFailureSurfacesAsServiceCallFailed() {
        final byte[] p = reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1});
        final OutboundLookup lookup = (c, m) -> {
            throw CantonProofException.serviceCallFailed();
        };
        assertRejects("ServiceCallFailed", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, lookup));
    }

    @Test
    void controlPayloadIsWrongMessageType() {
        final byte[] p = ClprMessagePayload.PROTOBUF
                .toBytes(ClprMessagePayload.newBuilder()
                        .control(ClprControlMessage.DEFAULT)
                        .build())
                .toByteArray();
        assertRejects("WrongMessageType", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, neverCalled()));
    }

    @Test
    void trailingFieldAfterPayloadIsWrongMessageType() {
        // A valid DATA from the app followed by a second oneof arm: PBJ would keep the last arm, the reference
        // rejects anything but exactly one field spanning the whole payload.
        final byte[] p = concat(data(APP, OTHER_PARTY), data(OTHER_PARTY, APP));
        assertRejects("WrongMessageType", () -> CantonProofVerifier.checkPayload(p, CHANNEL_ID, APP, neverCalled()));
    }

    @Test
    void unknownFieldNumberAndWireTypeAreWrongMessageType() {
        final byte[] field4 = Pb.lenField(4, new byte[] {1});
        assertRejects(
                "WrongMessageType", () -> CantonProofVerifier.checkPayload(field4, CHANNEL_ID, APP, neverCalled()));
        final byte[] varint = {0x08, 0x01}; // field 1, wire type varint
        assertRejects(
                "WrongMessageType", () -> CantonProofVerifier.checkPayload(varint, CHANNEL_ID, APP, neverCalled()));
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThatThrownBy(() -> CantonProofVerifier.checkPayload(new byte[0], CHANNEL_ID, APP, neverCalled()))
                .isInstanceOf(CantonProofException.class);
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private static OutboundLookup neverCalled() {
        return (c, m) -> fail("outbound lookup must not be called");
    }

    private static byte[] data(final byte[] sender, final byte[] target) {
        return ClprMessagePayload.PROTOBUF.toBytes(dataPayload(sender, target)).toByteArray();
    }

    private static ClprMessagePayload dataPayload(final byte[] sender, final byte[] target) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .sender(Bytes.wrap(sender))
                        .targetApplication(Bytes.wrap(target))
                        .messageData(Bytes.wrap(new byte[] {7})))
                .build();
    }

    private static byte[] reply(final long id, final ClprMessageReplyStatus status, final byte[] replyData) {
        return ClprMessagePayload.PROTOBUF
                .toBytes(ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder()
                                .messageId(id)
                                .status(status)
                                .messageReplyData(Bytes.wrap(replyData)))
                        .build())
                .toByteArray();
    }

    /** An {@code ActionDescription{2: ExerciseActionDescription{2: choice, 10: template_id}}}. */
    private static byte[] exercise() {
        final byte[] ex = new Pb()
                .field(2, utf8("SendMessage"))
                .field(10, utf8("template"))
                .build();
        return Pb.lenField(2, ex);
    }

    /** A view participant data leaf: created contracts, the action, and the rollback marker (v31 scope / v32 flag). */
    private static byte[] vpd(
            final boolean v32, final byte[] action, final boolean rolledBack, final byte[]... createdContracts) {
        final Pb pb = new Pb();
        for (final byte[] c : createdContracts) {
            pb.field(3, Pb.lenField(1, c));
        }
        pb.field(6, action);
        if (v32) {
            pb.varint(8, rolledBack ? 1 : 0);
        } else if (rolledBack) {
            pb.field(7, Pb.lenField(1, new byte[] {9}));
        }
        return pb.build();
    }

    private static Chan channel() {
        return new Chan();
    }

    /** Builds a {@code Versioned{2: FatContractInstance}} Channel contract with a Daml-LF create record. */
    private static final class Chan {
        private byte[] packageHex = PACKAGE_HEX;
        private String entity = "Channel";
        private byte[] operator = OPERATOR;
        private byte[] app = APP;
        private byte[] channelHex = CHANNEL_HEX;
        private byte[] signatory = APP;
        private long next = 7;
        private String sentHash = HEX.formatHex(SENT_HASH);
        private String recvHash = HEX.formatHex(RECV_HASH);
        private String manifestHash = "";
        private String status = "Active";
        private int fieldCount = 26;

        Chan packageHex(final byte[] v) {
            packageHex = v;
            return this;
        }

        Chan entity(final String v) {
            entity = v;
            return this;
        }

        Chan operator(final byte[] v) {
            operator = v;
            return this;
        }

        Chan app(final byte[] v) {
            app = v;
            return this;
        }

        Chan channelHex(final byte[] v) {
            channelHex = v;
            return this;
        }

        Chan signatory(final byte[] v) {
            signatory = v;
            return this;
        }

        Chan next(final long v) {
            next = v;
            return this;
        }

        Chan sentHash(final String v) {
            sentHash = v;
            return this;
        }

        Chan recvHash(final String v) {
            recvHash = v;
            return this;
        }

        Chan manifestHash(final String v) {
            manifestHash = v;
            return this;
        }

        Chan status(final String v) {
            status = v;
            return this;
        }

        Chan fieldCount(final int v) {
            fieldCount = v;
            return this;
        }

        byte[] build() {
            final List<byte[]> values = new ArrayList<>();
            for (int i = 0; i < 26; ++i) {
                values.add(text(""));
            }
            values.set(0, party(operator));
            values.set(1, party(app));
            values.set(2, text(channelHex));
            values.set(5, int64(next));
            values.set(6, text(utf8(sentHash)));
            values.set(10, int64(3));
            values.set(14, int64(4));
            values.set(17, enumValue(status));
            values.set(24, text(utf8(recvHash)));
            values.set(25, text(utf8(manifestHash)));
            final Pb record = new Pb();
            for (int i = 0; i < fieldCount; ++i) {
                record.field(1, Pb.lenField(1, values.get(i)));
            }
            final byte[] createArg = Pb.lenField(13, record.build());
            final byte[] templateId =
                    CantonProto.encodeIdentifier(packageHex, utf8("Clpr"), utf8("Service"), utf8(entity));
            final byte[] fat = new Pb()
                    .field(3, templateId)
                    .field(4, createArg)
                    .field(5, signatory)
                    .build();
            return Pb.lenField(2, fat);
        }

        private static byte[] party(final byte[] p) {
            return Pb.lenField(7, p);
        }

        private static byte[] text(final String s) {
            return text(utf8(s));
        }

        private static byte[] text(final byte[] s) {
            return Pb.lenField(8, s);
        }

        private static byte[] int64(final long v) {
            return new Pb().varint(3, v << 1).build(); // sint64 zig-zag of a non-negative value
        }

        private static byte[] enumValue(final String name) {
            return Pb.lenField(15, Pb.lenField(1, utf8(name)));
        }
    }

    private static void assertRejects(final String errorName, final org.junit.jupiter.api.function.Executable body) {
        assertThatThrownBy(body::execute)
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo(errorName));
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        java.util.Arrays.fill(out, b);
        return out;
    }

    private static byte[] concat(final byte[]... parts) {
        final var out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** Minimal protobuf wire-format builder. */
    private static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            out.writeBytes(lenField(number, value));
            return this;
        }

        Pb varint(final int number, final long value) {
            writeVarint(out, (long) number << 3);
            writeVarint(out, value);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }

        static byte[] lenField(final long number, final byte[] value) {
            final var o = new ByteArrayOutputStream();
            writeVarint(o, (number << 3) | CantonProto.WIRE_LEN);
            writeVarint(o, value.length);
            o.writeBytes(value);
            return o.toByteArray();
        }

        private static void writeVarint(final ByteArrayOutputStream o, long v) {
            while (true) {
                final int b = (int) (v & 0x7f);
                v >>>= 7;
                if (v != 0) {
                    o.write(b | 0x80);
                } else {
                    o.write(b);
                    return;
                }
            }
        }
    }
}
