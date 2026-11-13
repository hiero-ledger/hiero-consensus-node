// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Contract;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.ContractRange;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.ParticipantData;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Range;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.SyncAndGroup;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Verdict;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Versioned;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonProto}.
 *
 * <p><b>Provenance of vectors.</b>
 * <ul>
 *   <li><b>REAL Canton bytes:</b> {@code verdict-typed-content} (a real {@code ConfirmationResultMessage},
 *       protocol v35) anchors {@link #parseVerdictRealFixture}; {@code created-event-blob} (a real
 *       {@code FatContractInstance} with a real Daml-LF create argument) anchors the contract and Daml-LF
 *       accessor tests.</li>
 *   <li><b>CONSTRUCTED protobuf:</b> the common-metadata, view-participant-data (v31/v32), view-common-data,
 *       versioned-leaf, identifier, Daml-LF scalar, and strict-parse negative vectors are real protobuf
 *       <em>wire format</em> assembled here with {@link Pb} to exercise the reader and its strict rules. No
 *       vector uses non-protobuf magic bytes.</li>
 * </ul>
 */
class CantonProtoTest {
    private static final HexFormat HEX = HexFormat.of();

    // ── REAL fixtures ────────────────────────────────────────────────────────

    @Test
    void parseVerdictRealFixture() {
        final Verdict v = CantonProto.parseVerdict(CantonFixtures.verdictTypedContent());
        assertEquals(1, v.viewType());
        assertEquals(CantonProto.VIEW_TYPE_TRANSACTION, v.viewType());
        assertEquals(
                "global-domain::1220c6dcb0cb7cd1e6438a11ee70f7aa63d4b3c2f353a1ea7254c471d105e3ed33d4::35-0",
                new String(v.synchronizerId(), StandardCharsets.UTF_8));
        assertEquals(1791363167585288L, v.requestId());
        assertEquals("aec33071bc09ceaa6e4994806d6a0f8410788709e1cc8fd08049ff97b28fd2fe", HEX.formatHex(v.rootHash()));
        assertTrue(v.approved());
    }

    @Test
    void parseContractAndDamlLfRealFixture() {
        final byte[] blob = CantonFixtures.createdEventBlob();
        // A CreatedContract holds the contract in field 1; the real blob is itself that Versioned value.
        final byte[] contract = Pb.lenField(1, blob);
        final Contract c = CantonProto.parseContract(contract, new ContractRange(0, contract.length));

        assertEquals(1, c.signatories().size());
        assertThat(new String(sub(contract, c.signatories().getFirst()), StandardCharsets.UTF_8))
                .startsWith("app_provider_localnet-localparty-1::1220");

        // Real Daml-LF create argument: a Record with 4 fields (dsoParty, validatorParty, endUserName, endUserParty).
        final var fields = CantonProto.recordFields(contract, c.createArg());
        assertEquals(4, fields.size());
        // Field 2 is the text endUserName; fields 0/1/3 are parties.
        assertEquals(
                "app-provider",
                new String(
                        sub(contract, CantonProto.stringValue(contract, fields.get(2), CantonProto.VALUE_TEXT)),
                        StandardCharsets.UTF_8));
        assertThat(new String(
                        sub(contract, CantonProto.stringValue(contract, fields.get(0), CantonProto.VALUE_PARTY)),
                        StandardCharsets.UTF_8))
                .startsWith("DSO::1220");
    }

    // ── Verdict strictness over the real-shaped message ──────────────────────

    @Test
    void verdictNotApprovedWhenVerdictHoldsRejectArm() {
        // ConfirmationResultMessage{..., 5: Verdict{2: reject}} → approved=false (kind != 1).
        final byte[] typed = verdict(crmBuilder().field(5, Pb.msg(Pb.lenField(2, new byte[0]))));
        assertFalse(CantonProto.parseVerdict(typed).approved());
    }

    @Test
    void verdictRejectsDuplicateSingularField() {
        // Two view_type (field 2) varints: a singular field we read occurs twice → malformed.
        final byte[] typed = verdict(crmBuilder().varint(2, 1));
        assertMalformed(() -> CantonProto.parseVerdict(typed));
    }

