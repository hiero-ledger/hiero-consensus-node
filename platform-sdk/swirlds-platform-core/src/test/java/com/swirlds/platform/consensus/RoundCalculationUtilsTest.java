/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.consensus;

import com.swirlds.platform.system.events.EventConstants;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RoundCalculationUtilsTest {

    @Test
    void getOldestNonAncientRound() {
        Assertions.assertEquals(
                6,
                RoundCalculationUtils.getOldestNonAncientRound(5, 10),
                "if the latest 5 rounds are ancient, then the oldest non-ancient is 6");
        Assertions.assertEquals(
                EventConstants.MINIMUM_ROUND_CREATED,
                RoundCalculationUtils.getOldestNonAncientRound(10, 5),
                "if no rounds are ancient yet, then the oldest one is the first round");
    }
}
