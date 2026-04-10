// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.AccountID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link NodeRewardActivity}. */
class NodeRewardActivityTest {

    private static final AccountID ACCOUNT =
            AccountID.newBuilder().accountNum(1001L).build();

    @Test
    void testNullAccountIdThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new NodeRewardActivity(1L, null, 0, 100, 80));
    }

    /**
     * Verifies activePercent() across representative inputs.
     * minJudgeRoundPercentage is irrelevant to this calculation and is fixed at 80.
     */
    @ParameterizedTest(name = "{0} missed / {1} rounds → {2}%")
    @CsvSource({
        // rounds=0: guard against division by zero
        "0,   0,   0.0",
        // 0 missed: always fully active
        "0,   100, 100.0",
        // partial activity
        "5,   100, 95.0",
        "20,  100, 80.0",
        "30,  100, 70.0",
        // missed > rounds: Math.max clamp prevents negative result
        "150, 100, 0.0",
    })
    void testActivePercent(long numMissed, long rounds, double expectedPercent) {
        final var activity = new NodeRewardActivity(1L, ACCOUNT, numMissed, rounds, 80);
        assertEquals(expectedPercent, activity.activePercent());
    }

    @Test
    void testActivePercentFractionalResult() {
        // 1 missed / 3 rounds → 66.666…% — kept separate because it needs a delta assertion
        final var activity = new NodeRewardActivity(1L, ACCOUNT, 1, 3, 0);
        assertEquals(((double) (2 * 100)) / 3, activity.activePercent(), 1e-9);
    }
}