    @Test
    void verdictRejectsWrongWireType() {
        // request_id (field 3) encoded as LEN instead of VARINT → malformed.
        final Pb crm = new Pb()
                .field(1, "sync".getBytes(StandardCharsets.UTF_8))
                .varint(2, 1)
                .field(3, new byte[] {1, 2, 3})
                .field(4, multihash())
                .field(5, Pb.msg(Pb.lenField(1, new byte[0])));
        assertMalformed(() -> CantonProto.parseVerdict(wrapCrm(crm.build())));
    }

    @Test
    void verdictRejectsVerdictOneofWithTwoArms() {
        // Verdict holder with two fields → not exactly one oneof arm → malformed.
        final byte[] twoArm = concat(Pb.lenField(1, new byte[0]), Pb.lenField(2, new byte[0]));
        final byte[] typed = verdict(crmBuilder().field(5, twoArm));
        assertMalformed(() -> CantonProto.parseVerdict(typed));
    }

    @Test
    void verdictRejectsBadMultihash() {
        final Pb crm = new Pb()
                .field(1, "sync".getBytes(StandardCharsets.UTF_8))
                .varint(2, 1)
                .varint(3, 7)
                .field(4, new byte[] {0x12, 0x20, 1, 2}) // too short
                .field(5, Pb.msg(Pb.lenField(1, new byte[0])));
        assertMalformed(() -> CantonProto.parseVerdict(wrapCrm(crm.build())));
    }

    @Test
    void verdictMissingRequiredFieldThrowsMissing() {
        // Omit synchronizer id (field 1).
        final Pb crm =
                new Pb().varint(2, 1).varint(3, 7).field(4, multihash()).field(5, Pb.msg(Pb.lenField(1, new byte[0])));
        assertThatThrownBy(() -> CantonProto.parseVerdict(wrapCrm(crm.build())))
                .isInstanceOf(CantonProofException.class)
                .satisfies(e -> assertEquals("CantonProtoMissingField", ((CantonProofException) e).errorName()));
    }

    // ── CommonMetadata ───────────────────────────────────────────────────────

    @Test
    void parseCommonMetadataConstructed() {
        final byte[] sync = "global-domain::1220abcd::35-0".getBytes(StandardCharsets.UTF_8);
        final byte[] b = new Pb().field(3, sync).varint(5, 42).build();
        final SyncAndGroup sg = CantonProto.parseCommonMetadata(b);
        assertArrayEquals(sync, sg.synchronizerId());
        assertEquals(42, sg.group());
    }

    // ── ViewParticipantData ──────────────────────────────────────────────────

    @Test
    void parseParticipantDataV31Exercise() {
        // 6: ActionDescription{2: ExerciseActionDescription{2: choice, 10: template_id}},
        // 3: two created contracts, 7: RollbackContext{1: scope} non-empty → rolledBack.
        final byte[] exercise = new Pb()
                .field(2, "Transfer".getBytes(StandardCharsets.UTF_8))
                .field(10, "tmpl".getBytes(StandardCharsets.UTF_8))
                .build();
        final byte[] vpd = new Pb()
                .field(3, new byte[] {1})
                .field(3, new byte[] {2})
                .field(6, Pb.msg(Pb.lenField(2, exercise)))
                .field(7, Pb.msg(Pb.lenField(1, new byte[] {9}))) // non-empty scope
                .build();
        final ParticipantData p = CantonProto.parseParticipantData(vpd, false);
        assertTrue(p.isExercise());
        assertTrue(p.rolledBack());
        assertEquals(2, p.createdCore().size());
    }

    @Test
    void parseParticipantDataV31NotRolledBackWhenScopeEmpty() {
        final byte[] vpd = new Pb()
                .field(6, Pb.msg(Pb.lenField(1, new byte[0]))) // non-exercise action (arm 1)
                .field(7, Pb.msg(Pb.lenField(1, new byte[0]))) // empty scope
                .build();
        final ParticipantData p = CantonProto.parseParticipantData(vpd, false);
        assertFalse(p.isExercise());
        assertFalse(p.rolledBack());
    }

