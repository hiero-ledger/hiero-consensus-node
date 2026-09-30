// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/** Universal top-level replacements and explicit clears. No recursive patches or appends. */
public final class StateChangeDeltas {
    public static final int DEFAULT_MIN_SAVING = 64;

    private StateChangeDeltas() {}

    /** A sparse value and its clear list. The private PBJ mask is used only for size selection. */
    public static final class Delta<T> {
        private final T value;
        private final List<Integer> clearedFields;
        private final Object mask;

        private Delta(T value, int[] clearedFields, Object mask) {
            this.value = requireNonNull(value);
            this.clearedFields = Arrays.stream(clearedFields).boxed().toList();
            this.mask = mask instanceof long[] words ? words.clone() : requireNonNull(mask);
        }

        public T value() {
            return value;
        }

        public List<Integer> clearedFields() {
            return clearedFields;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Delta<?> that
                    && value.equals(that.value)
                    && clearedFields.equals(that.clearedFields)
                    && Objects.deepEquals(mask, that.mask);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, clearedFields, mask instanceof long[] words ? Arrays.hashCode(words) : mask);
        }
    }

    @FunctionalInterface
    interface ClearFields<T, M> {
        int[] apply(T prior, T next, M mask);
    }

    @FunctionalInterface
    interface SavingsTest<T, M> {
        boolean test(T next, M mask, int minimum, int overhead);
    }

    @FunctionalInterface
    interface ObjectApplier<T> {
        T apply(T prior, T partial, int[] cleared);
    }

    /** Typed adapter to PBJ-generated operations for a block-stream payload. */
    public static final class ValueType {
        private final String name;
        private final Class<?> javaType;
        private final Codec<Object> codec;
        final IntFunction<FieldDefinition> fields;
        final IntFunction<String> oneof;
        private final BiFunction<Object, Object, Delta<?>> differ;
        private final SavingsTest<Object, Object> savings;
        private final ObjectApplier<Object> applier;

        @SuppressWarnings("unchecked")
        <T, M> ValueType(
                String name,
                Class<T> javaType,
                Codec<T> codec,
                IntFunction<FieldDefinition> fields,
                IntFunction<String> oneof,
                BiFunction<T, T, M> differ,
                BiFunction<T, M, T> partial,
                ClearFields<T, M> clearFields,
                SavingsTest<T, M> savings,
                ObjectApplier<T> applier) {
            this.name = name;
            this.javaType = javaType;
            this.codec = (Codec<Object>) codec;
            this.fields = fields;
            this.oneof = oneof;
            this.differ = (prior, next) -> {
                try {
                    final M mask = differ.apply(javaType.cast(prior), javaType.cast(next));
                    return new Delta<>(
                            partial.apply(javaType.cast(next), mask),
                            clearFields.apply(javaType.cast(prior), javaType.cast(next), mask),
                            mask);
                } catch (UnsupportedOperationException unsupported) {
                    // PBJ requires a full replacement when top-level unknown fields are present.
                    return null;
                }
            };
            this.savings =
                    (next, mask, minimum, overhead) -> savings.test(javaType.cast(next), (M) mask, minimum, overhead);
            this.applier = (prior, partialValue, cleared) ->
                    applier.apply(javaType.cast(prior), javaType.cast(partialValue), cleared);
        }

        public String name() {
            return name;
        }

        public Object stateValue(Object value) {
            if (javaType == ProtoBytes.class && value instanceof Bytes bytes) return new ProtoBytes(bytes);
            if (javaType == ProtoString.class && value instanceof String text) return new ProtoString(text);
            if (javaType == EntityNumber.class && value instanceof Long number) return new EntityNumber(number);
            if (!javaType.isInstance(value)) throw new IllegalArgumentException("Wrong value type for " + name);
            return value;
        }

        public Object wireValue(Object value) {
            return switch (value) {
                case ProtoBytes bytes -> bytes.value();
                case ProtoString text -> text.value();
                case EntityNumber number -> number.number();
                default -> value;
            };
        }

        public Bytes bytes(Object value) {
            return codec.toBytes(stateValue(value));
        }

        public Object parse(Bytes bytes) {
            try {
                return codec.parse(bytes.toReadableSequentialData(), false, true, Codec.DEFAULT_MAX_DEPTH);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Invalid " + name + " value", e);
            }
        }

        public Object defaultValue() {
            return parse(Bytes.EMPTY);
        }

        /** Apply a partial value using PBJ's generated object applier, without serialization. */
        public Object applyPartial(Object prior, Object partial, List<Integer> cleared) {
            return applyObject(this, prior, partial, cleared);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static List<ValueType> valueTypes() {
        return StateChangeTypes.types();
    }

    public static ValueType valueType(Object value) {
        return StateChangeTypes.forValue(value);
    }

    public static ValueType mapType(int stateId, int valueField) {
        return StateChangeTypes.mapType(valueField);
    }

    public static ValueType singletonType(int stateId, int valueField) {
        return StateChangeTypes.singletonType(valueField);
    }

    /** PBJ value comparisons; top-level unknown fields conservatively retain full output. */
    @Nullable
    public static Delta<?> diff(@Nullable Object prior, Object next) {
        final var type = valueType(next);
        return prior == null || type == null || prior.getClass() != next.getClass()
                ? null
                : type.differ.apply(prior, next);
    }

    /** Counts only omitted fields and exits once the saving covers metadata plus the configured minimum. */
    public static boolean worthPartial(ValueType type, Object next, Delta<?> delta, int minSaving, int clearTag) {
        if (minSaving < 0) throw new IllegalArgumentException("Minimum saving must be nonnegative");
        long overhead = PartialStateChangeApplier.varintSize(((clearTag - 1L) << 3)) + 1;
        if (!delta.clearedFields().isEmpty()) {
            long length = 0;
            for (int field : delta.clearedFields()) length += PartialStateChangeApplier.varintSize(field);
            overhead += PartialStateChangeApplier.varintSize(((long) clearTag << 3) | 2)
                    + PartialStateChangeApplier.varintSize(length)
                    + length;
        }
        return type.savings.test(type.stateValue(next), delta.mask, minSaving, Math.toIntExact(overhead));
    }

    public static MapUpdateChange encodeMap(int stateId, @Nullable Object prior, MapUpdateChange full) {
        return encodeMap(stateId, prior, full, DEFAULT_MIN_SAVING);
    }

    public static MapUpdateChange encodeMap(int stateId, @Nullable Object prior, MapUpdateChange full, int minSaving) {
        requireProducerFull(full.partial(), full.clearedFields());
        final var choice = full.valueOrThrow().valueChoice();
        final var type = requireType(mapType(stateId, choice.kind().protoOrdinal()));
        final var next = type.stateValue(choice.value());
        final var delta = diff(prior == null ? null : type.stateValue(prior), next);
        if (delta == null || !worthPartial(type, next, delta, minSaving, 5)) return full;
        return full.copyBuilder()
                .value(new MapChangeValue(new OneOf<>(choice.kind(), type.wireValue(delta.value()))))
                .partial(true)
                .clearedFields(delta.clearedFields())
                .build();
    }

    public static SingletonUpdateChange encodeSingleton(
            int stateId, @Nullable Object prior, SingletonUpdateChange full) {
        return encodeSingleton(stateId, prior, full, DEFAULT_MIN_SAVING);
    }

    public static SingletonUpdateChange encodeSingleton(
            int stateId, @Nullable Object prior, SingletonUpdateChange full, int minSaving) {
        requireProducerFull(full.partial(), full.clearedFields());
        final var choice = full.newValue();
        final var type = requireType(singletonType(stateId, choice.kind().protoOrdinal()));
        final var next = type.stateValue(choice.value());
        final var delta = diff(prior == null ? null : type.stateValue(prior), next);
        if (delta == null || !worthPartial(type, next, delta, minSaving, 25)) return full;
        return new SingletonUpdateChange(
                true,
                delta.clearedFields(),
                new OneOf<>(choice.kind(), type.wireValue(delta.value())),
                full.getUnknownFields());
    }

    public static Object applyMap(int stateId, @Nullable Object prior, MapUpdateChange update) {
        requireMetadata(update.partial(), update.clearedFields());
        if (!update.partial()) return BlockStreamUtils.mapValueFor(update.valueOrThrow());
        if (!update.getUnknownFields().isEmpty()) throw new IllegalArgumentException("Unknown partial metadata");
        final var choice = update.valueOrThrow().valueChoice();
        final var type = requireType(mapType(stateId, choice.kind().protoOrdinal()));
        return applyObject(type, prior, choice.value(), update.clearedFields());
    }

    public static Object applySingleton(int stateId, @Nullable Object prior, SingletonUpdateChange update) {
        requireMetadata(update.partial(), update.clearedFields());
        if (!update.partial()) return BlockStreamUtils.singletonPutFor(update);
        if (!update.getUnknownFields().isEmpty()) throw new IllegalArgumentException("Unknown partial metadata");
        final var type =
                requireType(singletonType(stateId, update.newValue().kind().protoOrdinal()));
        return applyObject(type, prior, update.newValue().value(), update.clearedFields());
    }

    private static Object applyObject(ValueType type, Object prior, Object partial, List<Integer> cleared) {
        if (prior == null) throw new IllegalArgumentException("Partial update requires a prior value");
        try {
            return type.applier.apply(
                    type.stateValue(prior),
                    type.stateValue(partial),
                    cleared.stream().mapToInt(Integer::intValue).toArray());
        } catch (UnsupportedOperationException unsupported) {
            throw new IllegalArgumentException("Unknown fields in a partial update", unsupported);
        }
    }

    public static Bytes applyBytes(
            @Nullable ValueType type,
            @Nullable Bytes prior,
            Bytes value,
            boolean partial,
            List<Integer> clearedFields) {
        requireMetadata(partial, clearedFields);
        if (!partial) return value;
        if (prior == null) throw new IllegalArgumentException("Partial update requires a prior value");
        return PartialStateChangeApplier.splice(requireType(type), prior, value, clearedFields);
    }

    public static boolean hasPartialUpdates(Block block) {
        return block.items().stream()
                .filter(item -> item.hasStateChanges())
                .flatMap(item -> item.stateChangesOrThrow().stateChanges().stream())
                .anyMatch(change ->
                        (change.hasMapUpdate() && change.mapUpdateOrThrow().partial())
                                || (change.hasSingletonUpdate()
                                        && change.singletonUpdateOrThrow().partial()));
    }

    public static void validateBlockDeltas(Block block) {
        for (var item : block.items()) {
            if (!item.hasStateChanges()) continue;
            for (var change : item.stateChangesOrThrow().stateChanges()) {
                if (change.hasMapUpdate()) {
                    var update = change.mapUpdateOrThrow();
                    requireMetadata(update.partial(), update.clearedFields());
                    if (update.partial()) {
                        requireType(mapType(
                                change.stateId(),
                                update.valueOrThrow().valueChoice().kind().protoOrdinal()));
                        if (!update.getUnknownFields().isEmpty())
                            throw new IllegalArgumentException("Unknown partial metadata");
                    }
                } else if (change.hasSingletonUpdate()) {
                    var update = change.singletonUpdateOrThrow();
                    requireMetadata(update.partial(), update.clearedFields());
                    if (update.partial()) {
                        requireType(singletonType(
                                change.stateId(), update.newValue().kind().protoOrdinal()));
                        if (!update.getUnknownFields().isEmpty())
                            throw new IllegalArgumentException("Unknown partial metadata");
                    }
                }
            }
        }
    }

    private static ValueType requireType(@Nullable ValueType type) {
        if (type == null) throw new IllegalArgumentException("Unknown partial value type");
        return type;
    }

    private static void requireProducerFull(boolean partial, List<Integer> clears) {
        if (partial || !clears.isEmpty()) throw new IllegalArgumentException("Producer input must be full");
    }

    private static void requireMetadata(boolean partial, List<Integer> clears) {
        if (!partial && !clears.isEmpty()) throw new IllegalArgumentException("Full updates cannot contain clears");
        int previous = 0;
        for (int field : clears) {
            if (field <= previous || field > 536870911)
                throw new IllegalArgumentException("Invalid or unordered clear");
            previous = field;
        }
    }
}
