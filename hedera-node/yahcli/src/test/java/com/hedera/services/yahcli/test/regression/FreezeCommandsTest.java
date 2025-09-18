// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.freezeAbortIsSuccessful;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.scheduleFreezeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeAbort;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeOnly;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeUpgrade;
import static java.time.ZoneId.systemDefault;
import static java.time.temporal.ChronoUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class FreezeCommandsTest {

    @HapiTest
    final Stream<DynamicTest> readmeScheduleFreezeExample() {
        final var freezeDate = new AtomicReference<String>();
        final var fiveDaysFromNow = getFiveDaysFromNowString();
        return hapiTest(
                // vanilla freeze
                yahcliFreezeOnly("--start-time", fiveDaysFromNow)
                        .exposingOutputTo(scheduleFreezeCapturer(freezeDate::set)),
                doingContextual(spec -> assertEquals(freezeDate.get(), fiveDaysFromNow)),
                yahcliFreezeAbort().exposingOutputTo(freezeAbortIsSuccessful()),
                // freeze with upgrade
                yahcliFreezeUpgrade(
                                "--start-time",
                                fiveDaysFromNow,
                                "--upgrade-zip-hash",
                                "5d3b0e619d8513dfbf606ef00a2e83ba97d736f5f5ba61561d895ea83a6d4c34fce05d6cd74c83ec171f710e37e12aab")
                        .exposingOutputTo(output -> assertTrue(output.contains("NMT software upgrade in motion from"))),
                yahcliFreezeAbort().exposingOutputTo(freezeAbortIsSuccessful()));
    }

    private String getFiveDaysFromNowString() {
        final var fiveDaysFromNow = Instant.now().plus(5, DAYS);
        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd.HH:mm:ss").withZone(systemDefault());
        return formatter.format(fiveDaysFromNow);
    }
}
