// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.schedule.entities;

/*
 * ‌
 * Hedera Services API Fees
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.KEY_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.usage.SigUsage;
import org.junit.jupiter.api.Test;

class ScheduleEntitySizesTest {
    private final ScheduleEntitySizes subject = ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;

    @Test
    void estimatesSigsAsExpected() {
        // expect:
        assertEquals(2, subject.estimatedScheduleSigs(new SigUsage(3, 100, 1)));
        assertEquals(1, subject.estimatedScheduleSigs(new SigUsage(3, 100, 10)));
    }

    @Test
    void estimatesSig() {
        // expect:
        assertEquals(7 * KEY_SIZE, subject.bytesUsedForSigningKeys(7));
    }
}
