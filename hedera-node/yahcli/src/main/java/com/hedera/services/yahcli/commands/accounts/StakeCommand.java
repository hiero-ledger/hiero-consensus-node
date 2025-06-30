// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigManager;
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
        assertValidParams(config);
        final String target;
        final StakeSuite.TargetType type;
        final var normalizedElectedAccountNum = normalizePossibleIdLiteral(config, electedAccountNum);
        if (normalizedElectedAccountNum != null) {
            type = StakeSuite.TargetType.NODE;
            target = normalizedElectedAccountNum;
        } else if (normalizedElectedAccountNum != null) {
            type = StakeSuite.TargetType.ACCOUNT;
            target = normalizedElectedAccountNum;
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
        var normalizedStakedAccountNum = normalizePossibleIdLiteral(config, stakedAccountNum);
        final var delegate =
                new StakeSuite(config, config.asSpecConfig(), target, type, normalizedStakedAccountNum, declineReward);
        delegate.runSuiteSync();

        if (normalizedStakedAccountNum == null) {
            normalizedStakedAccountNum = asEntityString(config.getDefaultPayer());
        }
        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            final var msgSb = new StringBuilder("SUCCESS - account ")
                    .append(Utils.extractAccount(normalizedStakedAccountNum))
                    .append(" updated");
            final var normalizedElectedNodeId = normalizePossibleIdLiteral(config, electedNodeId);
            if (type != StakeSuite.TargetType.NONE) {
                msgSb.append(", now staked to ")
                        .append(type.name())
                        .append(" ")
                        .append(
                                type == StakeSuite.TargetType.NODE
                                        ? normalizedElectedNodeId
                                        : Utils.extractAccount(normalizedElectedAccountNum));
            }
            if (declineReward != null) {
                msgSb.append(" with declineReward=").append(declineReward);
            }
            COMMON_MESSAGES.info(msgSb.toString());
        } else {
            COMMON_MESSAGES.warn("FAILED to change staking election for account "
                    + Utils.extractAccount(normalizedStakedAccountNum));
            return 1;
        }

        return 0;
    }

    @SuppressWarnings({"java:S3776", "java:S1192"})
    private void assertValidParams(ConfigManager config) {
        if (stopDecliningRewards != null && startDecliningRewards != null) {
            throw new CommandLine.ParameterException(
                    accountsCommand.getYahcli().getSpec().commandLine(),
                    "Cannot both start and stop declining rewards");
        }
        final var normalizedElectedNodeId = normalizePossibleIdLiteral(config, electedNodeId);
        final var normalizedElectedAccountNum = normalizePossibleIdLiteral(config, electedAccountNum);
        final var changedDeclineRewards = startDecliningRewards != null || stopDecliningRewards != null;
        if (normalizedElectedNodeId != null) {
            if (normalizedElectedAccountNum != null) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "Cannot stake to both node (" + normalizedElectedNodeId + ") and account ("
                                + normalizedElectedAccountNum + ")");
            }
            try {
                Long.parseLong(normalizedElectedNodeId);
            } catch (final Exception any) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "--node-id value '" + normalizedElectedNodeId + "' is un-parseable (" + any.getMessage() + ")");
            }
        } else if (normalizedElectedAccountNum == null && !changedDeclineRewards) {
            throw new CommandLine.ParameterException(
                    accountsCommand.getYahcli().getSpec().commandLine(),
                    "Must stake to either a node or an account ("
                            + normalizedElectedAccountNum
                            + "); or "
                            + "start/stop declining rewards");
        } else if (normalizedElectedAccountNum != null) {
            try {
                Utils.extractAccount(normalizedElectedAccountNum);
            } catch (final Exception any) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "--account-num value '" + normalizedElectedAccountNum + "' is un-parseable (" + any.getMessage()
                                + ")");
            }
        }

        final var normalizedStakedAccountNum = normalizePossibleIdLiteral(config, stakedAccountNum);
        if (normalizedStakedAccountNum != null) {
            try {
                Utils.extractAccount(normalizedStakedAccountNum);
            } catch (final Exception any) {
                throw new CommandLine.ParameterException(
                        accountsCommand.getYahcli().getSpec().commandLine(),
                        "staked account parameter '"
                                + normalizedStakedAccountNum
                                + "' is un-parseable ("
                                + any.getMessage()
                                + ")");
            }
        }
    }
}
