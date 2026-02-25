// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongUnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * A helper suite that,
 *
 * <ol>
 *   <li>Starts staking on the target network; and,
 *   <li>Creates some accounts (all with default payer key) that stake to either a node or a
 *       previously-created account, and have some chance of declining rewards; and,
 *   <li>Exports a CSV with information on these accounts.
 * </ol>
 *
 * <p>The CSV has columns
 *
 * <pre>
 *      {@literal stakerNum,balance,stakedToMe,totalStake,stakedToNode,declinedRewards}
 * </pre>
 */
public class StartStaking extends HapiSuite {
    private static final Logger log = LogManager.getLogger(StartStaking.class);

    // Change desired network
    private static final String TARGET_NODES = "<node0-ip>:0.0.3";
    private static final String PAYER_PEM_LOC = "<path-to-PEM>";
    private static final String PAYER_PEM_PASSPHRASE = "<PEM passphrase>";
    private static final int NUM_TARGET_NODES = 7;

    private static final SplittableRandom RANDOM = new SplittableRandom(1234567);

    private static final long CANONICAL_REWARD_RATE = 273972602739726L;
    private static final long REWARD_START_BALANCE = 251 * ONE_MILLION_HBARS;
    private static final long HIGH_VALUE_STAKER_BALANCE = 1234 * ONE_MILLION_HBARS;
    private static final int NUM_ACCOUNTS_TO_STAKE = 1000;
    private static final int BURST_SIZE = 100;
    private static final String STAKER_NAME = "staker";
    private static final double STAKE_TO_PROBABILITY = 0.20;
    private static final double DECLINE_REWARD_PROBABILITY = 0.10;
    private static final long[] BALANCE_CHOICES_HBARS = {100, 1100, 10_200, 100_300, 1_000_400};
    private static final String CSV_LOC = "stakers.csv";

    record StakerMeta(long balance, long nodeId, long stakeToNum, boolean declineReward) {
        private static final long NONE = -1L;

        public boolean isStakingToAccount() {
            return stakeToNum != NONE;
        }

        public static StakerMeta newToAccount(final long balance, final long stakeToNum) {
            return new StakerMeta(balance, NONE, stakeToNum, false);
        }

        public static StakerMeta newToNode(final long balance, final long nodeId) {
            return new StakerMeta(balance, nodeId, NONE, false);
        }

        public static StakerMeta newToNodeDeclining(final long balance, final long nodeId) {
            return new StakerMeta(balance, nodeId, NONE, true);
        }
    }

    private static Queue<Long> stakerNums = new ConcurrentLinkedQueue<>();
    private static Map<Long, StakerMeta> stakers = new ConcurrentHashMap<>();

    private static long chooseRandomExistingStaker() {
        final var n = stakerNums.size();
        final var choice = RANDOM.nextInt(n);
        Iterator<Long> iter = stakerNums.iterator();
        long chosen = -1;
        for (int i = 0; i <= choice; i++) {
            chosen = iter.next();
        }
        return chosen;
    }

    private static long chooseRandomBalance() {
        final var choice = RANDOM.nextInt(BALANCE_CHOICES_HBARS.length);
        return BALANCE_CHOICES_HBARS[choice];
    }

