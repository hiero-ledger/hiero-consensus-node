// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.StakeSetupSuite;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "activate-staking",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Activates staking on the target network (requires <SHARD>.<REALM>.2 payer)")
public class SetupStakeCommand implements Callable<Integer> {
    private static final long CANONICAL_REWARD_RATE = 6849L;
    private static final long CANONICAL_800_START_BALANCE = 250 * HapiSuite.ONE_MILLION_HBARS;
    private static final long TOTAL_HBAR_SUPPLY = 50_000_000_000L * 100_000_000L;

    @CommandLine.ParentCommand
    Yahcli yahcli;

    @CommandLine.Option(
            names = {"-p", "--per-node-amount"},
            paramLabel = "amount to stake to each node (use h/kh/mh/bh suffixes to scale)")
    String perNodeAmount;

    @CommandLine.Option(
            names = {"-r", "--staking-reward-rate"},
            paramLabel = "reward rate for staking (use h/kh/mh/bh suffixes to scale)")
    String stakingRewardRate;

    @CommandLine.Option(
            names = {"-b", "--reward-account-balance"},
            paramLabel = "balance of the reward account (use h/kh/mh/bh suffixes to scale)")
    String rewardAccountBalance;

    private static final Map<String, Long> SUFFIX_SCALES = Map.of(
            "h", HapiSuite.ONE_HBAR,
            "kh", 1_000L * HapiSuite.ONE_HBAR,
            "mh", 1_000_000L * HapiSuite.ONE_HBAR,
            "bh", 1_000_000_000L * HapiSuite.ONE_HBAR);

    @Override
    public Integer call() throws Exception {
        final var config = ConfigUtils.configFrom(yahcli);
        try {
            assertValidParams();
        } catch (Exception any) {
            config.output().warn("Please check the parameters and try again (got '" + any.getMessage() + "')");
            return 1;
        }
        long stakePerNode = TOTAL_HBAR_SUPPLY / config.numNodesInTargetNet() / 4;
        if (perNodeAmount != null) {
            stakePerNode = parseScaledAmount(perNodeAmount);
        }
        long rewardRate = CANONICAL_REWARD_RATE;
        if (stakingRewardRate != null) {
            rewardRate = parseScaledAmount(stakingRewardRate);
        }
        long start800Balance = CANONICAL_800_START_BALANCE;
        if (rewardAccountBalance != null) {
            start800Balance = parseScaledAmount(rewardAccountBalance);
        }
        final var delegate = new StakeSetupSuite(stakePerNode, rewardRate, start800Balance, config);
        delegate.runSuiteSync();

        final var firstSpec = delegate.getFinalSpecs().getFirst();
        if (firstSpec.getStatus() == HapiSpec.SpecStatus.PASSED) {
            final var fqStakingAcct = asEntityString(firstSpec.shard(), firstSpec.realm(), 800);
            final var msgSb = new StringBuilder("SUCCESS - staking activated on network '")
                    .append(config.getTargetName())
                    .append("' with,\n.i.   * Reward rate of               ")
                    .append(rewardRate)
                    .append("\n.i.   * " + fqStakingAcct + " balance credit of    ")
                    .append(start800Balance)
                    .append("\n");
            final var perNodeStake = stakePerNode;
            delegate.getAccountsToStakedNodes().forEach((account, nodeId) -> msgSb.append(".i.   * ")
                    .append(account)
                    .append(" staked to node")
                    .append(nodeId)
                    .append(" for ")
                    .append(perNodeStake)
                    .append("\n"));
            config.output().info(msgSb.toString());
        } else {
            config.output().warn("FAILED - staking not initialized for '" + config.getTargetName() + "'");
            return 1;
        }

        return 0;
    }

    @SuppressWarnings({"java:S3776", "java:S1192"})
    private void assertValidParams() {
        if (perNodeAmount != null) {
            parseScaledAmount(perNodeAmount);
        }
        if (stakingRewardRate != null) {
            parseScaledAmount(stakingRewardRate);
        }
        if (rewardAccountBalance != null) {
            parseScaledAmount(rewardAccountBalance);
        }
    }

    private long parseScaledAmount(String amount) {
        if (amount.isBlank()) {
            return 0;
        }
        if ((amount.endsWith("h") || amount.endsWith("H")) && amount.length() > 1) {
            if (Character.isDigit(amount.charAt(amount.length() - 2))) {
                return Long.parseLong(amount.substring(0, amount.length() - 1)) * SUFFIX_SCALES.get("h");
            } else {
                return Long.parseLong(amount.substring(0, amount.length() - 2))
                        * SUFFIX_SCALES.get(
                                amount.substring(amount.length() - 2).toLowerCase());
            }
        } else {
            return Long.parseLong(amount);
        }
    }
}
