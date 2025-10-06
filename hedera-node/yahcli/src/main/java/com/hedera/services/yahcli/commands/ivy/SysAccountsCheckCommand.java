// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy;

import static com.hedera.services.bdd.spec.HapiSpec.SpecStatus.PASSED;

import com.hedera.services.yahcli.commands.ivy.suites.IvySysAccountsCheckSuite;
import com.hedera.services.yahcli.config.ConfigUtils;
import java.util.concurrent.Callable;
import picocli.CommandLine;

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
        final var specConfig = config.asSpecConfig();
        final var delegate = new IvySysAccountsCheckSuite(specConfig);
        delegate.runSuiteSync();
        return delegate.getFinalSpecs().getFirst().getStatus() == PASSED ? 0 : 1;
    }
}
