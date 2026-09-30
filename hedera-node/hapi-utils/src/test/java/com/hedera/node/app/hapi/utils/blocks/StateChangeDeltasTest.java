// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas.ValueType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.CopyBuilderTracking;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StateChangeDeltasTest {
    static Stream<ValueType> types() {
        return StateChangeDeltas.valueTypes().stream();
    }

    static Descriptor descriptor(ValueType type) {
        for (var field : com.hedera.hapi.block.stream.output.protoc.MapChangeValue.getDescriptor()
                .getFields()) {
            if (StateChangeDeltas.mapType(0, field.getNumber()) == type) return field.getMessageType();
        }
        for (var field : com.hedera.hapi.block.stream.output.protoc.SingletonUpdateChange.getDescriptor()
                .getFields()) {
            if (field.getContainingOneof() != null && StateChangeDeltas.singletonType(0, field.getNumber()) == type)
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
        assertThat(StateChangeDeltas.applyBytes(type, type.bytes(old), sparse, true, delta.clearedFields()))
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
        for (int tag : List.of(5, 25)) {
            long overhead = tag == 5 ? 2 : 3;
            if (!delta.clearedFields().isEmpty()) {
                long length = delta.clearedFields().stream()
                        .mapToLong(PartialStateChangeApplier::varintSize)
                        .sum();
                overhead += (tag == 5 ? 1 : 2) + PartialStateChangeApplier.varintSize(length) + length;
            }
            for (int min : List.of(0, 1, 32, 64, 128, 4096)) {
                assertThat(StateChangeDeltas.worthPartial(type, value, delta, min, tag))
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
                            tokenType,
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
    void malformedMetadataMissingBaselineAndWrongOneofInstructionsFail() {
        final var prior = Account.newBuilder()
                .memo("keep")
                .tinybarBalance(1)
                .stakedNodeId(7)
                .build();
        final var type = StateChangeDeltas.valueType(prior);
        final var balance =
                Account.PROTOBUF.toBytes(Account.newBuilder().tinybarBalance(2).build());
        assertThatThrownBy(() -> StateChangeDeltas.applyBytes(type, null, balance, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (var clears : List.of(List.of(5, 5), List.of(6, 5), List.of(0), List.of(999), List.of(5))) {
            assertThatThrownBy(() -> StateChangeDeltas.applyBytes(type, type.bytes(prior), balance, true, clears))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> StateChangeDeltas.applyBytes(type, null, balance, false, List.of(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StateChangeDeltas.applyBytes(
                        type,
                        type.bytes(prior),
                        Account.PROTOBUF.toBytes(Account.newBuilder()
                                .stakedAccountId(AccountID.DEFAULT)
                                .build()),
                        true,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String bytes : List.of("2800", "baffff", "2a0101", "32026b", "808000")) {
            assertThatThrownBy(() -> StateChangeDeltas.applyBytes(
                            type, type.bytes(prior), Bytes.fromHex(bytes), true, List.of()))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void oneofSwitchClearsOldAlternativeForByteAndObjectReplay() {
        final var prior = Account.newBuilder().memo("keep").stakedNodeId(7).build();
        final var next = prior.copyBuilder().stakedAccountId(AccountID.DEFAULT).build();
        final var delta = StateChangeDeltas.diff(prior, next);
        assertThat(delta.clearedFields()).containsExactly(11);
        final var type = StateChangeDeltas.valueType(prior);
        assertThat(StateChangeDeltas.applyBytes(
                        type, type.bytes(prior), type.bytes(delta.value()), true, delta.clearedFields()))
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
        assertThat(StateChangeDeltas.applyBytes(type, type.bytes(next), Bytes.EMPTY, true, List.of(11)))
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
