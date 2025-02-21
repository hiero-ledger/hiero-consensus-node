/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.networkadmin.impl.test;

import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_KEY;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.UPGRADE_FILE_HASH_KEY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.hedera.node.app.service.networkadmin.impl.ReadableFreezeStoreImpl;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableFreezeStoreTest {
    @Mock(strictness = LENIENT)
    protected WritableStates writableStates;

    @Test
    void constructorCreatesFreezeState() {
        final WritableFreezeStore store = new WritableFreezeStore(writableStates);
        assertNotNull(store);
    }

    @Test
    void testNullConstructorArgs() {
        assertThrows(NullPointerException.class, () -> new WritableFreezeStore(null));
        assertThrows(NullPointerException.class, () -> new ReadableFreezeStoreImpl(null));
    }

    @Test
    void testFreezeTime() {
        final AtomicReference<ProtoBytes> freezeTimeBackingStore = new AtomicReference<>(null);
        when(writableStates.getSingleton(FREEZE_TIME_KEY))
                .then(invocation -> new FunctionWritableSingletonState<>(
                        FreezeService.NAME, FREEZE_TIME_KEY, freezeTimeBackingStore::get, freezeTimeBackingStore::set));
        final AtomicReference<ProtoBytes> lastFrozenBackingStore = new AtomicReference<>(null);
        final WritableFreezeStore store = new WritableFreezeStore(writableStates);

        // test with no freeze time set
        assertNull(store.freezeTime());

        // test with freeze time set
        final Timestamp freezeTime =
                Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build();
        store.freezeTime(freezeTime);
        assertEquals(freezeTime, store.freezeTime());
    }

    @Test
    void testUpdateFileHash() {
        final AtomicReference<ProtoBytes> backingStore = new AtomicReference<>(null);
        when(writableStates.getSingleton(UPGRADE_FILE_HASH_KEY))
                .then(invocation -> new FunctionWritableSingletonState<>(
                        FreezeService.NAME, UPGRADE_FILE_HASH_KEY, backingStore::get, backingStore::set));
        final WritableFreezeStore store = new WritableFreezeStore(writableStates);

        // test with no file hash set
        assertNull(store.updateFileHash());

        // test with file hash set
        store.updateFileHash(Bytes.wrap("test hash"));
        assertEquals(Bytes.wrap("test hash"), store.updateFileHash());

        // test with file hash set
        assertThatThrownBy(() -> store.updateFileHash(null)).isInstanceOf(NullPointerException.class);
        assertEquals(Bytes.wrap("test hash"), store.updateFileHash());
    }
}
