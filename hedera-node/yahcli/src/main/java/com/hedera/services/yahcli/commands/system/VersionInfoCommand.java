// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.system;

import com.hedera.services.bdd.suites.meta.VersionInfoSpec;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.config.ConfigUtils;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "version",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Get the deployed version of a network")
public final class VersionInfoCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        final var config = ConfigUtils.configFrom(yahcli);

        final var delegate = new VersionInfoSpec(config.asSpecConfig());

        delegate.runSuiteSync();

        return 0;
    }
}
