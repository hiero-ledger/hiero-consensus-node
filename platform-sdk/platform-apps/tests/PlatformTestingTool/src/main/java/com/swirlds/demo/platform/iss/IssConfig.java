// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.iss;

import java.time.Duration;
import java.time.Instant;

public class IssConfig {
    private int issSecondsAfterStart;

    public int getIssSecondsAfterStart() {
        return issSecondsAfterStart;
    }

    public void setIssSecondsAfterStart(int issSecondsAfterStart) {
        this.issSecondsAfterStart = issSecondsAfterStart;
    }

    public boolean shouldSendIssTransaction(Instant startTime) {
        return Duration.between(startTime, Instant.now()).getSeconds() > issSecondsAfterStart;
    }
}
