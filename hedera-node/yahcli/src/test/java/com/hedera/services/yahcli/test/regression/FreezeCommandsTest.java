// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.freezeAbortIsSuccessful;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newFileHashCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.scheduleFreezeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeAbort;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeOnly;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeUpgrade;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliPrepareUpgrade;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliSysFiles;
import static java.time.ZoneId.systemDefault;
import static java.time.temporal.ChronoUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class FreezeCommandsTest {
    private static final String UPDATE_FILE_LOCATION =
            Path.of("build/resources/test/testFiles").toAbsolutePath().toString();

    @HapiTest
    final Stream<DynamicTest> readmeScheduleFreezeExample() {
        final var freezeDate = new AtomicReference<String>();
        final var fileHash = new AtomicReference<String>();
        final var fiveDaysFromNow = getFiveDaysFromNowString();
        return hapiTest(
                // vanilla freeze
                yahcliFreezeOnly("--start-time", fiveDaysFromNow)
                        .exposingOutputTo(scheduleFreezeCapturer(freezeDate::set)),
                doingContextual(spec -> assertEquals(freezeDate.get(), fiveDaysFromNow)),
                yahcliFreezeAbort().exposingOutputTo(freezeAbortIsSuccessful()),
                // stage an upgrade before scheduling freeze-upgrade
                yahcliSysFiles("upload", "-s", UPDATE_FILE_LOCATION, "software-zip"),
                yahcliSysFiles("hash-check", "software-zip").exposingOutputTo(newFileHashCapturer(fileHash::set)),
                doingContextual(spec -> assertTrue(!fileHash.get().isBlank())),
                doingContextual(spec -> allRunFor(spec, yahcliPrepareUpgrade("-f", "150", "-h", fileHash.get()))),
                // freeze with upgrade
                sourcing(
                        () -> yahcliFreezeUpgrade("--start-time", fiveDaysFromNow, "--upgrade-zip-hash", fileHash.get())
                                .exposingOutputTo(
                                        output -> assertTrue(output.contains("NMT software upgrade in motion from")))),
                yahcliFreezeAbort().exposingOutputTo(freezeAbortIsSuccessful()));
    }

    private String getFiveDaysFromNowString() {
        final var fiveDaysFromNow = Instant.now().plus(5, DAYS);
        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd.HH:mm:ss").withZone(systemDefault());
        return formatter.format(fiveDaysFromNow);
    }
}
