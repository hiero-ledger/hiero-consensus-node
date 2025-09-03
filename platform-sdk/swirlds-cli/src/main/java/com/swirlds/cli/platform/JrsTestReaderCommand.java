// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(
        name = "jtr",
        mixinStandardHelpOptions = true,
        description = "JRS Test Reader: scrapes data and generates test reports.")
@SubcommandOf(PlatformCli.class)
public class JrsTestReaderCommand extends AbstractCommand {

    private JrsTestReaderCommand() {}
}
