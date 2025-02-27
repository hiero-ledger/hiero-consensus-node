// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.schemas;

import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.copyToLeftPaddedByteArray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.service.contract.impl.schemas.V0500ContractSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("V050 storage links repair")
@ExtendWith(MockitoExtension.class)
class V0500ContractSchemaTest {
    private static final int N = 3;
    private static final String SHARED_VALUES_KEY = "V0500_FIRST_STORAGE_KEYS";
    private static final Bytes[] TEST_KEYS = LongStream.range(1, 1 + N)
            .mapToObj(i -> copyToLeftPaddedByteArray(i, new byte[32]))
            .map(Bytes::wrap)
            .sorted()
            .toArray(Bytes[]::new);
    private final Map<String, Object> sharedValues = new HashMap<>();
    private final Map<SlotKey, SlotValue> storage = new HashMap<>();
    private final MapWritableKVState<SlotKey, SlotValue> writableStorage =
            new MapWritableKVState<>(STORAGE_KEY, storage);
    private final MapReadableKVState<SlotKey, SlotValue> readableStorage =
            new MapReadableKVState<>(STORAGE_KEY, storage);
    private final MapReadableStates readableStates =
            MapReadableStates.builder().state(readableStorage).build();
    private final MapWritableStates writableStates =
            MapWritableStates.builder().state(writableStorage).build();

    @Mock
    private MigrationContext ctx;

    private final V0500ContractSchema subject = new V0500ContractSchema();

    @BeforeEach
    void setUp() {
        given(ctx.previousStates()).willReturn(readableStates);
        given(ctx.sharedValues()).willReturn(sharedValues);
    }

    @Test
    @DisplayName("preserves intact links as-is")
    void noChangeToIntactLinks() {
        for (int i = N - 1; i >= 0; i--) {
            addMapping(
                    TEST_KEYS[i], i == N - 1 ? Bytes.EMPTY : TEST_KEYS[i + 1], i == 0 ? Bytes.EMPTY : TEST_KEYS[i - 1]);
        }

        subject.migrate(ctx);
        writableStates.commit();

        assertThat(firstKeys()).containsEntry(CALLED_CONTRACT_ID, TEST_KEYS[N - 1]);
        assertIntactLinksInOrder(
                Arrays.stream(TEST_KEYS).sorted(Comparator.reverseOrder()).toArray(Bytes[]::new));
    }

    @Test
    @DisplayName("fixes no first mapping")
    void fixedMissingFirstMapping() {
        given(ctx.newStates()).willReturn(writableStates);
        for (int i = N - 1; i >= 0; i--) {
            addMapping(TEST_KEYS[i], TEST_KEYS[0], i == 0 ? Bytes.EMPTY : TEST_KEYS[i - 1]);
        }

        subject.migrate(ctx);
        writableStates.commit();

        assertFixedInLexicographicOrder();
    }

    @Test
    @DisplayName("fixes no last mapping")
    void fixedMissingLastMapping() {
        given(ctx.newStates()).willReturn(writableStates);
        for (int i = 0; i < N; i++) {
            addMapping(TEST_KEYS[i], i == 0 ? Bytes.EMPTY : TEST_KEYS[i - 1], TEST_KEYS[N - 1]);
        }

        subject.migrate(ctx);
        writableStates.commit();

        assertFixedInLexicographicOrder();
    }

    @Test
    @DisplayName("fixes wrong prev pointer")
    void fixedWrongPrevPointer() {
        given(ctx.newStates()).willReturn(writableStates);
        for (int i = 0; i < N; i++) {
            if (i == 1) {
                addMapping(TEST_KEYS[i], TEST_KEYS[N - 1], TEST_KEYS[N - 1]);
            } else {
                addMapping(
                        TEST_KEYS[i],
                        i == 0 ? Bytes.EMPTY : TEST_KEYS[i - 1],
                        i == N - 1 ? Bytes.EMPTY : TEST_KEYS[i + 1]);
            }
        }

        subject.migrate(ctx);
        writableStates.commit();

        assertFixedInLexicographicOrder();
    }

    @Test
    @DisplayName("fixes unreachable mapping")
    void fixedUnreachableMapping() {
        given(ctx.newStates()).willReturn(writableStates);
        for (int i = 0; i < N; i++) {
            if (i == 1) {
                addMapping(TEST_KEYS[i], TEST_KEYS[0], Bytes.EMPTY);
            } else {
                addMapping(
                        TEST_KEYS[i],
                        i == 0 ? Bytes.EMPTY : TEST_KEYS[i - 1],
                        i == N - 1 ? Bytes.EMPTY : TEST_KEYS[i + 1]);
            }
        }

        subject.migrate(ctx);
        writableStates.commit();

        assertFixedInLexicographicOrder();
    }

    @Test
    @DisplayName("fixes loop")
    void fixedLoop() {
        given(ctx.newStates()).willReturn(writableStates);
        for (int i = 0; i < N; i++) {
            if (i == 1) {
                addMapping(TEST_KEYS[i], TEST_KEYS[0], TEST_KEYS[1]);
            } else {
                addMapping(
                        TEST_KEYS[i],
                        i == 0 ? Bytes.EMPTY : TEST_KEYS[i - 1],
                        i == N - 1 ? Bytes.EMPTY : TEST_KEYS[i + 1]);
            }
        }

        subject.migrate(ctx);
        writableStates.commit();

        assertFixedInLexicographicOrder();
    }

    private void assertFixedInLexicographicOrder() {
        assertThat(firstKeys()).containsEntry(CALLED_CONTRACT_ID, TEST_KEYS[0]);
        assertIntactLinksInOrder(TEST_KEYS);
    }

    private void assertIntactLinksInOrder(@NonNull final Bytes... keys) {
        for (int i = 0; i < keys.length; i++) {
            final var key = keys[i];
            final var prevKey = i == 0 ? Bytes.EMPTY : keys[i - 1];
            final var nextKey = i == keys.length - 1 ? Bytes.EMPTY : keys[i + 1];
            assertThat(storage)
                    .containsEntry(new SlotKey(CALLED_CONTRACT_ID, key), new SlotValue(key, prevKey, nextKey));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<ContractID, Bytes> firstKeys() {
        return (Map<ContractID, Bytes>) sharedValues.get(SHARED_VALUES_KEY);
    }

    private void addMapping(@NonNull final Bytes key, @NonNull final Bytes prevKey, @NonNull final Bytes nextKey) {
        final var slotKey = new SlotKey(CALLED_CONTRACT_ID, key);
        final var slotValue = new SlotValue(key, prevKey, nextKey);
        storage.put(slotKey, slotValue);
    }
}
