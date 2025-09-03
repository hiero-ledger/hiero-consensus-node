// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asScheduleString;
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
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliAccounts;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliScheduleSign;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class ScheduleCommandsTest {

    @HapiTest
    final Stream<DynamicTest> readmeScheduleCreateExample() {
        final var newAccountNum = new AtomicLong();
        final var newScheduleNum = new AtomicLong();
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
                                .exposingRegistry(registry -> {
                                    var num = registry.getScheduleId("original").getScheduleNum();
                                    newScheduleNum.set(num);
                                }))),
                doingContextual(spec -> allRunFor(
                        spec,
                        getScheduleInfo(
                                asScheduleString(spec.scheduleIdFactory().apply(newScheduleNum.get()))))));
    }

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
        final var scheduleNum = new AtomicLong();
        // account S new key
        final var actualKeyS = new AtomicReference<Key>();

        return hapiTest(
                // create 3 accounts
                yahcliAccounts("create", "-S").exposingOutputTo(newAccountCapturer(accountNumR::set)),
                yahcliAccounts("create", "-S").exposingOutputTo(newAccountCapturer(accountNumT::set)),
                yahcliAccounts("create", "-S").exposingOutputTo(newAccountCapturer(accountNumS::set)),
                // save public key vales of R and T accounts
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliKey("print-public", "-p", accountKeyPath(String.valueOf(accountNumR.get())))
                                .exposingOutputTo(publicKeyCapturer(publicKeyR::set)),
                        yahcliKey("print-public", "-p", accountKeyPath(String.valueOf(accountNumT.get())))
                                .exposingOutputTo(publicKeyCapturer(publicKeyT::set)))),

                // create new key file
                doingContextual(spec -> {
                    newKeyFilePath.set(newKeyFilePath("newAccountSKey.txt"));
                    try {
                        Files.createFile(Path.of(newKeyFilePath.get()));
                        Files.writeString(Path.of(newKeyFilePath.get()), publicKeyR.get() + "\n" + publicKeyT.get());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    allRunFor(
                            spec,
                            yahcliAccounts(
                                            "update",
                                            "--pathKeys",
                                            newKeyFilePath.get(),
                                            "--targetAccount",
                                            String.valueOf(accountNumS.get()))
                                    .schedule()
                                    .exposingRegistry(registry -> {
                                        var num =
                                                registry.getScheduleId("update").getScheduleNum();
                                        scheduleNum.set(num);
                                    }));
                }),

                // Sign the schedule with all keys
                doingContextual(spec -> allRunFor(
                        spec,
                        // sign with account R key
                        yahcliScheduleSign(
                                "sign",
                                "--scheduleId",
                                String.valueOf(scheduleNum.get()),
                                "-k",
                                accountKeyPath(String.valueOf(accountNumR.get()))),
                        // sign with account T key
                        yahcliScheduleSign(
                                "sign",
                                "--scheduleId",
                                String.valueOf(scheduleNum.get()),
                                "-k",
                                accountKeyPath(String.valueOf(accountNumT.get()))),
                        // sign with account S
                        yahcliScheduleSign(
                                "sign",
                                "--scheduleId",
                                String.valueOf(scheduleNum.get()),
                                "-k",
                                accountKeyPath(String.valueOf(accountNumS.get()))))),

                doingContextual(spec -> allRunFor(
                        spec,
                        getAccountInfo(String.valueOf(accountNumR.get())).exposingKeyTo(keyR::set),
                        getAccountInfo(String.valueOf(accountNumT.get())).exposingKeyTo(keyT::set),
                        getAccountInfo(String.valueOf(accountNumS.get())).exposingKeyTo(actualKeyS::set),
                        // print account info S
                        yahcliAccounts("info", String.valueOf(accountNumS.get())))),

                doingContextual(spec -> {
                    // compose expected key form R and T account keys
                    final var expectedKey = Key.newBuilder().setKeyList(KeyList.newBuilder().addKeys(keyR.get()).addKeys(keyT.get()).build()).build();
                    // validate actual key
                    assertEquals(actualKeyS.get(), expectedKey, "Wrong key!");
                })
        );
    }

    // Helpers
    private String accountKeyPath(String accountNum) {
        return newKeyFilePath(String.format("account%s.pem", accountNum));
    }

    private String newKeyFilePath(String fileName) {
        return Path.of(DEFAULT_WORKING_DIR.get(), TEST_NETWORK, String.format("keys/%s", fileName))
                .toAbsolutePath()
                .toString();
    }
}