    public static void main(String... args) {
        new StartStaking().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public boolean canRunConcurrent() {
        return false;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(startStakingAndExportCreatedStakers());
    }

    final Stream<DynamicTest> startStakingAndExportCreatedStakers() {
        final var baseStakerName = "baseStaker";
        return customHapiSpec("StartStakingAndExportCreatedStakers")
                .withProperties(Map.of(
                        "nodes", TARGET_NODES,
                        "default.payer.pemKeyLoc", PAYER_PEM_LOC,
                        "default.payer.pemKeyPassphrase", PAYER_PEM_PASSPHRASE))
                .given(
                        overriding("staking.perHbarRewardRate", "" + CANONICAL_REWARD_RATE),
                        cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, STAKING_REWARD, REWARD_START_BALANCE)))
                .when(
                        inParallel(IntStream.range(0, NUM_TARGET_NODES)
                                .mapToObj(i -> cryptoCreate(baseStakerName + i)
                                        .stakedNodeId(i)
                                        .balance(HIGH_VALUE_STAKER_BALANCE)
                                        .key(DEFAULT_PAYER)
                                        .exposingCreatedIdTo(id -> trackStaker(
                                                id.getAccountNum(),
                                                StakerMeta.newToNode(HIGH_VALUE_STAKER_BALANCE, i))))
                                .toArray(HapiSpecOperation[]::new)),
                        blockingOrder(IntStream.range(0, NUM_ACCOUNTS_TO_STAKE / BURST_SIZE)
                                .mapToObj(j -> inParallel(IntStream.range(0, BURST_SIZE)
                                        .mapToObj(i -> sourcing(this::nextCreation))
                                        .toArray(HapiSpecOperation[]::new)))
                                .toArray(HapiSpecOperation[]::new)))
                .then(withOpContext((spec, opLog) -> {
                    final Map<Long, Long> stakedToMe = new HashMap<>();
                    stakers.forEach((num, meta) -> {
                        if (meta.isStakingToAccount()) {
                            stakedToMe.merge(meta.stakeToNum, meta.balance, Long::sum);
                        }
                    });
                    final LongUnaryOperator effStakeToMeFn = num -> {
                        final var meta = stakers.get(num);
                        return meta.isStakingToAccount() ? 0L : stakedToMe.getOrDefault(num, 0L);
                    };
                    try (final var writer = Files.newBufferedWriter(Paths.get(CSV_LOC))) {
                        writer.write("stakerNum,balance,stakedToMe,totalStake,stakedToNode,declinedRewards\n");
                        stakers.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(entry -> {
                                    final var num = entry.getKey();
                                    final var meta = entry.getValue();
                                    final var stakedToThisNum = effStakeToMeFn.applyAsLong(num);
                                    final var totalStake = meta.balance + stakedToThisNum;
                                    final var stakeToNode = !meta.isStakingToAccount();
                                    final var line = new StringBuilder()
                                            .append(num)
                                            .append(",")
                                            .append(meta.balance)
                                            .append(",")
                                            .append(stakedToThisNum)
                                            .append(",")
                                            .append(totalStake)
                                            .append(",")
                                            .append(stakeToNode)
                                            .append(",")
                                            .append(stakeToNode && meta.declineReward)
                                            .append("\n");
                                    try {
                                        writer.write(line.toString());
                                    } catch (final IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                });
                    }
                }));
    }

    private static void trackStaker(final long num, final StakerMeta meta) {
        stakers.put(num, meta);
        stakerNums.add(num);
    }

    private HapiSpecOperation nextCreation() {
        final var toAccount = RANDOM.nextDouble() < STAKE_TO_PROBABILITY;
        final var balance = chooseRandomBalance();
        return toAccount ? nextToAccount(balance) : nextToNode(balance);
    }

    private HapiSpecOperation nextToAccount(final long balance) {
        final var to = chooseRandomExistingStaker();
        log.info("Choosing to stake to num {}", to);
        return cryptoCreate(STAKER_NAME + RANDOM.nextLong(Long.MAX_VALUE))
                .stakedAccountId(String.valueOf(to))
                .balance(balance)
                .key(DEFAULT_PAYER)
                .exposingCreatedIdTo(id -> trackStaker(id.getAccountNum(), StakerMeta.newToAccount(balance, to)));
    }

    private HapiSpecOperation nextToNode(final long balance) {
        final var node = RANDOM.nextInt(NUM_TARGET_NODES);
        final var decline = RANDOM.nextDouble() < DECLINE_REWARD_PROBABILITY;
        return cryptoCreate(STAKER_NAME + RANDOM.nextLong(Long.MAX_VALUE))
                .stakedNodeId(node)
                .balance(balance)
                .key(DEFAULT_PAYER)
                .declinedReward(decline)
                .exposingCreatedIdTo(id -> trackStaker(
                        id.getAccountNum(),
                        decline ? StakerMeta.newToNodeDeclining(balance, node) : StakerMeta.newToNode(balance, node)));
    }
}
