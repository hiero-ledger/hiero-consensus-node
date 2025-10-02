// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.pauses;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiSpecSleep extends UtilOp {
    static final Logger log = LogManager.getLogger(HapiSpecSleep.class);

    private final long timeMs;

    public HapiSpecSleep(final long timeMs) {
        this.timeMs = timeMs;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        if (!loggingOff) {
            log.info("Sleeping for {}ms now...", timeMs);
        }
        spec.sleepConsensusTime(Duration.ofMillis(timeMs));
        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("timeMs", timeMs).toString();
    }
}
