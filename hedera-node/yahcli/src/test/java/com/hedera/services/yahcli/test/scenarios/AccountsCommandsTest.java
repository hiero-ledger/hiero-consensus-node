// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.YahcliVerbs.*;
import static com.hedera.services.yahcli.test.YahcliVerbs.newAccountCapturer;
import static com.hedera.services.yahcli.test.YahcliVerbs.newBalanceCapturer;
import static com.hedera.services.yahcli.test.YahcliVerbs.yahcliAccounts;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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
    final Stream<DynamicTest> readmeAccountsBalanceExample() {
        final var civKey1 = "civKey1";
        final var civilian1 = "civilian1";
        final var civKey2 = "civKey2";
        final var civilian2 = "civilian1";
        final AtomicReference<AccountID> civ1Id = new AtomicReference<>();
        final AtomicReference<AccountID> civ2Id = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(civKey1)
                        .shape(SigControl.ED25519_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(civKey1 + ".pem"), "keypass"),
                cryptoCreate(civilian1)
                        .key(civKey1)
                        .balance(10 * ONE_HBAR)
                        .memo("Never gonna")
                        .exposingCreatedIdTo(civ1Id::set),
                newKeyNamed(civKey2)
                        .shape(SigControl.SECP256K1_ON)
                        .exportingTo(() -> asYcDefaultNetworkKey(civKey2 + ".pem"), "keypass"),
                cryptoCreate(civilian2)
                        .key(civKey2)
                        .balance(20 * ONE_HBAR)
                        .memo("give you up")
                        .exposingCreatedIdTo(civ2Id::set),
                doingContextual(spec -> {
                    final var civ1AcctNum = civ1Id.get().getAccountNum();
                    final var civ2AcctNum = civ2Id.get().getAccountNum();
                    allRunFor(
                            spec,
                            yahcliAccounts("balance", String.valueOf(civ1AcctNum), String.valueOf(civ2AcctNum))
                                    .exposingOutputTo(newBalanceCapturer((actual) -> {
                                        assertEquals(10 * ONE_HBAR, actual.get(civ1AcctNum));
                                        assertEquals(20 * ONE_HBAR, actual.get(civ2AcctNum));
                                    })));
                }));
    }
}
