// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleSign;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class ScheduleSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(ScheduleSuite.class);

    private final Map<String, String> specConfig;
    private final String scheduleId;

    public ScheduleSuite(final Map<String, String> specConfig, final String scheduleId) {
        this.specConfig = specConfig;
        this.scheduleId = scheduleId;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doSchedule());
    }

    final Stream<DynamicTest> doSchedule() {
        var schedule = new HapiScheduleSign(HapiSuite.DEFAULT_SHARD_REALM + scheduleId);
        return HapiSpec.customHapiSpec("DoSchedule")
                .withProperties(specConfig)
                .given()
                .when()
                .then(schedule);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
