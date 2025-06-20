// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.freeze;

import java.util.Calendar;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

public class FreezeUtilTest {
    @Test
    public void convertUTCMillisTest() {
        long utcMillis = 1554331942000l;
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);
        assert cal.get(Calendar.YEAR) == 2019;
        assert cal.get(Calendar.HOUR) == 10;
        assert cal.get(Calendar.HOUR_OF_DAY) == 22;
        assert cal.get(Calendar.MINUTE) == 52;
    }

    @Test
    public void getUTCHourMinFromMillisTest() {
        long utcMillis = 1554331942000l;
        int[] hourMin = FreezeUtil.getUTCHourMinFromMillis(utcMillis);
        assert hourMin[0] == 22;
        assert hourMin[1] == 52;
    }
}