    @Test
    void parseParticipantDataV32RolledBack() {
        final byte[] vpd = new Pb()
                .field(6, Pb.msg(Pb.lenField(1, new byte[0])))
                .varint(8, 1)
                .build();
        final ParticipantData p = CantonProto.parseParticipantData(vpd, true);
        assertFalse(p.isExercise());
        assertTrue(p.rolledBack());
    }

    @Test
    void parseParticipantDataExerciseMissingChoiceThrows() {
        // Exercise arm present but missing choice (field 2) → missing field.
        final byte[] exercise =
                new Pb().field(10, "tmpl".getBytes(StandardCharsets.UTF_8)).build();
        final byte[] vpd = new Pb().field(6, Pb.msg(Pb.lenField(2, exercise))).build();
        assertThatThrownBy(() -> CantonProto.parseParticipantData(vpd, false))
                .isInstanceOf(CantonProofException.class)
                .satisfies(e -> assertEquals("CantonProtoMissingField", ((CantonProofException) e).errorName()));
    }

    // ── ViewCommonData / required confirmer ──────────────────────────────────

    @Test
    void isRequiredConfirmerTrueWhenOthersBelowThreshold() {
        // informees[0]=app, [1]=other. Quorum{entries: app weight1, other weight1, threshold 2}.
        // Without app, others(=1) < threshold(2) → app is required.
        final byte[] app = "app-party".getBytes(StandardCharsets.UTF_8);
        final byte[] other = "other-party".getBytes(StandardCharsets.UTF_8);
        final byte[] quorum = new Pb()
                .field(1, entry(0, 1))
                .field(1, entry(1, 1))
                .varint(2, 2)
                .build();
        final byte[] vcd =
                new Pb().field(2, app).field(2, other).field(3, quorum).build();
        assertTrue(CantonProto.isRequiredConfirmer(vcd, app));
    }

    @Test
    void isRequiredConfirmerFalseWhenOthersMeetThreshold() {
        final byte[] app = "app-party".getBytes(StandardCharsets.UTF_8);
        final byte[] other = "other-party".getBytes(StandardCharsets.UTF_8);
        final byte[] quorum = new Pb()
                .field(1, entry(0, 1))
                .field(1, entry(1, 2))
                .varint(2, 2)
                .build();
        final byte[] vcd =
                new Pb().field(2, app).field(2, other).field(3, quorum).build();
        assertFalse(CantonProto.isRequiredConfirmer(vcd, app));
    }

    @Test
    void isRequiredConfirmerDoesNotWrapWhenOtherWeightsSumPastUint64() {
        // The reference accumulates uint64 weights in a uint256, so two weights of 2^63 sum to 2^64 (not 0) and
        // the others already meet the threshold: app is NOT required. A 64-bit accumulator wraps to 0 < 1.
        final byte[] app = "app-party".getBytes(StandardCharsets.UTF_8);
        final byte[] other = "other-party".getBytes(StandardCharsets.UTF_8);
        final byte[] third = "third-party".getBytes(StandardCharsets.UTF_8);
        final byte[] quorum = new Pb()
                .field(1, entry(0, 1))
                .field(1, entry(1, Long.MIN_VALUE))
                .field(1, entry(2, Long.MIN_VALUE))
                .varint(2, 1)
                .build();
        final byte[] vcd = new Pb()
                .field(2, app)
                .field(2, other)
                .field(2, third)
                .field(3, quorum)
                .build();
        assertFalse(CantonProto.isRequiredConfirmer(vcd, app));
    }

    @Test
    void isRequiredConfirmerFalseWhenPartyAbsent() {
        final byte[] vcd = new Pb()
                .field(2, "x".getBytes(StandardCharsets.UTF_8))
                .field(3, new Pb().field(1, entry(0, 1)).varint(2, 5).build())
                .build();
        assertFalse(CantonProto.isRequiredConfirmer(vcd, "absent".getBytes(StandardCharsets.UTF_8)));
    }

    // ── readVarint ───────────────────────────────────────────────────────────

