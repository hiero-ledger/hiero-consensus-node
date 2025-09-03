// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.keyPrintCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newKeyCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
@HapiTestLifecycle
public class KeysCommandsTest {
    private static final String KEY_NAME = "newKey";
    private static final String[] FILES_TO_DELETE = {
        KEY_NAME + ".pass", KEY_NAME + ".pem", KEY_NAME + ".privkey", KEY_NAME + ".pubkey", KEY_NAME + ".words"
    };

    @HapiTest
    final Stream<DynamicTest> keyGenerationAndPrintWorks() {
        final var publicKey = new AtomicReference<String>();
        return hapiTest(
                yahcliKeys("gen-new", "-p", KEY_NAME + ".pem").exposingOutputTo(newKeyCapturer(publicKey::set)),
                // FUTURE: add some validation of the key files themselves
                sourcingContextual(spec -> yahcliKeys("print-keys", "-p", "newKey.pem")
                        .exposingOutputTo(keyPrintCapturer(printedKey ->
                                assertEquals(publicKey.get(), printedKey, "should print the same key")))));
    }

    @AfterAll
    public static void shutdown(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(withOpContext((spec, log) -> {
            for (String filename : FILES_TO_DELETE) {
                Path path = Paths.get(filename);
                try {
                    if (Files.exists(path)) {
                        Files.delete(path);
                        log.info("Deleted file: {}", filename);
                    }
                } catch (IOException e) {
                    log.error("Failed to delete file: {} - {}", filename, e.getMessage());
                }
            }
        }));
    }
}
