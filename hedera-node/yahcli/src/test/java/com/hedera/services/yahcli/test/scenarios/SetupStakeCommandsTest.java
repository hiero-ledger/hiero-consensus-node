// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.scenarios;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newSetupStakeCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliSetupStaking;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
public class SetupStakeCommandsTest {
    @HapiTest
    final Stream<DynamicTest> setupStakeTest() {
        final var newRewardRate = new AtomicLong();
        final var newPerNodeRate = new AtomicLong();
        final var newAccountBalance = new AtomicLong();
        return hapiTest(
                yahcliSetupStaking("-p", "1kh", "-r", "2h", "-b", "3mh")
                        .exposingOutputTo(
                                newSetupStakeCapturer(newRewardRate::set, newPerNodeRate::set, newAccountBalance::set)),
                withOpContext((spec, opLog) -> {
                    assertEquals(200_000_000L, newRewardRate.get(), "Expected reward rate to be 2 Hbars");
                    assertEquals(100_000_000_000L, newPerNodeRate.get(), "Expected per-node stake to be 1 K Hbars");
                    assertEquals(
                            300_000_000_000_000L,
                            newAccountBalance.get(),
                            "Expected staking account balance to be 3 M Hbars");
                })
                // TODO add state verification as well
                );
    }
}
