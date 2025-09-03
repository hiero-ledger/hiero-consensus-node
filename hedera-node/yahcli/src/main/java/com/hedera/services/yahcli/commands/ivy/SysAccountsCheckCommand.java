package com.hedera.services.yahcli.commands.ivy;

import com.hedera.services.yahcli.config.ConfigUtils;
import picocli.CommandLine;

import java.util.concurrent.Callable;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

@CommandLine.Command(
        name = "sys-accounts-check",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Verifies that HAPI reports absence of legacy system accounts")
public class SysAccountsCheckCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    IvyCommand ivyCommand;

    @Override
    public Integer call() throws Exception {
        final var yahcli = ivyCommand.getYahcli();
        final var config = ConfigUtils.configFrom(yahcli);
        final var scenariosLoc = config.scenariosDirOrThrow();
        final var specConfig = config.asSpecConfig();
    }
}
