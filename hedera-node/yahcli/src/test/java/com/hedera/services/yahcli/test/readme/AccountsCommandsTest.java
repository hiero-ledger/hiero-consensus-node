// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.readme;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newAccountCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newBalanceCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newCurrencyTransferCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newTokenTransferCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliAccounts;
import static com.hedera.services.yahcli.test.profile.Civilian.CIVILIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.yahcli.test.profile.Civilian;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class AccountsCommandsTest {
    @HapiTest
    final Stream<DynamicTest> readmeAccountsCreateExample() {
        final var newAccountNum = new AtomicLong();
        return hapiTest(
                // Create an account with yahcli (fails if yahcli exits with a non-zero return code)
                yahcliAccounts("create", "-d", "hbar", "-a", "1", "--memo", "Who danced between")
                        // Capture the new account number from the yahcli output
                        .exposingOutputTo(newAccountCapturer(newAccountNum::set)),
                // Query the new account by number and assert it has the expected memo and balance
                sourcingContextual(spec -> getAccountInfo(
                                asAccountString(spec.accountIdFactory().apply(newAccountNum.get())))
                        .has(accountWith().balance(ONE_HBAR).memo("Who danced between"))));
    }

    @HapiTest
    final Stream<DynamicTest> reKeyWithNewlyGeneratedKey() {
        final var testAccountId = new AtomicReference<Long>();
        return hapiTest(
                // Create account and attach an ED25519 key
                cryptoCreate("testAccount")
                        .exposingCreatedIdTo(accountID -> testAccountId.set(accountID.getAccountNum())),
                sourcing(() -> newKeyNamed("testKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey("account" + testAccountId + ".pem"), "keypass")),
                cryptoUpdate("testAccount").key("testKey"),
                // re-key using newly generated key
                sourcingContextual(spec -> yahcliAccounts("rekey", "-g", String.valueOf(testAccountId))
                        .exposingOutputTo(
                                output -> assertTrue(output.contains(testAccountId + " has been re-keyed")))));
    }

    @HapiTest
    final Stream<DynamicTest> rekeyWithExplicitED25519Key() {
        final var testAccountId = new AtomicReference<Long>();
        return hapiTest(
                // Create account and attach an ED25519 key
                cryptoCreate("testAccount")
                        .exposingCreatedIdTo(accountID -> testAccountId.set(accountID.getAccountNum())),
                sourcing(() -> newKeyNamed("testKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey("account" + testAccountId + ".pem"), "keypass")),
                cryptoUpdate("testAccount").key("testKey"),
                // Create new ED25519 key
                sourcing(() -> newKeyNamed("newKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey("newKey.pem"), "keypass")),
                // rekey using yahcli with the newly created ED25519 key file
                sourcingContextual(spec -> yahcliAccounts(
                                "rekey", "-k", asYcDefaultNetworkKey("newKey.pem"), String.valueOf(testAccountId))
                        .exposingOutputTo(
                                output -> assertTrue(output.contains(testAccountId + " has been re-keyed")))));
    }

    @HapiTest
    final Stream<DynamicTest> rekeyWithExplicitSECPKey() {
        final var testAccountId = new AtomicReference<Long>();
        return hapiTest(
                // Create account and attach an ED25519 key
                cryptoCreate("testAccount")
                        .exposingCreatedIdTo(accountID -> testAccountId.set(accountID.getAccountNum())),
                sourcing(() -> newKeyNamed("testKey")
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey("account" + testAccountId + ".pem"), "keypass")),
                cryptoUpdate("testAccount").key("testKey"),
                // Create new SECP256K1 key
                sourcing(() -> newKeyNamed("newSecpKey")
                        .shape(SigControl.SECP256K1_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey("newSecpKey.pem"), "keypass")),
                // rekey using yahcli with the newly created SECP256K1 key file
                sourcingContextual(spec -> yahcliAccounts(
                                "rekey", "-k", asYcDefaultNetworkKey("newSecpKey.pem"), String.valueOf(testAccountId))
                        .exposingOutputTo(
                                output -> assertTrue(output.contains(testAccountId + " has been re-keyed")))));
    }

    @HapiTest
    final Stream<DynamicTest> readmeAccountsBalanceExample() {
        final AtomicReference<Long> civId = new AtomicReference<>();
        final var civ2 = new Civilian("2", 20 * ONE_HBAR);
        final AtomicReference<Long> civ2Id = new AtomicReference<>();
        return hapiTest(
                CIVILIAN.newKey(),
                CIVILIAN.newAccount(civId),
                civ2.newKey(),
                civ2.newAccount(civ2Id),
                doingContextual(spec -> {
                    final var civ1AcctNum = civId.get();
                    final var civ2AcctNum = civ2Id.get();
                    allRunFor(
                            spec,
                            yahcliAccounts("balance", String.valueOf(civ1AcctNum), String.valueOf(civ2AcctNum))
                                    .exposingOutputTo(newBalanceCapturer((actual) -> {
                                        assertEquals(CIVILIAN.initialBalance(), actual.get(civ1AcctNum));
                                        assertEquals(civ2.initialBalance(), actual.get(civ2AcctNum));
                                    })));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> readmeAccountsSendCurrencyExample() {
        final AtomicReference<Long> civAcctNum = new AtomicReference<>();
        final var transferAmtHbar = 1_000L;
        final var transferAmtTinybars = transferAmtHbar * ONE_HBAR;
        final var transferAmtKilobars = transferAmtHbar / 1_000L;
        return hapiTest(
                CIVILIAN.newKey(),
                CIVILIAN.newAccount(civAcctNum),
                // First send hbar
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliAccounts(
                                        "send",
                                        "--to",
                                        String.valueOf(civAcctNum.get()),
                                        "--memo",
                                        "\"Never gonna give you up\"",
                                        String.valueOf(transferAmtHbar))
                                .exposingOutputTo(newCurrencyTransferCapturer(actual -> {
                                    Assertions.assertEquals(transferAmtHbar, actual.amount());
                                    Assertions.assertEquals("hbar", actual.denom());
                                    Assertions.assertEquals(civAcctNum.get(), actual.toAcctNum());
                                })))),
                // Verify with a direct query
                getAccountBalance(CIVILIAN.name()).hasTinyBars(CIVILIAN.initialBalance() + transferAmtTinybars),
                // Then send tinybars
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliAccounts(
                                        "send",
                                        "--to",
                                        String.valueOf(civAcctNum.get()),
                                        "-d",
                                        "tinybar",
                                        "--memo",
                                        "\"Never gonna let you down\"",
                                        String.valueOf(transferAmtTinybars))
                                .exposingOutputTo(newCurrencyTransferCapturer(actual -> {
                                    Assertions.assertEquals(transferAmtTinybars, actual.amount());
                                    Assertions.assertEquals("tinybar", actual.denom());
                                    Assertions.assertEquals(civAcctNum.get(), actual.toAcctNum());
                                })))),
                // Verify with query
                getAccountBalance(CIVILIAN.name()).hasTinyBars(CIVILIAN.initialBalance() + (2 * transferAmtTinybars)),
                // Finally, send kilobar (1 kilobar = 1_000 hbar)
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliAccounts(
                                        "send",
                                        "--to",
                                        String.valueOf(civAcctNum.get()),
                                        "-d",
                                        "kilobar",
                                        "--memo",
                                        "\"Never gonna run around and desert you\"",
                                        String.valueOf(transferAmtKilobars))
                                .exposingOutputTo(newCurrencyTransferCapturer(actual -> {
                                    Assertions.assertEquals(transferAmtKilobars, actual.amount());
                                    Assertions.assertEquals("kilobar", actual.denom());
                                    Assertions.assertEquals(civAcctNum.get(), actual.toAcctNum());
                                })))),
                // Verify with query
                getAccountBalance(CIVILIAN.name()).hasTinyBars(CIVILIAN.initialBalance() + (3 * transferAmtTinybars)));
    }

    @HapiTest
    final Stream<DynamicTest> readmeAccountsSendTokenExample() {
        final AtomicReference<Long> civAcctNum = new AtomicReference<>();
        final var tokenName = "aToken";
        final AtomicReference<Long> tokenNum = new AtomicReference<>();
        final var transferAmtFungible = 1_000_000;

        return hapiTest(
                CIVILIAN.newKey(),
                CIVILIAN.newAccount(civAcctNum),
                tokenCreate(tokenName)
                        .adminKey(DEFAULT_PAYER)
                        .initialSupply(2 * transferAmtFungible)
                        .treasury(DEFAULT_PAYER)
                        .exposingCreatedIdTo(token -> tokenNum.set(Long.parseLong(token.split("\\.")[2]))),
                tokenAssociate(CIVILIAN.name(), tokenName),
                doingContextual(spec -> allRunFor(
                        spec,
                        yahcliAccounts(
                                        "send",
                                        "--to",
                                        String.valueOf(civAcctNum.get()),
                                        "-d",
                                        String.valueOf(tokenNum),
                                        "--memo",
                                        "\"Never gonna make you cry\"",
                                        String.valueOf(1))
                                .exposingOutputTo(newTokenTransferCapturer(actual -> {
                                    Assertions.assertEquals(transferAmtFungible / 1_000_000, actual.amount());
                                    Assertions.assertEquals(String.valueOf(tokenNum.get()), actual.denom());
                                    Assertions.assertEquals(civAcctNum.get(), actual.toAcctNum());
                                })))),
                // Verify with a direct query
                getAccountBalance(CIVILIAN.name())
                        .hasTokenBalance(tokenName, transferAmtFungible)
                        // Hbar balance shouldn't change
                        .hasTinyBars(CIVILIAN.initialBalance()),
                getAccountBalance(DEFAULT_PAYER).hasTokenBalance(tokenName, transferAmtFungible));
    }
}
