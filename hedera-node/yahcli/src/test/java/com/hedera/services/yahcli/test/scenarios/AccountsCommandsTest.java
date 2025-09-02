// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newAccountCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliAccounts;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.atomic.AtomicLong;
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
}
