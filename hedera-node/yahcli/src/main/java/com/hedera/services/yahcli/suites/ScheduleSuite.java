// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleSign;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class ScheduleSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(ScheduleSuite.class);

    private final ConfigManager configManager;
    private final String scheduleId;
    private final String keyFilePath;

    public ScheduleSuite(final ConfigManager configManager, final String scheduleId, final String keyFilePath) {
        this.configManager = configManager;
        this.scheduleId = scheduleId;
        this.keyFilePath = keyFilePath;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doSchedule());
    }

    final Stream<DynamicTest> doSchedule() {
        final var fqScheduleId = asEntityString(
                configManager.shard().getShardNum(), configManager.realm().getRealmNum(), scheduleId);
        final var scheduleSign = new HapiScheduleSign(fqScheduleId);
        final var opsList = new ArrayList<SpecOperation>();

        if ("<N/A>".equals(keyFilePath)) {
            opsList.add(scheduleSign);
        } else {
            final var currKey = "currKey";
            opsList.add(UtilVerbs.keyFromFile(currKey, keyFilePath));
            opsList.add(scheduleSign.alsoSigningWith(currKey));
        }
        final var spec = new HapiSpec(
                "DoSchedule",
                new MapPropertySource(configManager.asSpecConfig()),
                opsList.toArray(new SpecOperation[0]));
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
