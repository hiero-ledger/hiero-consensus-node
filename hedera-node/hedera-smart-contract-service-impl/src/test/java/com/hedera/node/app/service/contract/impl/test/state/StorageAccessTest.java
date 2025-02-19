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

package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.state.StorageAccess.StorageAccessType.ZERO_INTO_EMPTY_SLOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

class StorageAccessTest {
    @Test
    void distinguishesBetweenReadAndMutation() {
        final var read = StorageAccess.newRead(UInt256.ONE, UInt256.MIN_VALUE);
        final var write = StorageAccess.newWrite(UInt256.ONE, UInt256.MIN_VALUE, UInt256.MAX_VALUE);

        assertTrue(read.isReadOnly());
        assertFalse(write.isReadOnly());
    }

    @Test
    void readsAreNotRemovalsOrInsertions() {
        final var read = StorageAccess.newRead(UInt256.ONE, UInt256.MIN_VALUE);
        assertFalse(read.isRemoval());
        assertFalse(read.isInsertion());
    }

    @Test
    void zeroIsOnlyRemovalIfExistingValueNotZero() {
        final var reZero = StorageAccess.newWrite(UInt256.ONE, UInt256.ZERO, UInt256.ZERO);
        assertFalse(reZero.isRemoval());
        assertTrue(reZero.isUpdate());
        assertTrue(reZero.isZeroIntoEmptySlot());
        assertEquals(ZERO_INTO_EMPTY_SLOT, StorageAccess.StorageAccessType.getAccessType(reZero));
    }

    @Test
    void nonZeroIsOnlyInsertionIfExistingValueZero() {
        final var reZero = StorageAccess.newWrite(UInt256.ONE, UInt256.MAX_VALUE, UInt256.MAX_VALUE);
        assertFalse(reZero.isInsertion());
    }

    @Test
    void anyNonZeroWriteIsAnUpdate() {
        final var reZero = StorageAccess.newWrite(UInt256.ONE, UInt256.MAX_VALUE, UInt256.ONE);
        assertTrue(reZero.isUpdate());
        assertFalse(reZero.isZeroIntoEmptySlot());
    }
}
