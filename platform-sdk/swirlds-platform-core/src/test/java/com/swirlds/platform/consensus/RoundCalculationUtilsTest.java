// SPDX-License-Identifier: Apache-2.0
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

    @Test
    void getMinGenNonAncient() {
        // generation is equal to round*10
        final Map<Long, Long> map =
                LongStream.range(1, 50).collect(HashMap::new, (m, l) -> m.put(l, l * 10), HashMap::putAll);
        Assertions.assertEquals(
                60,
                RoundCalculationUtils.getMinGenNonAncient(5, 10, map::get),
                "if the oldest non-ancient round is 6, then the generation should 60");
        Assertions.assertEquals(
                10,
                RoundCalculationUtils.getMinGenNonAncient(10, 5, map::get),
                "if no rounds are ancient yet, then the minGenNonAncient is the first round generation");
        Assertions.assertEquals(
                EventConstants.FIRST_GENERATION,
                RoundCalculationUtils.getMinGenNonAncient(10, 5, l -> EventConstants.GENERATION_UNDEFINED),
                "if no round generation is not defined yet, then the minGenNonAncient is the first generation");
    }
}