    @Test
    void readVarintDecodesSingleAndMultiByteValuesAndReportsNextOffset() {
        final var one = CantonProto.readVarint(new byte[] {0x7f, 0x00}, 0, 2);
        assertEquals(0x7f, one.value());
        assertEquals(1, one.next());

        // 300 = 0b1_0010_1100 -> 0xac 0x02, read from offset 1.
        final var multi = CantonProto.readVarint(new byte[] {0x00, (byte) 0xac, 0x02, 0x55}, 1, 4);
        assertEquals(300, multi.value());
        assertEquals(3, multi.next());
    }

    @Test
    void readVarintDecodesMaxUint64AsUnsignedLong() {
        final byte[] max = {
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            0x01
        };
        final var v = CantonProto.readVarint(max, 0, max.length);
        assertEquals(-1L, v.value());
        assertEquals(10, v.next());
    }

    @Test
    void readVarintRejectsOverflowPastUint64() {
        final byte[] tooBig = {
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            0x02
        };
        assertMalformed(() -> CantonProto.readVarint(tooBig, 0, tooBig.length));
    }

    @Test
    void readVarintRejectsRunningPastEndOrRange() {
        assertMalformed(() -> CantonProto.readVarint(new byte[] {(byte) 0x80}, 0, 1));
        assertMalformed(() -> CantonProto.readVarint(new byte[0], 0, 0));
        // The terminating byte exists in the array but lies beyond `end`.
        assertMalformed(() -> CantonProto.readVarint(new byte[] {(byte) 0x80, 0x01}, 0, 1));
    }

    // ── Versioned leaf / identifier ──────────────────────────────────────────

    @Test
    void unwrapVersionedConstructed() {
        final byte[] data = "leaf-body".getBytes(StandardCharsets.UTF_8);
        final byte[] leaf = new Pb().field(1, data).varint(2, 30).build();
        final Versioned v = CantonProto.unwrapVersioned(leaf);
        assertArrayEquals(data, v.data());
        assertEquals(30, v.version());
    }

    @Test
    void encodeIdentifierMatchesManualLayout() {
        final byte[] pkg = "916f16ea".getBytes(StandardCharsets.UTF_8);
        final byte[] modA = "Splice".getBytes(StandardCharsets.UTF_8);
        final byte[] modB = "Wallet".getBytes(StandardCharsets.UTF_8);
        final byte[] name = "WalletAppInstall".getBytes(StandardCharsets.UTF_8);
        final byte[] expected =
                concat(Pb.lenField(1, pkg), Pb.lenField(2, modA), Pb.lenField(2, modB), Pb.lenField(3, name));
        assertArrayEquals(expected, CantonProto.encodeIdentifier(pkg, modA, modB, name));
    }

    @Test
    void encodeIdentifierRejectsLongPart() {
        final byte[] big = new byte[128];
        assertMalformed(() -> CantonProto.encodeIdentifier(big, new byte[0], new byte[0], new byte[0]));
    }

    // ── Daml-LF scalar accessors ─────────────────────────────────────────────

    @Test
    void uintValueDecodesNonNegativeZigZag() {
        // Value{3: Int64 sint64=10 → zigzag 20}.
        final byte[] value = Pb.msg(pbVarintField(CantonProto.VALUE_INT64, 20));
        assertEquals(10, CantonProto.uintValue(value, new Range(0, value.length)));
    }

    @Test
    void uintValueRejectsNegativeZigZag() {
        // zigzag 19 is odd → negative → malformed.
        final byte[] value = Pb.msg(pbVarintField(CantonProto.VALUE_INT64, 19));
        assertMalformed(() -> CantonProto.uintValue(value, new Range(0, value.length)));
    }

    @Test
    void enumAndListAndHexValues() {
        // Enum{1: "Accepted"}.
        final byte[] en = Pb.msg(Pb.lenField(
                CantonProto.VALUE_ENUM,
                new Pb().field(1, "Accepted".getBytes(StandardCharsets.UTF_8)).build()));
        assertEquals(
                "Accepted",
                new String(sub(en, CantonProto.enumValue(en, new Range(0, en.length))), StandardCharsets.UTF_8));

        // List{1: e1, 1: e2}.
        final byte[] list = Pb.msg(Pb.lenField(
                CantonProto.VALUE_LIST,
                new Pb().field(1, new byte[] {1}).field(1, new byte[] {2, 3}).build()));
        assertEquals(
                2, CantonProto.listElements(list, new Range(0, list.length)).size());

        // hex text "0a1b" → bytes 0x0a 0x1b.
        final byte[] hexText = "0a1b".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(new byte[] {0x0a, 0x1b}, CantonProto.hexValue(hexText, new Range(0, hexText.length)));
    }

