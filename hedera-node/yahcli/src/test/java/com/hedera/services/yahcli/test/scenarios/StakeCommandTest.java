// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newStakedAccountCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newStakedNodeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliAccounts;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class StakeCommandTest {

    @HapiTest
    final Stream<DynamicTest> stakeCommandsWorks() {
        final var nodeId = 0L;
        final var accountId = new AtomicLong();
        return hapiTest(
                cryptoCreate("newAccount").exposingCreatedIdTo(id -> accountId.set(id.getAccountNum())),
                yahcliAccounts("stake", "-n", String.valueOf(nodeId))
                        .exposingOutputTo(newStakedNodeCapturer(nodeStakedTo ->
                                assertEquals(nodeId, nodeStakedTo, "should be staked to node %d".formatted(nodeId)))),
                sourcingContextual(spec -> yahcliAccounts("stake", "-a", String.valueOf(accountId))
                        .exposingOutputTo(newStakedAccountCapturer(accountStakedTo -> assertEquals(
                                accountId.get(),
                                accountStakedTo,
                                "should be staked to account %d".formatted(accountId.get()))))));
    }
}
