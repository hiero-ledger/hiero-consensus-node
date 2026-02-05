// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_COLLECTOR;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;

import com.hedera.node.app.hapi.utils.fee.FeeObject;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.AccountInfoAsserts;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
public class TargetNetworkPrep {

    @LeakyHapiTest(
            overrides = {"nodes.feeCollectionAccountEnabled", "nodes.preserveMinNodeRewardBalance"},
            requirement = {SYSTEM_ACCOUNT_BALANCES})
    final Stream<DynamicTest> ensureSystemStateAsExpectedWithSystemDefaultFiles() {
        final var emptyKey =
                Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();
        final var snapshot800 = "800startBalance";
        final var snapshot801 = "801startBalance";
        final var snapshot802 = "802startBalance";
        final var civilian = "civilian";
        final AtomicReference<FeeObject> feeObs = new AtomicReference<>();
        return hapiTest(
                overridingTwo(
                        "nodes.feeCollectionAccountEnabled", "false", "nodes.preserveMinNodeRewardBalance", "false"),
                cryptoCreate(civilian),
                balanceSnapshot(snapshot802, FEE_COLLECTOR),
                balanceSnapshot(snapshot800, STAKING_REWARD),
                cryptoTransfer(tinyBarsFromTo(civilian, STAKING_REWARD, ONE_HBAR))
                        .payingWith(civilian)
                        .signedBy(civilian)
                        .exposingFeesTo(feeObs)
                        .logged(),
                sourcing(() -> getAccountBalance(STAKING_REWARD).hasTinyBars(changeFromSnapshot(snapshot800, (long)
                        (ONE_HBAR + ((feeObs.get().networkFee() + feeObs.get().serviceFee()) * 0.1))))),
                balanceSnapshot(snapshot801, NODE_REWARD),
                cryptoTransfer(tinyBarsFromTo(civilian, NODE_REWARD, ONE_HBAR))
                        .payingWith(civilian)
                        .signedBy(civilian)
                        .logged(),
                sourcing(() -> getAccountBalance(NODE_REWARD).hasTinyBars(changeFromSnapshot(snapshot801, (long)
                        (ONE_HBAR + ((feeObs.get().networkFee() + feeObs.get().serviceFee()) * 0.1))))),
                getAccountDetails(STAKING_REWARD)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .key(emptyKey)
                                .memo("")
                                .noAlias()
                                .noAllowances()),
                getAccountDetails(NODE_REWARD)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .key(emptyKey)
                                .memo("")
                                .noAlias()
                                .noAllowances()),
                withOpContext((spec, opLog) -> {
                    final var genesisInfo = getAccountInfo("2");
                    allRunFor(spec, genesisInfo);
                    final var key = genesisInfo
                            .getResponse()
                            .getCryptoGetInfo()
                            .getAccountInfo()
                            .getKey();
                    final var cloneConfirmations = inParallel(IntStream.rangeClosed(200, 750)
                            .filter(i -> i < 350 || i >= 400)
                            .mapToObj(i -> getAccountInfo(String.valueOf(i))
                                    .noLogging()
                                    .payingWith(GENESIS)
                                    .has(AccountInfoAsserts.accountWith().key(key)))
                            .toArray(HapiSpecOperation[]::new));
                    allRunFor(spec, cloneConfirmations);
                }),
                sourcing(() -> getAccountBalance(FEE_COLLECTOR).hasTinyBars(changeFromSnapshot(snapshot802, 0L))),
                getAccountDetails(FEE_COLLECTOR)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .key(emptyKey)
                                .memo("")
                                .noAlias()
                                .noAllowances()));
    }

    @LeakyHapiTest(
            overrides = {"nodes.feeCollectionAccountEnabled", "nodes.preserveMinNodeRewardBalance"},
            requirement = {SYSTEM_ACCOUNT_BALANCES})
    final Stream<DynamicTest> ensureSystemStateAsExpectedWithFeeCollector() {
        final var emptyKey =
                Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();
        final var snapshot802 = "802startBalance";
        final var civilian = "civilian";
        final AtomicReference<FeeObject> feeObs = new AtomicReference<>();
        return hapiTest(
                overridingTwo(
                        "nodes.feeCollectionAccountEnabled", "true", "nodes.preserveMinNodeRewardBalance", "false"),
                cryptoCreate(civilian),
                balanceSnapshot(snapshot802, FEE_COLLECTOR),
                cryptoTransfer(tinyBarsFromTo(civilian, STAKING_REWARD, ONE_HBAR))
                        .payingWith(civilian)
                        .signedBy(civilian)
                        .exposingFeesTo(feeObs)
                        .via("stakingRewardTransfer")
                        .logged(),
                getTxnRecord("stakingRewardTransfer").logged().hasHbarAmount(STAKING_REWARD, ONE_HBAR),
                cryptoTransfer(tinyBarsFromTo(civilian, NODE_REWARD, ONE_HBAR))
                        .payingWith(civilian)
                        .signedBy(civilian)
                        .logged()
                        .via("nodeRewardTransfer"),
                getTxnRecord("nodeRewardTransfer").logged().hasHbarAmount(NODE_REWARD, ONE_HBAR),
                getAccountDetails(STAKING_REWARD)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .key(emptyKey)
                                .memo("")
                                .noAlias()
                                .noAllowances()),
                getAccountDetails(NODE_REWARD)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .key(emptyKey)
                                .memo("")
                                .noAlias()
                                .noAllowances()),
                withOpContext((spec, opLog) -> {
                    final var genesisInfo = getAccountInfo("2");
                    allRunFor(spec, genesisInfo);
                    final var key = genesisInfo
                            .getResponse()
                            .getCryptoGetInfo()
                            .getAccountInfo()
                            .getKey();
                    final var cloneConfirmations = inParallel(IntStream.rangeClosed(200, 750)
                            .filter(i -> i < 350 || i >= 400)
                            .mapToObj(i -> getAccountInfo(String.valueOf(i))
                                    .noLogging()
                                    .payingWith(GENESIS)
                                    .has(AccountInfoAsserts.accountWith().key(key)))
                            .toArray(HapiSpecOperation[]::new));
                    allRunFor(spec, cloneConfirmations);
                }),
                sourcing(() -> getAccountBalance(FEE_COLLECTOR)
                        .hasTinyBars(changeFromSnapshot(
                                snapshot802, (long) 2 * feeObs.get().totalFee()))),
                getAccountDetails(FEE_COLLECTOR)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .key(emptyKey)
                                .memo("")
                                .noAlias()
                                .noAllowances()));
    }
}
