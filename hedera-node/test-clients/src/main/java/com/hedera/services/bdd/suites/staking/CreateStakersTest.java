// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.operations.transactions.TouchBalancesOperation.touchBalanceOf;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ensureStakingActivated;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static java.util.stream.Collectors.toSet;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@Order(Integer.MIN_VALUE)
public class CreateStakersTest {
    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    @HapiTest
    final Stream<DynamicTest> createStakers() {
        return hapiTest(
                ensureStakingActivated(),
                touchBalanceOf(NODE0_STAKER, NODE1_STAKER, NODE2_STAKER),
                // Give all stakers the 0.0.2 key to easily transfer their balances later
                inParallel(Stream.of(NODE0_STAKER, NODE1_STAKER, NODE2_STAKER)
                        .map(staker -> cryptoUpdate(staker.name()).key(GENESIS))
                        .toArray(SpecOperation[]::new)),
                doingContextual(spec -> HapiSpec.setStakerIds(Stream.of(NODE0_STAKER, NODE1_STAKER, NODE2_STAKER)
                        .map(staker -> spec.registry().getAccountID(staker.name()))
                        .collect(toSet()))));
    }
}
