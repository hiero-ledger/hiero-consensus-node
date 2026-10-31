// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hip1522;

import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.junit.TestTags.SERIAL;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThree;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Pins the agreement HIP-1522 requires between the EVM and HAPI views of an account's staking state, for an
 * account that is actually accruing rewards.
 *
 * <p>{@link AccountStakingConfigurationTest} can only ever observe a {@code pendingReward} of zero, because
 * rewards are paid out of 0.0.800 only once staking rewards are activated, and accrue only across completed
 * staking periods. This suite activates rewards and waits out two periods, so like {@code StakingSuite} it
 * runs serially and in isolation: activation is permanent network state.
 */
@Tag(SERIAL)
@Tag(LONG_RUNNING)
@HapiTestLifecycle
@OrderedInIsolation
public class AccountStakingRewardsTest {
    private static final String STAKING_FLAG = "contracts.systemContract.accountService.stakingEnabled";
    private static final String HRC1522_CONTRACT = "HRC1522Contract";
    private static final String PAYER = "payer";
    private static final String STAKER = "staker";
    private static final String STAKES_TO_STAKER = "stakesToStaker";
    private static final long STAKING_PERIOD_MINS = 1L;
    private static final long PER_HBAR_REWARD_RATE = 3_333_333L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                overriding(STAKING_FLAG, "true"),
                overridingThree(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "" + PER_HBAR_REWARD_RATE,
                        "staking.rewardBalanceThreshold", "0"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)));
    }

    @HapiTest
    @DisplayName("getStakingInfo agrees with CryptoGetInfo for an account accruing rewards")
    final Stream<DynamicTest> getStakingInfoAgreesWithCryptoGetInfo() {
        final AtomicReference<AccountID> stakerId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(STAKER).stakedNodeId(0).balance(ONE_MILLION_HBARS).exposingCreatedIdTo(stakerId::set),
                // So stakedToMe is non-zero too, not just pendingReward
                cryptoCreate(STAKES_TO_STAKER).stakedAccountId(STAKER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT),
                // A reward accrues only across a completed period, and the period in which the account
                // started staking does not count, so two boundaries must pass
                waitUntilStartOfNextStakingPeriod(STAKING_PERIOD_MINS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(STAKING_PERIOD_MINS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                // Read both views right after a boundary, so they share a staking period: the contract call
                // estimates against its consensus time and the query against wall clock, and the estimate is
                // constant within a period
                withOpContext((spec, opLog) -> {
                    final var read = contractCall(
                                    HRC1522_CONTRACT, "getStakingInfoCall", idAsHeadlongAddress(stakerId.get()))
                            .payingWith(PAYER)
                            .gas(1_000_000)
                            .via("evmRead");
                    final var record = getTxnRecord("evmRead");
                    final var hapiRead = getAccountInfo(STAKER);
                    allRunFor(spec, read, record, hapiRead);

                    final var outputs = Function.fromJson(getABIFor(FUNCTION, "getStakingInfoCall", HRC1522_CONTRACT))
                            .decodeReturn(record.getResponseRecord()
                                    .getContractCallResult()
                                    .getContractCallResult()
                                    .toByteArray());
                    assertEquals(SUCCESS.getNumber(), (long) outputs.get(0));
                    final Tuple evm = outputs.get(1);
                    final var hapi = hapiRead.getResponse()
                            .getCryptoGetInfo()
                            .getAccountInfo()
                            .getStakingInfo();

                    // The accessor is only meaningful if the state under comparison is
                    assertTrue(hapi.getPendingReward() > 0, "the staker should have accrued a reward");
                    assertTrue(hapi.getStakedToMe() > 0, "another account stakes to the staker");

                    assertEquals(hapi.getDeclineReward(), (boolean) evm.get(0), "declineReward");
                    assertEquals(hapi.getStakePeriodStart().getSeconds(), (long) evm.get(1), "stakePeriodStart");
                    assertEquals(hapi.getPendingReward(), (long) evm.get(2), "pendingReward");
                    assertEquals(hapi.getStakedToMe(), (long) evm.get(3), "stakedToMe");
                    assertEquals(hapi.getStakedNodeId(), (long) evm.get(4), "stakedNodeId");
                    assertEquals(asHeadlongAddress(new byte[20]), evm.get(5), "stakedAccountId");
                }));
    }
}