    @Test
    void hexValueRejectsOddLengthAndBadNibble() {
        final byte[] odd = "abc".getBytes(StandardCharsets.UTF_8);
        assertMalformed(() -> CantonProto.hexValue(odd, new Range(0, odd.length)));
        final byte[] bad = "0g".getBytes(StandardCharsets.UTF_8);
        assertMalformed(() -> CantonProto.hexValue(bad, new Range(0, bad.length)));
    }

    @Test
    void equalsComparesRangeBytes() {
        final byte[] b = "hello world".getBytes(StandardCharsets.UTF_8);
        assertTrue(CantonProto.equals(b, new Range(6, 11), "world".getBytes(StandardCharsets.UTF_8)));
        assertFalse(CantonProto.equals(b, new Range(0, 5), "world".getBytes(StandardCharsets.UTF_8)));
    }

    // ── Strict-parse negatives ─────────────────────────────

    @Nested
    class StrictParsing {
        @Test
        void repeatedSingularReadFieldRejected() {
            // getVarint over two occurrences of a field it reads → malformed (not last-wins).
            final byte[] b = new Pb()
                    .field(3, "x".getBytes(StandardCharsets.UTF_8))
                    .varint(5, 1)
                    .varint(5, 2)
                    .build();
            assertMalformed(() -> CantonProto.parseCommonMetadata(b));
        }

        @Test
        void wrongWireTypeForReadFieldRejected() {
            // CommonMetadata field 3 (LEN) supplied as VARINT → malformed.
            final byte[] b = new Pb().varint(3, 7).varint(5, 1).build();
            assertMalformed(() -> CantonProto.parseCommonMetadata(b));
        }

        @Test
        void oneofHolderWithUnexpectedExtraArmRejected() {
            // Daml-LF value holder carrying two fields is not a single oneof arm → malformed.
            final byte[] value =
                    concat(Pb.lenField(CantonProto.VALUE_RECORD, new byte[0]), Pb.lenField(1, new byte[0]));
            assertMalformed(() -> CantonProto.recordFields(value, new Range(0, value.length)));
        }

        @Test
        void oneofHolderWithWrongArmRejected() {
            // Expect RECORD(13) but holder carries TEXT(8) → malformed.
            final byte[] value = Pb.msg(Pb.lenField(CantonProto.VALUE_TEXT, new byte[] {1}));
            assertMalformed(() -> CantonProto.recordFields(value, new Range(0, value.length)));
        }

        @Test
        void unknownWireTypeRejected() {
            // Field key with wire type 3 (group start, unsupported) → malformed.
            final byte[] b = new byte[] {(byte) ((1 << 3) | 3)};
            assertMalformed(() -> CantonProto.parseCommonMetadata(b));
        }

        @Test
        void lengthPrefixPastEndRejected() {
            // LEN field claims 10 bytes but only 1 remains.
            final byte[] b = new byte[] {(byte) ((3 << 3) | 2), 10, 1};
            assertMalformed(() -> CantonProto.parseCommonMetadata(b));
        }

        @Test
        void varintOverflowRejected() {
            // 10 continuation bytes overflow uint64.
            final byte[] b = new byte[11];
            b[0] = (byte) ((5 << 3));
            for (int i = 1; i <= 10; i++) {
                b[i] = (byte) 0x80;
            }
            assertMalformed(() -> CantonProto.parseCommonMetadata(b));
        }

