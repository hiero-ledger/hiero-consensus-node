// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map.lifecycle;

import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ExpectedValueTest {

    @Test
    void getDefaultUidTest() {
        final ExpectedValue value = new ExpectedValue(null, null);
        Assertions.assertEquals(0, value.getUid(), "No uid set, so default for long should be returned");
    }

    @Test
    void getAssignedUidTest() {
        final Random random = new Random();
        final long uid = random.nextLong();
        final ExpectedValue value = new ExpectedValue(null, null, false, null, null, null, uid);
        Assertions.assertEquals(uid, value.getUid(), "Uid assigned should match");
    }

    @Test
    void getSetUidTest() {
        final Random random = new Random();
        final long uid = random.nextLong();
        final ExpectedValue value = new ExpectedValue(null, null);
        value.setUid(uid);
        Assertions.assertEquals(uid, value.getUid(), "Uid set should match");
    }
}
