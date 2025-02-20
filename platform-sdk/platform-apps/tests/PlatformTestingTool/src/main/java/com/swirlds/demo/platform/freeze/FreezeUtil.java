// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.freeze;

import java.util.Calendar;
import java.util.TimeZone;

public class FreezeUtil {

    /**
     * get UTC Hour and Minutes from utcMillis
     * @param utcMillis
     * @return
     */
    public static int[] getUTCHourMinFromMillis(final long utcMillis) {
        int[] hourMin = new int[2];
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);
        hourMin[0] = cal.get(Calendar.HOUR_OF_DAY);
        hourMin[1] = cal.get(Calendar.MINUTE);
        return hourMin;
    }
}
