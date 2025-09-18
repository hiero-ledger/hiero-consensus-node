// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.DEFAULT_WORKING_DIR;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.TEST_NETWORK;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newAccountCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.publicKeyCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.scheduleIdCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliAccounts;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliScheduleSign;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
@OrderedInIsolation
public class ScheduleCommandsTest {

    @Order(0)
    @HapiTest
    final Stream<DynamicTest> readmeScheduleCreateExample() {
        final var newAccountNum = new AtomicLong();
        final var newScheduleId = new AtomicReference<String>();
        return hapiTest(

                // Create an account with yahcli (fails if yahcli exits with a non-zero return code)
                yahcliAccounts("create", "-S")
                        // Capture the new account number from the yahcli output
                        .exposingOutputTo(newAccountCapturer(newAccountNum::set)),
                sourcingContextual(spec -> getAccountInfo(
                                asAccountString(spec.accountIdFactory().apply(newAccountNum.get())))
                        .has(accountWith().receiverSigReq(true))),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliAccounts(
                                        "send",
                                        "--to",
                                        asAccountString(spec.accountIdFactory().apply(newAccountNum.get())),
                                        "--memo",
                                        "\"Never gonna give you up\"",
                                        String.valueOf(5))
                                .schedule()
                                .exposingOutputTo(scheduleIdCapturer(newScheduleId::set)))),
                doingContextual(spec -> allRunFor(spec, getScheduleInfo(newScheduleId.get()))));
    }

    @Order(1)
    @HapiTest
    final Stream<DynamicTest> readmeSchedulingKeyListUpdateExample() {
        // account num
        final var accountNumR = new AtomicLong();
        final var accountNumT = new AtomicLong();
        final var accountNumS = new AtomicLong();
        // account R key
        final var publicKeyR = new AtomicReference<String>();
        final var keyR = new AtomicReference<Key>();
        // account T key
        final var publicKeyT = new AtomicReference<String>();
        final var keyT = new AtomicReference<Key>();
        // schedule arg
        final var newKeyFilePath = new AtomicReference<String>();
        // schedule num
        final var scheduleId = new AtomicReference<String>();
        // account S new key
        final var actualKeyS = new AtomicReference<Key>();

        return hapiTest(
                // create 3 accounts
                yahcliAccounts("create", "-S", "-d", "hbar", "-a", "1")
                        .exposingOutputTo(newAccountCapturer(accountNumR::set)),
                yahcliAccounts("create", "-S", "-d", "hbar", "-a", "1")
                        .exposingOutputTo(newAccountCapturer(accountNumT::set)),
                yahcliAccounts("create", "-S", "-d", "hbar", "-a", "1")
                        .exposingOutputTo(newAccountCapturer(accountNumS::set)),

                // save public key vales of R and T accounts
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliKey("print-public", "-p", accountKeyPath(String.valueOf(accountNumR.get())))
                                .exposingOutputTo(publicKeyCapturer(publicKeyR::set)),
                        yahcliKey("print-public", "-p", accountKeyPath(String.valueOf(accountNumT.get())))
                                .exposingOutputTo(publicKeyCapturer(publicKeyT::set)))),
                // schedule account S key update
                doingContextual(spec -> {
                    // create new key file and create the schedule
                    newKeyFilePath.set(combinePublicKeys(publicKeyR.get(), publicKeyT.get()));
                    allRunFor(
                            spec,
                            yahcliAccounts(
                                            "update",
                                            "--pathKeys",
                                            newKeyFilePath.get(),
                                            "--targetAccount",
                                            String.valueOf(accountNumS.get()))
                                    .schedule()
                                    .exposingOutputTo(scheduleIdCapturer(scheduleId::set)));
                }),

                // Sign the schedule with all keys
                doingContextual(spec -> allRunFor(
                        spec,
                        // sign with account R key
                        yahcliScheduleSign("sign", "--scheduleId", scheduleId.get())
                                .payingWith(String.valueOf(accountNumR.get())),
                        // sign with account T key
                        yahcliScheduleSign("sign", "--scheduleId", scheduleId.get())
                                .payingWith(String.valueOf(accountNumT.get())),
                        // sign with account S
                        yahcliScheduleSign("sign", "--scheduleId", scheduleId.get())
                                .payingWith(String.valueOf(accountNumS.get())))),
                // Query all account keys
                doingContextual(spec -> allRunFor(
                        spec,
                        getAccountInfo(String.valueOf(accountNumR.get())).exposingKeyTo(keyR::set),
                        getAccountInfo(String.valueOf(accountNumT.get())).exposingKeyTo(keyT::set),
                        getAccountInfo(String.valueOf(accountNumS.get())).exposingKeyTo(actualKeyS::set))),
                // Validate account S new key
                doingContextual(spec -> {
                    // compose expected key form R and T account keys
                    final var expectedKey = Key.newBuilder()
                            .setKeyList(KeyList.newBuilder()
                                    .addKeys(keyR.get())
                                    .addKeys(keyT.get())
                                    .build())
                            .build();
                    // validate actual key
                    assertEquals(actualKeyS.get(), expectedKey, "Wrong key!");
                }));
    }

    // Helpers
    private String combinePublicKeys(String... publicKeys) {
        final var path = keyFilePath("combinedPublicKey.txt");
        try {
            Files.createFile(Path.of(path));
            final var keys = Arrays.stream(publicKeys).toList();
            StringBuilder content = new StringBuilder();
            keys.forEach(key -> content.append(key).append("\n"));
            Files.writeString(Path.of(path), content.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    private String accountKeyPath(String accountNum) {
        return keyFilePath(String.format("account%s.pem", accountNum));
    }

    private String keyFilePath(String fileName) {
        return Path.of(DEFAULT_WORKING_DIR.get(), TEST_NETWORK, "keys", fileName)
                .toAbsolutePath()
                .toString();
    }
}
