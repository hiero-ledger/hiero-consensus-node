// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.schedule;

import static com.hedera.services.bdd.spec.HapiPropertySource.asScheduleString;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.ScheduleInfo;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ScheduleFeeUtils {
    static final Logger log = LogManager.getLogger(ScheduleFeeUtils.class);

    static ScheduleInfo lookupInfo(HapiSpec spec, String schedule, boolean loggingOff) throws Throwable {
        var subOp = getScheduleInfo(schedule).noLogging();
        Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            if (!loggingOff) {
                var literal = asScheduleString(spec.registry().getScheduleId(schedule));
                log.warn("Unable to look up {}", literal, error.get());
            }
            throw error.get();
        }
        return subOp.getResponse().getScheduleGetInfo().getScheduleInfo();
    }
}
