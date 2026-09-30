// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas.ValueType;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.PartialApplier;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Top-level protobuf field-run splicing. Nested messages remain opaque, whole replacements. */
public final class PartialStateChangeApplier {
    private PartialStateChangeApplier() {}

    /** Validate CN's payload schema and delegate the actual byte replacement to PBJ. */
    public static Bytes splice(ValueType type, Bytes prior, Bytes partial, List<Integer> cleared) {
        final var old = fields(type, prior);
        final var patch = fields(type, partial);
        final int[] clearNumbers = cleared.stream().mapToInt(Integer::intValue).toArray();
        PartialApplier.validateCleared(clearNumbers);
        final var clearSet = new HashSet<>(cleared);
        for (int number : cleared) {
            if (type.fields.apply(number) == null || patch.containsKey(number)) {
                throw new IllegalArgumentException("Unknown or overlapping clear: " + number);
            }
        }
        for (int number : patch.keySet()) {
            final String group = type.oneof.apply(number);
            if (group.isEmpty()) continue;
            for (int previous : old.keySet()) {
                if (previous != number && group.equals(type.oneof.apply(previous)) && !clearSet.contains(previous)) {
                    throw new IllegalArgumentException("Oneof switch must explicitly clear its old alternative");
                }
            }
        }
        return PartialApplier.splice(prior, partial, clearNumbers);
    }

    static Map<Integer, Bytes> fields(ValueType type, Bytes bytes) {
        final var result = new TreeMap<Integer, Bytes>();
        final var input = bytes.toReadableSequentialData();
        final var oneofs = new HashSet<String>();
        int previous = 0;
        long runStart = 0;
        while (input.hasRemaining()) {
            final long start = input.position();
            final int tag = input.readVarInt(false);
            final int number = tag >>> 3;
            final int wire = tag & 7;
            final var field = type.fields.apply(number);
            if (number == 0
                    || field == null
                    || number < previous
                    || (number == previous && !field.repeated())
                    || input.position() - start != varintSize(Integer.toUnsignedLong(tag))) {
                throw new IllegalArgumentException("Unknown, unordered, duplicate or noncanonical field: " + number);
            }
            final var group = type.oneof.apply(number);
            if (!group.isEmpty() && !oneofs.add(group))
                throw new IllegalArgumentException("Multiple oneof alternatives");
            final boolean packed = field.repeated()
                    && field.type() != FieldType.STRING
                    && field.type() != FieldType.BYTES
                    && field.type() != FieldType.MESSAGE;
            final int expected = field.optional() || packed
                    ? 2
                    : switch (field.type()) {
                        case DOUBLE, FIXED64, SFIXED64 -> 1;
                        case MESSAGE, STRING, BYTES -> 2;
                        case FLOAT, FIXED32, SFIXED32 -> 5;
                        default -> 0;
                    };
            if (wire != expected) throw new IllegalArgumentException("Incorrect wire type for field " + number);
            final boolean presence = field.oneOf() || field.optional() || field.repeated();
            if (number != previous) runStart = start;
            switch (wire) {
                case 0 -> {
                    final long at = input.position();
                    final long value = input.readVarLong(false);
                    if (input.position() - at != varintSize(value)
                            || (value == 0 && !presence)
                            || (field.type() == FieldType.BOOL && value != 0 && value != 1)) {
                        throw new IllegalArgumentException("Noncanonical scalar");
                    }
                }
                case 1 -> {
                    if (input.remaining() < 8) throw new IllegalArgumentException("Truncated fixed64");
                    input.skip(8);
                }
                case 2 -> {
                    final long at = input.position();
                    final int length = input.readVarInt(false);
                    if (length < 0
                            || length > input.remaining()
                            || input.position() - at != varintSize(length)
                            || (length == 0 && (packed || (!presence && field.type() != FieldType.MESSAGE)))) {
                        throw new IllegalArgumentException("Invalid field length");
                    }
                    input.skip(length);
                }
                case 5 -> {
                    if (input.remaining() < 4) throw new IllegalArgumentException("Truncated fixed32");
                    input.skip(4);
                }
                default -> throw new IllegalArgumentException("Unsupported wire type");
            }
            result.put(number, bytes.slice(runStart, input.position() - runStart));
            previous = number;
        }
        return result;
    }

    static int varintSize(long value) {
        int size = 1;
        while ((value >>>= 7) != 0) size++;
        return size;
    }
}
