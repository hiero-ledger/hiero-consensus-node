// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.files;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.SysFileDownloadSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "download",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Downloads system files")
public class SysFileDownloadCommand implements Callable<Integer> {
    @ParentCommand
    private SysFilesCommand sysFilesCommand;

    @CommandLine.Option(
            names = {"-d", "--dest-dir"},
            paramLabel = "destination directory",
            defaultValue = "{network}/sysfiles/")
    private String destDir;

    @Parameters(
            arity = "1..*",
            paramLabel = "<sysfiles>",
            description = "one or more from "
                    + "{ address-book, node-details, fees, rates, props, "
                    + "permissions, throttles, software-zip, telemetry-zip } (or "
                    + "{ 101, 102, 111, 112, 121, 122, 123, 150, 159 })---or 'all'")
    private String[] sysFiles;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(sysFilesCommand.getYahcli());
        destDir = SysFilesCommand.resolvedDir(destDir, config);

        var delegate = new SysFileDownloadSuite(destDir, config, sysFiles);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - downloaded all requested system files");
        } else {
            COMMON_MESSAGES.warn("FAILED downloading requested system files");
            return 1;
        }

        return 0;
    }
}
