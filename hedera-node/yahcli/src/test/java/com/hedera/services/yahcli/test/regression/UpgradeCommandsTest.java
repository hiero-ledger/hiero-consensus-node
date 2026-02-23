// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newFileHashCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliFreezeUpgrade;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliPrepareUpgrade;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliSysFiles;
import static java.time.ZoneId.systemDefault;
import static java.time.temporal.ChronoUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class UpgradeCommandsTest {

    private static final String UPDATE_FILE_LOCATION =
            Path.of("build/resources/test/testFiles").toAbsolutePath().toString();

    @HapiTest
    final Stream<DynamicTest> upgradeFlowTest() {
        final var fileHash = new AtomicReference<String>();
        return hapiTest(
                yahcliSysFiles("upload", "-s", UPDATE_FILE_LOCATION, "software-zip"),
                yahcliSysFiles("hash-check", "software-zip").exposingOutputTo(newFileHashCapturer(fileHash::set)),
                // assert hashes,
                withOpContext((spec, log) -> assertEquals(
                        sha384Hex(UPDATE_FILE_LOCATION + "/softwareUpgrade.zip"),
                        fileHash.get(),
                        "File hash not as expected")),
                // prepare NMT upgrade
                sourcingContextual(spec -> yahcliPrepareUpgrade("-f", "150", "-h", fileHash.get())),
                // schedule Freeze upgrade
                yahcliFreezeUpgrade(
                        "--start-time",
                        getFiveDaysFromNowString(),
                        "--upgrade-zip-hash",
                        "5d3b0e619d8513dfbf606ef00a2e83ba97d736f5f5ba61561d895ea83a6d4c34fce05d6cd74c83ec171f710e37e12aab"));
    }

    public static String sha384Hex(String filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-384");
        try (InputStream is = new FileInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String getFiveDaysFromNowString() {
        final var fiveDaysFromNow = Instant.now().plus(5, DAYS);
        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd.HH:mm:ss").withZone(systemDefault());
        return formatter.format(fiveDaysFromNow);
    }
}
