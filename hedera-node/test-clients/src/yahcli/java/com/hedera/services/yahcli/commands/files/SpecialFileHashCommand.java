// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.files;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.SpecialFileHashSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "hash-check",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Checks the hash of a special file")
public class SpecialFileHashCommand implements Callable<Integer> {
    @ParentCommand
    private SysFilesCommand sysFilesCommand;

    @Parameters(paramLabel = "<special-file>", description = "{ software-zip, telemetry-zip }")
    private String specialFile;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(sysFilesCommand.getYahcli());

        var delegate = new SpecialFileHashSuite(config, specialFile);
        delegate.runSuiteSync();

        return (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) ? 0 : 1;
    }
}