        @Test
        void lengthPrefixWithHighBitSetRejected() {
            // A LEN field whose length varint = 2^63 (ten bytes: nine 0x80 then 0x01) has bit 63 set, i.e. is
            // NEGATIVE as a signed long. This must be rejected by the unsigned bounds compare — a signed
            // compare would (wrongly) treat it as "fits" and accept bytes Solidity rejects. Distinct from
            // varintOverflowRejected, which trips the uint64 overflow guard, not this bounds path.
            final byte[] b = new byte[] {
                (byte) ((3 << 3) | 2),
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x01
            };
            assertMalformed(() -> CantonProto.parseCommonMetadata(b));
        }

        @Test
        void repeatedFieldPreservesOrderOfAllOccurrences() {
            // A genuinely repeated field (getAll) keeps every occurrence in order — createdCore here.
            final byte[] vpd = new Pb()
                    .field(3, new byte[] {10})
                    .field(3, new byte[] {20})
                    .field(3, new byte[] {30})
                    .field(6, Pb.msg(Pb.lenField(1, new byte[0])))
                    .build();
            final ParticipantData p = CantonProto.parseParticipantData(vpd, true);
            assertEquals(3, p.createdCore().size());
            assertEquals((byte) 10, vpd[p.createdCore().get(0).start()]);
            assertEquals((byte) 20, vpd[p.createdCore().get(1).start()]);
            assertEquals((byte) 30, vpd[p.createdCore().get(2).start()]);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertMalformed(final org.junit.jupiter.api.function.Executable body) {
        assertThatThrownBy(body::execute)
                .isInstanceOf(CantonProofException.class)
                .satisfies(e -> assertEquals("CantonProtoMalformed", ((CantonProofException) e).errorName()));
    }

    /** A varint-field with a wrapping Value holder, as a raw field (no outer length). */
    private static byte[] pbVarintField(final long number, final long value) {
        final var out = new ByteArrayOutputStream();
        writeVarint(out, number << 3);
        writeVarint(out, value);
        return out.toByteArray();
    }

    private static byte[] entry(final long index, final long weight) {
        return new Pb().varint(1, index).varint(2, weight).build();
    }

    private static byte[] sub(final byte[] b, final Range r) {
        final byte[] out = new byte[r.length()];
        System.arraycopy(b, r.start(), out, 0, r.length());
        return out;
    }

    private static byte[] multihash() {
        final byte[] mh = new byte[34];
        mh[0] = 0x12;
        mh[1] = 0x20;
        return mh;
    }

    /**
     * A base ConfirmationResultMessage body (fields 1-4, no Verdict field 5). Tests add field 5 themselves so
     * that doing so does not create an unintended duplicate of a singular field.
     */
    private Pb crmBuilder() {
        return new Pb()
                .field(1, "global-domain::1220::35-0".getBytes(StandardCharsets.UTF_8))
                .varint(2, 1)
                .varint(3, 1791363167585288L)
                .field(4, multihash());
    }

    private byte[] verdict(final Pb crm) {
        return wrapCrm(crm.build());
    }

    /** Versioned{1: TypedSignedProtocolMessageContent{3: Versioned{1: crm}}}. */
    private static byte[] wrapCrm(final byte[] crm) {
        final byte[] innerVersioned = new Pb().field(1, crm).build();
        final byte[] typed = new Pb().field(3, innerVersioned).build();
        return new Pb().field(1, typed).build();
    }

    private static byte[] concat(final byte[]... parts) {
        int n = 0;
        for (final byte[] p : parts) {
            n += p.length;
        }
        final byte[] out = new byte[n];
        int o = 0;
        for (final byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static void writeVarint(final ByteArrayOutputStream out, long v) {
        while (true) {
            final int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    /** Minimal protobuf wire-format builder for constructing real wire bytes in tests. */
    private static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            final byte[] f = lenField(number, value);
            out.writeBytes(f);
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

        /** A single LEN-delimited field {@code number: value}. */
        static byte[] lenField(final long number, final byte[] value) {
            final var o = new ByteArrayOutputStream();
            writeVarint(o, (number << 3) | CantonProto.WIRE_LEN);
            writeVarint(o, value.length);
            o.writeBytes(value);
            return o.toByteArray();
        }

        /** Treat raw field bytes as a complete message body (identity wrapper for readability). */
        static byte[] msg(final byte[] fields) {
            return fields;
        }
    }
}
