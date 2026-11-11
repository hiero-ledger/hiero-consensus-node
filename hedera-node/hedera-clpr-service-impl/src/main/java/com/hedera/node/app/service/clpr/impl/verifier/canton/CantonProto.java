// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strict in-place protobuf readers for the Canton structures a CLPR proof opens: the mediator verdict, the
 * Merkle-tree leaves of a transaction view, the contracts inside them, and Daml-LF values. Every parse
 * decision must match the Solidity byte for byte, because a parse divergence is a consensus-safety bug.
 *
 * <p>Readers work in place on {@link Range} {@code (start, end)} windows of one {@code byte[]}; only
 * {@link #copy}, {@link #hexValue} and the {@code record} exit points allocate. A range is always inside the
 * buffer: every length prefix is checked against its enclosing range. Canton's parser keeps the last
 * occurrence of a singular field and the last member of a {@code oneof}; our validator writes the leaf
 * bytes. So a field read here must have its expected wire type and, if singular, occur at most once, and a
 * {@code oneof} holder must hold exactly one field: then both parsers read the same value. A second
 * occurrence of a singular field we read is therefore rejected (stricter than Canton's last-wins, matching
 * the Solidity reference), not silently resolved to the last.
 */
public final class CantonProto {
    public static final int WIRE_VARINT = 0;
    public static final int WIRE_I64 = 1;
    public static final int WIRE_LEN = 2;
    public static final int WIRE_I32 = 5;

    /** Daml-LF {@code Value} oneof field numbers. */
    public static final long VALUE_INT64 = 3;

    public static final long VALUE_PARTY = 7;
    public static final long VALUE_TEXT = 8;
    public static final long VALUE_LIST = 11;
    public static final long VALUE_RECORD = 13;
    public static final long VALUE_ENUM = 15;

    /** {@code VIEW_TYPE_TRANSACTION} in {@code ViewType}. */
    public static final long VIEW_TYPE_TRANSACTION = 1;

    private CantonProto() {}

    /** A half-open {@code [start, end)} window into the buffer being read. */
    public record Range(int start, int end) {
        public int length() {
            return end - start;
        }
    }

    /** The byte range of one {@code CreatedContract}/{@code InputContract} inside a view participant data leaf. */
    public record ContractRange(int start, int end) {
        public int length() {
            return end - start;
        }

        Range range() {
            return new Range(start, end);
        }
    }

    /** The fields of a {@code ConfirmationResultMessage}. */
    public record Verdict(
            long viewType,
            @NonNull byte[] synchronizerId,
            long requestId,
            @NonNull byte[] rootHash,
            boolean approved) {}

    /** What the verifier reads from a {@code ViewParticipantData} leaf. */
    public record ParticipantData(
            boolean isExercise,
            boolean rolledBack,
            @NonNull List<ContractRange> createdCore,
            @NonNull Range exerciseChoice,
            @NonNull Range exerciseTemplateId) {}

    /** {@code CommonMetadata}'s synchronizer id and mediator group. */
    public record SyncAndGroup(@NonNull byte[] synchronizerId, long group) {}

    /** The protobuf inside a Merkle leaf and its proto version. */
    public record Versioned(@NonNull byte[] data, long version) {}

    /** The parsed template id, create argument and signatories of an encoded contract (ranges into the buffer). */
    public record Contract(
            @NonNull Range templateId,
            @NonNull List<Range> signatories,
            @NonNull Range createArg) {}

    // ── Wire primitives ─────────────────────────────────────────────────────

    record Varint(long value, int next) {}

    record Field(long number, int wireType, Range value, long scalar, int next) {}

    static Varint readVarint(final byte[] b, int i, final int end) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (i >= end) {
                throw CantonProofException.cantonProtoMalformed("varint runs past range");
            }
            final int c = b[i] & 0xff;
            ++i;
            if (shift == 63 && c > 1) {
                throw CantonProofException.cantonProtoMalformed("varint overflows uint64");
            }
            v |= (long) (c & 0x7f) << shift;
            if (c < 0x80) {
                return new Varint(v, i);
            }
            shift += 7;
        }
    }

    /**
     * Read the field at {@code i}: its number and wire type, the value range for LEN fields, the value for
     * VARINT fields, and the offset of the next field.
     */
    static Field readField(final byte[] b, int i, final int end) {
        final Varint key = readVarint(b, i, end);
        i = key.next();
        final long number = key.value() >>> 3;
        final int wireType = (int) (key.value() & 7);
        if (wireType == WIRE_VARINT) {
            final Varint scalar = readVarint(b, i, end);
            return new Field(number, wireType, null, scalar.value(), scalar.next());
        } else if (wireType == WIRE_LEN) {
            final Varint lenV = readVarint(b, i, end);
            i = lenV.next();
            final long len = lenV.value();
            // len is an UNSIGNED uint64 varint (bit 63 may be set, making it negative as a signed long), so an
            // unsigned compare against the remaining range is required — a signed compare would accept a huge
            // length that Solidity (uint256 compare) rejects.
            if (Long.compareUnsigned(len, end - i) > 0) {
                throw CantonProofException.cantonProtoMalformed("length prefix runs past range");
            }
            final int next = i + (int) len;
            return new Field(number, wireType, new Range(i, next), 0, next);
        } else if (wireType == WIRE_I64 || wireType == WIRE_I32) {
            final int next = i + (wireType == WIRE_I64 ? 8 : 4);
            if (next > end) {
                throw CantonProofException.cantonProtoMalformed("fixed-width field runs past range");
            }
            return new Field(number, wireType, null, 0, next);
        } else {
            throw CantonProofException.cantonProtoMalformed("unknown wire type " + wireType);
        }
    }

    /** The value range of the LEN field {@code number} in {@code r}, which must occur at most once; null if absent. */
    static Range find(final byte[] b, final Range r, final long number) {
        Range value = null;
        boolean found = false;
        int i = r.start();
        while (i < r.end()) {
            final Field f = readField(b, i, r.end());
            if (f.number() == number) {
                if (found || f.wireType() != WIRE_LEN) {
                    throw CantonProofException.cantonProtoMalformed("duplicate or non-LEN field " + number);
                }
                found = true;
                value = f.value();
            }
            i = f.next();
        }
        return value;
    }

    /** As {@link #find}, throwing when the field is absent. */
    static Range get(final byte[] b, final Range r, final long number) {
        final Range value = find(b, r, number);
        if (value == null) {
            throw CantonProofException.cantonProtoMissingField("field " + number);
        }
        return value;
    }

    /**
     * The VARINT field {@code number} in {@code r}, which must occur at most once, or zero (the proto3
     * default) when absent.
     */
    static long getVarint(final byte[] b, final Range r, final long number) {
        long value = 0;
        boolean found = false;
        int i = r.start();
        while (i < r.end()) {
            final Field f = readField(b, i, r.end());
            if (f.number() == number) {
                if (found || f.wireType() != WIRE_VARINT) {
                    throw CantonProofException.cantonProtoMalformed("duplicate or non-VARINT field " + number);
                }
                found = true;
                value = f.scalar();
            }
            i = f.next();
        }
        return value;
    }

    /** Every LEN field {@code number} in {@code r}, in order. */
    static List<Range> getAll(final byte[] b, final Range r, final long number) {
        final List<Range> out = new ArrayList<>();
        int i = r.start();
        while (i < r.end()) {
            final Field f = readField(b, i, r.end());
            if (f.number() == number) {
                if (f.wireType() != WIRE_LEN) {
                    throw CantonProofException.cantonProtoMalformed("non-LEN repeated field " + number);
                }
                out.add(f.value());
            }
            i = f.next();
        }
        return out;
    }

    /** The field of a {@code oneof} holder {@code r}, which must hold exactly one field. */
    static Field onlyField(final byte[] b, final Range r) {
        final Field f = readField(b, r.start(), r.end());
        if (f.next() != r.end()) {
            throw CantonProofException.cantonProtoMalformed("oneof holder does not hold exactly one field");
        }
        return f;
    }

    static Range whole(final byte[] b) {
        return new Range(0, b.length);
    }

    private static boolean rangeEquals(final byte[] b, final Range r, final byte[] expected) {
        return r.length() == expected.length && Arrays.equals(b, r.start(), r.end(), expected, 0, expected.length);
    }

    static byte[] copy(final byte[] b, final Range r) {
        return Arrays.copyOfRange(b, r.start(), r.end());
    }

    // ── Verdict ────────────────────────────────────────────────────────────

    /**
     * Parse the {@code typedSignedProtocolMessageContent} the mediators sign.
     *
     * <p>{@code Versioned{1: TypedSignedProtocolMessageContent{3: Versioned{1: ConfirmationResultMessage}}}}.
     * {@code ConfirmationResultMessage{1: physical_synchronizer_id, 2: view_type, 3: request_id,
     * 4: root_hash (34-byte multihash), 5: Verdict{1: approve}}}.
     */
    @NonNull
    public static Verdict parseVerdict(@NonNull final byte[] typed) {
        Range r = get(typed, whole(typed), 1);
        r = get(typed, r, 3);
        r = get(typed, r, 1);
        final byte[] synchronizerId = copy(typed, get(typed, r, 1));
        final long viewType = getVarint(typed, r, 2);
        final long requestId = getVarint(typed, r, 3);
        final byte[] rootHash = readMultihash(typed, get(typed, r, 4));
        boolean approved = false;
        final Range verdict = find(typed, r, 5);
        if (verdict != null) {
            final Field only = onlyField(typed, verdict);
            approved = only.number() == 1;
        }
        return new Verdict(viewType, synchronizerId, requestId, rootHash, approved);
    }

    /** The SHA-256 digest inside a 34-byte multihash {@code 0x12 0x20 || digest}. */
    @NonNull
    static byte[] readMultihash(final byte[] b, final Range r) {
        if (r.length() != 34 || (b[r.start()] & 0xff) != 0x12 || (b[r.start() + 1] & 0xff) != 0x20) {
            throw CantonProofException.cantonProtoMalformed("not a 34-byte sha256 multihash");
        }
        return Arrays.copyOfRange(b, r.start() + 2, r.start() + 34);
    }

    // ── Merkle leaves ──────────────────────────────────────────────────────

    /**
     * The protobuf inside a Merkle leaf, and its proto version. Canton hashes each leaf as
     * {@code UntypedVersionedMessage{1: data, 2: version}}.
     */
    @NonNull
    public static Versioned unwrapVersioned(@NonNull final byte[] leaf) {
        final Range r = whole(leaf);
        final byte[] data = copy(leaf, get(leaf, r, 1));
        final long version = getVarint(leaf, r, 2);
        return new Versioned(data, version);
    }

    /** {@code CommonMetadata{3: physical_synchronizer_id, 5: mediator_group}}. */
    @NonNull
    public static SyncAndGroup parseCommonMetadata(@NonNull final byte[] b) {
        final Range r = whole(b);
        final byte[] synchronizerId = copy(b, get(b, r, 3));
        final long group = getVarint(b, r, 5);
        return new SyncAndGroup(synchronizerId, group);
    }

    /**
     * Whether {@code party} is a required confirmer of the view whose {@code ViewCommonData} is {@code b}: in
     * some quorum, the weights of the other members stay below the threshold without it.
     *
     * <p>{@code ViewCommonData{2: informees (party ids), 3: Quorum{1: PartyIndexAndWeight{1: index,
     * 2: weight}, 2: threshold}}}. A quorum entry indexes into {@code informees}.
     */
    public static boolean isRequiredConfirmer(@NonNull final byte[] b, @NonNull final byte[] party) {
        final Range r = whole(b);
        final List<Range> informees = getAll(b, r, 2);
        int partyIndex = -1;
        for (int i = 0; i < informees.size(); ++i) {
            if (rangeEquals(b, informees.get(i), party)) {
                partyIndex = i;
                break;
            }
        }
        if (partyIndex == -1) {
            return false;
        }

        final List<Range> quorums = getAll(b, r, 3);
        for (final Range quorum : quorums) {
            final List<Range> entries = getAll(b, quorum, 1);
            long others = 0;
            boolean member = false;
            for (final Range entry : entries) {
                final long index = getVarint(b, entry, 1);
                final long weight = getVarint(b, entry, 2);
                if (index == partyIndex) {
                    member = true;
                } else {
                    others += weight;
                }
            }
            if (member && Long.compareUnsigned(others, getVarint(b, quorum, 2)) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read a {@code ViewParticipantData} leaf.
     *
     * <p>Proto v31 (protocol version 35) carries {@code 7: RollbackContext{1: rollback_scope}}, rolled back
     * when the scope is non-empty. Proto v32 (protocol version 36) carries {@code 8: rolled_back}.
     * {@code 6: ActionDescription{2: ExerciseActionDescription{2: choice, 10: template_id}}}.
     */
    @NonNull
    public static ParticipantData parseParticipantData(@NonNull final byte[] b, final boolean protoV32) {
        final Range r = whole(b);
        // Read core inputs (field 2) for wire-type strictness parity with the reference, though unused here.
        getAll(b, r, 2);
        final List<Range> createdCore = getAll(b, r, 3);
        final Field action = onlyField(b, get(b, r, 6));
        final boolean isExercise = action.number() == 2 && action.wireType() == WIRE_LEN;
        // As the reference, the choice and template id are the empty range unless the action is an exercise.
        Range exerciseChoice = new Range(0, 0);
        Range exerciseTemplateId = new Range(0, 0);
        if (isExercise) {
            exerciseChoice = get(b, action.value(), 2);
            exerciseTemplateId = get(b, action.value(), 10);
        }
        boolean rolledBack;
        if (protoV32) {
            rolledBack = getVarint(b, r, 8) != 0;
        } else {
            final Range rollback = find(b, r, 7);
            if (rollback != null) {
                final Range scope = find(b, rollback, 1);
                rolledBack = scope != null && scope.length() > 0;
            } else {
                rolledBack = false;
            }
        }
        final List<ContractRange> created = new ArrayList<>(createdCore.size());
        for (final Range cr : createdCore) {
            created.add(new ContractRange(cr.start(), cr.end()));
        }
        return new ParticipantData(isExercise, rolledBack, created, exerciseChoice, exerciseTemplateId);
    }

    // ── Contracts ──────────────────────────────────────────────────────────

    /**
     * The template id, the signatories and the create argument of an encoded contract.
     *
     * <p>{@code contractRange} is an {@code InputContract} or a {@code CreatedContract}; both hold the
     * contract in field 1 as {@code Versioned{2: FatContractInstance{3: template_id, 4: create_arg,
     * 5: signatories}}}.
     */
    @NonNull
    public static Contract parseContract(@NonNull final byte[] b, @NonNull final ContractRange contractRange) {
        final Range fat = get(b, get(b, contractRange.range(), 1), 2);
        final Range templateId = get(b, fat, 3);
        final Range createArg = get(b, fat, 4);
        final List<Range> signatories = getAll(b, fat, 5);
        return new Contract(templateId, signatories, createArg);
    }

    /**
     * The encoding of a Daml-LF {@code Identifier{1: package_id, 2: module_name..., 3: name...}} for a
     * template {@code Module.Sub:Entity}, as the verifier expects it in a contract.
     */
    @NonNull
    public static byte[] encodeIdentifier(
            @NonNull final byte[] packageIdHex,
            @NonNull final byte[] moduleA,
            @NonNull final byte[] moduleB,
            @NonNull final byte[] name) {
        final byte[] f1 = lenField(1, packageIdHex);
        final byte[] f2a = lenField(2, moduleA);
        final byte[] f2b = lenField(2, moduleB);
        final byte[] f3 = lenField(3, name);
        final byte[] out = new byte[f1.length + f2a.length + f2b.length + f3.length];
        int p = 0;
        for (final byte[] part : new byte[][] {f1, f2a, f2b, f3}) {
            System.arraycopy(part, 0, out, p, part.length);
            p += part.length;
        }
        return out;
    }

    private static byte[] lenField(final int number, final byte[] value) {
        if (value.length >= 128) {
            throw CantonProofException.cantonProtoMalformed("identifier part too long");
        }
        final byte[] out = new byte[2 + value.length];
        out[0] = (byte) (number << 3 | WIRE_LEN);
        out[1] = (byte) value.length;
        System.arraycopy(value, 0, out, 2, value.length);
        return out;
    }

    // ── Daml-LF values ─────────────────────────────────────────────────────

    /** The fields of a Daml-LF record {@code Value{13: Record{1: Field{1: Value}}}}, in declaration order. */
    @NonNull
    public static List<Range> recordFields(@NonNull final byte[] b, @NonNull final Range value) {
        final List<Range> wrapped = getAll(b, member(b, value, VALUE_RECORD), 1);
        final List<Range> fields = new ArrayList<>(wrapped.size());
        for (final Range w : wrapped) {
            fields.add(get(b, w, 1));
        }
        return fields;
    }

    /** The elements of a Daml-LF {@code Value{11: List{1: Value...}}}. */
    @NonNull
    public static List<Range> listElements(@NonNull final byte[] b, @NonNull final Range value) {
        return getAll(b, member(b, value, VALUE_LIST), 1);
    }

    /** The UTF-8 bytes (as a range) of a Daml-LF text or party value. */
    @NonNull
    public static Range stringValue(@NonNull final byte[] b, @NonNull final Range value, final long kind) {
        return member(b, value, kind);
    }

    /** The constructor name of a Daml-LF {@code Value{15: Enum{1: value}}}. */
    @NonNull
    public static Range enumValue(@NonNull final byte[] b, @NonNull final Range value) {
        return get(b, member(b, value, VALUE_ENUM), 1);
    }

    /** A Daml-LF {@code Int64} (zig-zag {@code sint64}) that must be non-negative. */
    public static long uintValue(@NonNull final byte[] b, @NonNull final Range value) {
        final Field f = onlyField(b, value);
        if (f.number() != VALUE_INT64 || f.wireType() != WIRE_VARINT || (f.scalar() & 1) != 0) {
            throw CantonProofException.cantonProtoMalformed("not a non-negative Int64 value");
        }
        return f.scalar() >>> 1;
    }

    /** The LEN member {@code kind} of the Daml-LF {@code Value} oneof in {@code value}. */
    private static Range member(final byte[] b, final Range value, final long kind) {
        final Field f = onlyField(b, value);
        if (f.number() != kind || f.wireType() != WIRE_LEN) {
            throw CantonProofException.cantonProtoMalformed("Daml-LF value is not of kind " + kind);
        }
        return f.value();
    }

    /** Whether the bytes in {@code r} equal {@code expected}. */
    public static boolean equals(@NonNull final byte[] b, @NonNull final Range r, @NonNull final byte[] expected) {
        return rangeEquals(b, r, expected);
    }

    /** Decode lowercase hex text in {@code r} into bytes. */
    @NonNull
    public static byte[] hexValue(@NonNull final byte[] b, @NonNull final Range r) {
        final int len = r.length();
        if (len % 2 != 0) {
            throw CantonProofException.cantonProtoMalformed("odd-length hex value");
        }
        final byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; ++i) {
            out[i] = (byte) (nibble(b[r.start() + 2 * i]) << 4 | nibble(b[r.start() + 2 * i + 1]));
        }
        return out;
    }

    private static int nibble(final byte c) {
        final int x = c & 0xff;
        if (x >= 0x30 && x <= 0x39) {
            return x - 0x30;
        }
        if (x >= 0x61 && x <= 0x66) {
            return x - 0x57;
        }
        throw CantonProofException.cantonProtoMalformed("invalid hex nibble");
    }
}
