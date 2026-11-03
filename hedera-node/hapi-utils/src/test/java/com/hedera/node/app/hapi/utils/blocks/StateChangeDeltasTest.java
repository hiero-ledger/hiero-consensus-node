// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.schema.MapUpdateChangeSchema;
import com.hedera.hapi.block.stream.output.schema.SingletonUpdateChangeSchema;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas.ValueType;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.CopyBuilderTracked;
import com.hedera.pbj.runtime.DiffCodec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.CopyBuilderTracking;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StateChangeDeltasTest {
    @Test
    void catalogBackedBlockStateValuesHaveDiffCapabilityAndUnrelatedModelsHaveNone() {
        for (var type : types().toList()) {
            assertThat(type.codec()).as(type.name()).isInstanceOf(DiffCodec.class);
            // PBJ tracks only roots with more than 8 mask entries; smaller ones use the full-field comparison.
            assertThat(type.defaultValue() instanceof CopyBuilderTracked<?>)
                    .as(type.name())
                    .isEqualTo(maskEntries(descriptor(type)) > MAX_UNTRACKED_MASK_ENTRIES);
        }
        // Small, ubiquitous types must not carry per-instance tracking state everywhere they are nested.
        assertThat(CopyBuilderTracked.class.isAssignableFrom(AccountID.class)).isFalse();
        assertThat(CopyBuilderTracked.class.isAssignableFrom(com.hedera.hapi.node.base.Timestamp.class))
                .isFalse();
        assertThat(Account.DEFAULT).isInstanceOf(CopyBuilderTracked.class);
        assertThat(Token.DEFAULT).isInstanceOf(CopyBuilderTracked.class);
        assertThat(com.hedera.hapi.node.transaction.TransactionBody.PROTOBUF).isNotInstanceOf(DiffCodec.class);
        assertThat(com.hedera.hapi.block.stream.output.StateChange.PROTOBUF).isNotInstanceOf(DiffCodec.class);
        assertThat(com.hedera.hapi.node.base.Key.PROTOBUF).isNotInstanceOf(DiffCodec.class);
        assertThat(StateChangeTypes.PROTO_STRING.codec()).isNotInstanceOf(DiffCodec.class);
        assertThat(CopyBuilderTracked.class.isAssignableFrom(com.hedera.hapi.node.state.primitives.ProtoString.class))
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void adapterWithoutDiffCapabilityCanReplayPartials() {
        final Codec<Account> plain = mock(Codec.class, delegatesTo(Account.PROTOBUF));
        final var prior =
                Account.newBuilder().memo("x".repeat(200)).tinybarBalance(1).build();
        final var next = prior.copyBuilder().tinybarBalance(2).build();
        final var adapter = new ValueType("account", Account.class, plain);
        assertThat(adapter.canDiff(next)).isFalse();
        final var delta = StateChangeDeltas.diff(prior, next);
        assertThat(adapter.applyPartial(prior, delta.value(), delta.clearedFields()))
                .isEqualTo(next);
    }

    @Test
    void protoStringWithoutDiffApisKeepsFullOutputAndCanReplayIncomingPartials() {
        final var prior = new com.hedera.hapi.node.state.primitives.ProtoString("old".repeat(100));
        final var next = new com.hedera.hapi.node.state.primitives.ProtoString("new");
        assertThat(StateChangeDeltas.diff(prior, next)).isNull();
        final var map = MapUpdateChange.newBuilder()
                .key(MapChangeKey.newBuilder().protoStringKey("key"))
                .value(MapChangeValue.newBuilder().protoStringValue(next.value()))
                .build();
        assertThat(StateChangeDeltas.encodeMap(0, prior, map, 0)).isSameAs(map);
        assertThat(MapUpdateChange.PROTOBUF.toBytes(map)).isNotNull();
        final var singleton = com.hedera.hapi.block.stream.output.SingletonUpdateChange.newBuilder()
                .stringValue(next.value())
                .build();
        assertThat(StateChangeDeltas.encodeSingleton(0, prior, singleton, 0)).isSameAs(singleton);
        assertThat(com.hedera.hapi.block.stream.output.SingletonUpdateChange.PROTOBUF.toBytes(singleton))
                .isNotNull();
        final var cleared = com.hedera.hapi.node.state.primitives.ProtoString.DEFAULT;
        assertThat(StateChangeTypes.PROTO_STRING.applyPartial(prior, cleared, List.of(1)))
                .isEqualTo(cleared);
        assertThat(StateChangeDeltas.applyBytes(
                        StateChangeTypes.PROTO_STRING.bytes(prior), Bytes.EMPTY, true, List.of(1)))
                .isEqualTo(Bytes.EMPTY);
    }

    /** Mirrors PBJ's {@code LookupHelper.MAX_UNTRACKED_DIFF_ENTRIES}. */
    private static final int MAX_UNTRACKED_MASK_ENTRIES = 8;

    /** One mask entry per ordinary or map field, and one per oneof group. */
    static int maskEntries(Descriptor descriptor) {
        int entries = descriptor.getRealOneofs().size();
        for (FieldDescriptor field : descriptor.getFields()) {
            if (field.getRealContainingOneof() == null) entries++;
        }
        return entries;
    }

    static Stream<ValueType> types() {
        // ProtoString is a wire adapter only, outside the registered state-value catalog.
        return StateChangeDeltas.valueTypes().stream().filter(type -> type != StateChangeTypes.PROTO_STRING);
    }

    static Descriptor descriptor(ValueType type) {
        for (var field : com.hedera.hapi.block.stream.output.protoc.MapChangeValue.getDescriptor()
                .getFields()) {
            if (StateChangeDeltas.mapType(field.getNumber()) == type) return field.getMessageType();
        }
        for (var field : com.hedera.hapi.block.stream.output.protoc.SingletonUpdateChange.getDescriptor()
                .getFields()) {
            if (field.getContainingOneof() != null && StateChangeDeltas.singletonType(field.getNumber()) == type)
                return field.getMessageType();
        }
        throw new AssertionError(type);
    }

    @ParameterizedTest
    @MethodSource("types")
    void everyTopLevelFieldRoundTripsIncludingClearsAndPresence(ValueType type) throws Exception {
        final var descriptor = descriptor(type);
        for (var field : descriptor.getFields()) {
            final var before = DynamicMessage.newBuilder(descriptor);
            set(before, field, 1, 1);
            final var after = DynamicMessage.newBuilder(descriptor);
            set(after, field, 2, 1);
            roundTrip(type, before.build(), after.build());
            roundTrip(type, before.build(), DynamicMessage.getDefaultInstance(descriptor));
            roundTrip(type, DynamicMessage.getDefaultInstance(descriptor), before.build());
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !field.isRepeated()) {
                roundTrip(
                        type,
                        before.build(),
                        DynamicMessage.newBuilder(descriptor)
                                .setField(field, DynamicMessage.getDefaultInstance(field.getMessageType()))
                                .build());
            }
        }
        for (var oneof : descriptor.getOneofs()) {
            for (var from : oneof.getFields())
                for (var to : oneof.getFields()) {
                    final var before = DynamicMessage.newBuilder(descriptor);
                    final var after = DynamicMessage.newBuilder(descriptor);
                    set(before, from, 1, 1);
                    set(after, to, 0, 1); // Includes selected zero-valued oneof members.
                    roundTrip(type, before.build(), after.build());
                }
        }
    }

    @ParameterizedTest
    @MethodSource("types")
    void randomizedValuesMatchIndependentProtocReplayAndSavingsSum(ValueType type) throws Exception {
        final var descriptor = descriptor(type);
        final var random = new Random(917);
        for (int i = 0; i < 30; i++) {
            final var before = DynamicMessage.newBuilder(descriptor);
            for (var field : descriptor.getFields()) if (random.nextBoolean()) set(before, field, 1, 1);
            final var after = before.clone();
            for (var field : descriptor.getFields()) {
                int action = random.nextInt(4);
                if (action == 0) after.clearField(field);
                else if (action == 1) set(after, field, 2, 1);
            }
            roundTrip(type, before.build(), after.build());
        }
    }

    private static void roundTrip(ValueType type, DynamicMessage prior, DynamicMessage next) throws Exception {
        final var old = type.parse(Bytes.wrap(prior.toByteArray()));
        final var value = type.parse(Bytes.wrap(next.toByteArray()));
        final var delta = StateChangeDeltas.diff(old, value);
        assertThat(delta).as(type.name()).isNotNull();
        final var sparse = type.bytes(delta.value());
        assertThat(StateChangeDeltas.applyBytes(type.bytes(old), sparse, true, delta.clearedFields()))
                .as(type.name())
                .isEqualTo(type.bytes(value));
        final var descriptor = descriptor(type);
        assertThat(IndependentStateChangeReplay.reference(
                                DynamicMessage.parseFrom(
                                        descriptor, type.bytes(old).toByteArray()),
                                DynamicMessage.parseFrom(descriptor, sparse.toByteArray()),
                                delta.clearedFields())
                        .toByteArray())
                .isEqualTo(type.bytes(value).toByteArray());
        final var applied = type.applyPartial(old, delta.value(), delta.clearedFields());
        assertThat(applied).isEqualTo(value);
        assertThat(type.bytes(applied)).isEqualTo(type.bytes(value));
        final long omitted = type.bytes(value).length() - sparse.length();
        // Account for the different tag lengths of map and singleton metadata.
        for (final var fields : List.of(
                List.of(MapUpdateChangeSchema.PARTIAL, MapUpdateChangeSchema.CLEARED_FIELDS),
                List.of(SingletonUpdateChangeSchema.PARTIAL, SingletonUpdateChangeSchema.CLEARED_FIELDS))) {
            final var partialField = fields.get(0);
            final var clearedField = fields.get(1);
            long overhead = StateChangeDeltas.varintSize((long) partialField.number() << 3) + 1;
            if (!delta.clearedFields().isEmpty()) {
                long length = delta.clearedFields().stream()
                        .mapToLong(StateChangeDeltas::varintSize)
                        .sum();
                overhead += StateChangeDeltas.varintSize(((long) clearedField.number() << 3) | 2)
                        + StateChangeDeltas.varintSize(length)
                        + length;
            }
            for (int min : List.of(0, 1, 32, 64, 128, 4096)) {
                assertThat(StateChangeDeltas.worthPartial(type, value, delta, min, partialField, clearedField))
                        .as("%s threshold %s", type.name(), min)
                        .isEqualTo(omitted >= min + overhead);
            }
        }
    }

    static void set(DynamicMessage.Builder builder, FieldDescriptor field, int seed, int depth) {
        final Object value =
                switch (field.getJavaType()) {
                    case INT -> seed;
                    case LONG -> (long) seed;
                    case FLOAT -> (float) seed;
                    case DOUBLE -> (double) seed;
                    case BOOLEAN -> seed % 2 == 1;
                    case STRING -> "value-" + seed;
                    case BYTE_STRING -> ByteString.copyFromUtf8("bytes-" + seed);
                    case ENUM ->
                        field.getEnumType()
                                .getValues()
                                .get(seed % field.getEnumType().getValues().size());
                    case MESSAGE -> {
                        final var child = DynamicMessage.newBuilder(field.getMessageType());
                        if (depth > 0)
                            for (var nested : field.getMessageType().getFields()) set(child, nested, seed, depth - 1);
                        yield child.build();
                    }
                };
        if (field.isRepeated()) builder.setField(field, seed == 0 ? List.of() : List.of(value, value));
        else builder.setField(field, value);
    }

    @Test
    void minimumSavingsIsInclusiveAndAccountsForDifferentWrapperTagSizes() {
        final var before =
                Account.newBuilder().tinybarBalance(1).memo("m".repeat(64)).build();
        final var next = before.copyBuilder().tinybarBalance(2).build();
        assertThat(StateChangeDeltas.encodeMap(9999, before, full(next), 64).partial())
                .isTrue(); // 66 - 2
        assertThat(StateChangeDeltas.encodeMap(9999, before, full(next), 65).partial())
                .isFalse();
        assertThat(StateChangeDeltas.encodeMap(9999, null, full(next), 0).partial())
                .isFalse();
        assertThatThrownBy(() -> StateChangeDeltas.encodeMap(2, before, full(next), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copyBuilderChainsAndParsedValuesProduceIdenticalMasksAndBytes() throws Exception {
        final var root = Account.newBuilder()
                .memo("m".repeat(100))
                .tinybarBalance(1)
                .stakedNodeId(7)
                .build();
        final var middle = root.copyBuilder().tinybarBalance(2).build();
        final var builder =
                middle.copyBuilder().tinybarBalance(1).memo("changed").stakedAccountId(AccountID.DEFAULT);
        final var next = builder.build();
        final var type = StateChangeDeltas.valueType(root);
        final var parsedNext = (Account) type.parse(type.bytes(next));
        final var fromBuilder = StateChangeDeltas.diff(root, next);
        final var fallback = StateChangeDeltas.diff(root, parsedNext);
        assertThat(fromBuilder).isEqualTo(fallback);
        assertThat(((Account) fromBuilder.value()).tinybarBalance()).isZero(); // Restored setter is omitted.
        assertThat(fromBuilder.clearedFields()).doesNotContain(5);
        assertThat(MapUpdateChange.PROTOBUF.toBytes(StateChangeDeltas.encodeMap(2, root, full(next), 0)))
                .isEqualTo(MapUpdateChange.PROTOBUF.toBytes(StateChangeDeltas.encodeMap(2, root, full(parsedNext), 0)));
        builder.clearStakedId();
        final var cleared = builder.build();
        assertThat(StateChangeDeltas.diff(root, cleared))
                .isEqualTo(StateChangeDeltas.diff(root, type.parse(type.bytes(cleared))));
    }

    @Test
    void optionalTrackingAdapterPreservesCanonicalOutputAndRejectsStaleOrigins() {
        final var root = Account.newBuilder()
                .tinybarBalance(1)
                .memo("m".repeat(100))
                .stakedNodeId(7)
                .build();
        final var next = root.copyBuilder()
                .tinybarBalance(2)
                .tinybarBalance(1)
                .memo("changed")
                .stakedAccountId(AccountID.DEFAULT)
                .build();
        final var latest = root.copyBuilder().expirationSecond(100).build();
        final var expected = StateChangeDeltas.diff(root, next);
        final var expectedBytes = MapUpdateChange.PROTOBUF.toBytes(StateChangeDeltas.encodeMap(2, root, full(next), 0));
        final var expectedFromLatest = StateChangeDeltas.diff(latest, next);
        assertThat(CopyBuilderTracking.hasOrigin(next, root)).isTrue();
        assertThat(CopyBuilderTracking.hasOrigin(next, latest)).isFalse();
        assertThat(next.$copyBuilderFieldChanged(5)).isTrue();
        assertThat(next.$copyBuilderFieldChanged(4)).isFalse();
        assertThat(StateChangeDeltas.diff(root, next.$untracked())).isEqualTo(expected);
        assertThat(MapUpdateChange.PROTOBUF.toBytes(StateChangeDeltas.encodeMap(2, root, full(next), 0)))
                .isEqualTo(expectedBytes);
        // Expiration is absent from the candidate set, but the different prior forces comparison.
        assertThat(StateChangeDeltas.diff(latest, next)).isEqualTo(expectedFromLatest);
        assertThat(StateChangeDeltas.diff(latest, next.$untracked())).isEqualTo(expectedFromLatest);
    }

    @Test
    void staleOrReparsedBaselinesForceCompleteComparison() {
        final var root =
                Account.newBuilder().tinybarBalance(1).memo("m".repeat(100)).build();
        final var latest = root.copyBuilder().tinybarBalance(2).build();
        final var staleNext = root.copyBuilder().memo("new").build();
        final var delta = StateChangeDeltas.diff(latest, staleNext);
        assertThat(((Account) delta.value()).tinybarBalance()).isEqualTo(1);
        final var type = StateChangeDeltas.valueType(root);
        assertThat(StateChangeDeltas.diff(type.parse(type.bytes(latest)), staleNext))
                .isEqualTo(delta);
    }

    @Test
    void unknownFieldsRetainFullValuesAndNumericEnumOrdinalsRoundTrip() {
        final var known =
                Account.newBuilder().memo("m".repeat(100)).tinybarBalance(1).build();
        final var type = StateChangeDeltas.valueType(known);
        final var unknown = type.parse(Bytes.fromHex(type.bytes(known).toHex() + "a00601"));
        assertThat(StateChangeDeltas.diff(known, unknown)).isNull();
        assertThat(StateChangeDeltas.diff(unknown, known)).isNull();
        assertThat(StateChangeDeltas.encodeMap(2, unknown, full(known)).partial())
                .isFalse();
        final var fullUnknown = full((Account) unknown);
        assertThat(StateChangeDeltas.encodeMap(2, known, fullUnknown)).isSameAs(fullUnknown);
        final var unknownEnum =
                Token.newBuilder().tokenType(TokenType.UNRECOGNIZED).build();
        final var tokenType = StateChangeDeltas.valueType(unknownEnum);
        for (var values : List.of(List.of(Token.DEFAULT, unknownEnum), List.of(unknownEnum, Token.DEFAULT))) {
            final var delta = StateChangeDeltas.diff(values.getFirst(), values.getLast());
            assertThat(delta).isNotNull();
            assertThat(StateChangeDeltas.applyBytes(
                            tokenType.bytes(values.getFirst()),
                            tokenType.bytes(delta.value()),
                            true,
                            delta.clearedFields()))
                    .isEqualTo(tokenType.bytes(values.getLast()));
            assertThat(tokenType.bytes(tokenType.applyPartial(values.getFirst(), delta.value(), delta.clearedFields())))
                    .isEqualTo(tokenType.bytes(values.getLast()));
        }
    }

    @Test
    void signedAndUnsignedScalarExtremesRoundTrip() throws Exception {
        for (var type : StateChangeDeltas.valueTypes()) {
            final var descriptor = descriptor(type);
            for (var field : descriptor.getFields()) {
                if (field.getJavaType() != FieldDescriptor.JavaType.INT
                        && field.getJavaType() != FieldDescriptor.JavaType.LONG) continue;
                final var prior = DynamicMessage.newBuilder(descriptor);
                final var next = DynamicMessage.newBuilder(descriptor);
                Object minimum;
                Object maximum;
                if (field.getJavaType() == FieldDescriptor.JavaType.INT) {
                    minimum = Integer.MIN_VALUE;
                    maximum = Integer.MAX_VALUE;
                } else {
                    minimum = Long.MIN_VALUE;
                    maximum = Long.MAX_VALUE;
                }
                prior.setField(field, field.isRepeated() ? List.of(minimum) : minimum);
                next.setField(field, field.isRepeated() ? List.of(maximum) : maximum);
                roundTrip(type, prior.build(), next.build());
                roundTrip(type, next.build(), prior.build());
                roundTrip(type, prior.build(), DynamicMessage.getDefaultInstance(descriptor));
            }
        }
    }

    @Test
    void protobufFieldsAreWholeReplacementsIncludingFileAppendAndNestedKey() {
        final var oldFile = File.newBuilder()
                .contents(Bytes.wrap("a".repeat(1024)))
                .memo("m".repeat(100))
                .build();
        final var newFile = oldFile.copyBuilder()
                .contents(Bytes.wrap("a".repeat(1024) + "b"))
                .build();
        final var fileDelta = StateChangeDeltas.diff(oldFile, newFile);
        assertThat(((File) fileDelta.value()).contents()).isEqualTo(newFile.contents());
        final var prior = Account.newBuilder()
                .key(Key.newBuilder().ed25519(Bytes.wrap("old")))
                .memo("retained")
                .build();
        final var next = prior.copyBuilder()
                .key(Key.newBuilder().ecdsaSecp256k1(Bytes.wrap("new")))
                .build();
        final var delta = StateChangeDeltas.diff(prior, next);
        assertThat(((Account) delta.value()).key()).isEqualTo(next.key());
        assertThat(delta.clearedFields()).isEmpty();
    }

    @Test
    void malformedMetadataMissingBaselineAndInvalidWireFramingFail() {
        final var prior = Account.newBuilder()
                .memo("keep")
                .tinybarBalance(1)
                .stakedNodeId(7)
                .build();
        final var type = StateChangeDeltas.valueType(prior);
        final var balance =
                Account.PROTOBUF.toBytes(Account.newBuilder().tinybarBalance(2).build());
        assertThatThrownBy(() -> StateChangeDeltas.applyBytes(null, balance, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (var clears : List.of(List.of(5, 5), List.of(6, 5), List.of(0), List.of(536870912), List.of(5))) {
            assertThatThrownBy(() -> StateChangeDeltas.applyBytes(type.bytes(prior), balance, true, clears))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> StateChangeDeltas.applyBytes(null, balance, false, List.of(5)))
                .isInstanceOf(IllegalArgumentException.class);
        for (String bytes : List.of("baffff", "32026b", "808000", "00", "10010801", "0b")) {
            assertThatThrownBy(() ->
                            StateChangeDeltas.applyBytes(type.bytes(prior), Bytes.fromHex(bytes), true, List.of()))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void byteReplaySplicesOpaqueFieldsWithoutAPayloadSchema() {
        // Preserve field 1 and future field 100, replace field 2, clear field 3,
        // and add the entire repeated run for future field 99.
        final var prior = Bytes.fromHex("08011201611801a00601");
        final var partial = Bytes.fromHex("1201629a0601789a060179");
        assertThat(StateChangeDeltas.applyBytes(prior, partial, true, List.of(3)))
                .isEqualTo(Bytes.fromHex("08011201629a0601789a060179a00601"));
        // An absent field can be cleared without knowing whether the current schema declares it.
        assertThat(StateChangeDeltas.applyBytes(prior, Bytes.EMPTY, true, List.of(999)))
                .isEqualTo(prior);
    }

    @Test
    void oneofSwitchClearsOldAlternativeForByteAndObjectReplay() {
        final var prior = Account.newBuilder().memo("keep").stakedNodeId(7).build();
        final var next = prior.copyBuilder().stakedAccountId(AccountID.DEFAULT).build();
        final var delta = StateChangeDeltas.diff(prior, next);
        assertThat(delta.clearedFields()).containsExactly(11);
        final var type = StateChangeDeltas.valueType(prior);
        assertThat(StateChangeDeltas.applyBytes(
                        type.bytes(prior), type.bytes(delta.value()), true, delta.clearedFields()))
                .isEqualTo(type.bytes(next));
        final var update = full((Account) delta.value())
                .copyBuilder()
                .partial(true)
                .clearedFields(delta.clearedFields())
                .build();
        assertThat(StateChangeDeltas.applyMap(2, prior, update)).isEqualTo(next);
        assertThatThrownBy(() -> StateChangeDeltas.applyMap(
                        2, prior, update.copyBuilder().clearedFields(List.of()).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StateChangeDeltas.applyBytes(type.bytes(next), Bytes.EMPTY, true, List.of(11)))
                .isEqualTo(type.bytes(next)); // Clearing an absent alternative is idempotent.
        assertThat(type.applyPartial(next, Account.DEFAULT, List.of(11))).isEqualTo(next);
    }

    static MapUpdateChange full(Account value) {
        return MapUpdateChange.newBuilder()
                .key(MapChangeKey.newBuilder().accountIdKey(AccountID.DEFAULT))
                .value(MapChangeValue.newBuilder().accountValue(value))
                .build();
    }
}
