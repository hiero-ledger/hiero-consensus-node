// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.StakeSuite;
import com.hedera.services.yahcli.suites.Utils;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "stake",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Changes the staking election for an account")
public class StakeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    AccountsCommand accountsCommand;

    @CommandLine.Option(
            names = {"-n", "--to-node-id"},
            paramLabel = "id of node to stake to")
    String electedNodeId;

    @CommandLine.Option(
            names = {"-a", "--to-account-num"},
            paramLabel = "the account to stake to")
    String electedAccountNum;

    @CommandLine.Option(
            names = {"--stop-declining-rewards"},
            paramLabel = "trigger to add declineReward=false")
    Boolean stopDecliningRewards;

    @CommandLine.Option(
            names = {"--start-declining-rewards"},
            paramLabel = "trigger to add declineReward=true")
    Boolean startDecliningRewards;

    @CommandLine.Parameters(arity = "0..1", paramLabel = "<account>", description = "the account to stake")
    String stakedAccountNum;

    @Override
    public Integer call() throws Exception {
        final var config = ConfigUtils.configFrom(accountsCommand.getYahcli());
        assertValidParams();
        final String target;
        final StakeSuite.TargetType type;
        if (electedNodeId != null) {
            type = StakeSuite.TargetType.NODE;
            target = electedNodeId;
        } else if (electedAccountNum != null) {
            type = StakeSuite.TargetType.ACCOUNT;
            target = electedAccountNum;
        } else {
            target = null;
            type = StakeSuite.TargetType.NONE;
        }
        Boolean declineReward = null;
        if (startDecliningRewards != null) {
            declineReward = Boolean.TRUE;
        } else if (stopDecliningRewards != null) {
            declineReward = Boolean.FALSE;
        }
        final var delegate =
                new StakeSuite(config, config.asSpecConfig(), target, type, stakedAccountNum, declineReward);
        delegate.runSuiteSync();

        if (stakedAccountNum == null) {
            stakedAccountNum = asEntityString(config.getDefaultPayer());
        }
        if (delegate.getFinalSpecs().get(0).getStatus() == HapiSpec.SpecStatus.PASSED) {
            final var msgSb = new StringBuilder("SUCCESS - account ")
                    .append(Utils.extractAccount(stakedAccountNum))
                    .append(" updated");
            if (type != StakeSuite.TargetType.NONE) {
                msgSb.append(", now staked to ")
                        .append(type.name())
                        .append(" ")
                        .append(
                                type == StakeSuite.TargetType.NODE
                                        ? electedNodeId
                                        : Utils.extractAccount(electedAccountNum));
            }
            if (declineReward != null) {
                msgSb.append(" with declineReward=").append(declineReward);
            }
            COMMON_MESSAGES.info(msgSb.toString());
        } else {
            COMMON_MESSAGES.warn(
                    "FAILED to change staking election for account " + Utils.extractAccount(stakedAccountNum));
            return 1;
        }

        return 0;
    }

    @SuppressWarnings({"java:S3776", "java:S1192"})
    private void assertValidParams() {
        if (stopDecliningRewards != null && startDecliningRewards != null) {
            throw new CommandLine.ParameterException(
                    accountsCommand.getYahcli().getSpec().commandLine(),
                    "Cannot both start and stop declining rewards");
        }
        final var changedDeclineRewards = startDecliningRewards != null || stopDecliningRewards != null;
        if (electedNodeId != null) {
            if (electedAccountNum != null) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "Cannot stake to both node (" + electedNodeId + ") and account (" + electedAccountNum + ")");
            }
            try {
                Long.parseLong(electedNodeId);
            } catch (final Exception any) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "--node-id value '" + electedNodeId + "' is un-parseable (" + any.getMessage() + ")");
            }
        } else if (electedAccountNum == null && !changedDeclineRewards) {
            throw new CommandLine.ParameterException(
                    accountsCommand.getYahcli().getSpec().commandLine(),
                    "Must stake to either a node or an account ("
                            + electedAccountNum
                            + "); or "
                            + "start/stop declining rewards");
        } else if (electedAccountNum != null) {
            try {
                Utils.extractAccount(electedAccountNum);
            } catch (final Exception any) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "--account-num value '" + electedAccountNum + "' is un-parseable (" + any.getMessage() + ")");
            }
        }

        if (stakedAccountNum != null) {
            try {
                Utils.extractAccount(stakedAccountNum);
            } catch (final Exception any) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "staked account parameter '"
                                + stakedAccountNum
                                + "' is un-parseable ("
                                + any.getMessage()
                                + ")");
            }
        }
    }
}
