// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static org.hiero.base.constructable.GenerateClassId.generateAndPrintClassId;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(
        name = "generate-uid",
        mixinStandardHelpOptions = true,
        description = "Generate a random class ID for a serializable object.")
@SubcommandOf(PlatformCli.class)
public final class GenerateUidCommand extends AbstractCommand {

    private GenerateUidCommand() {}

    @Override
    public Integer call() {
        generateAndPrintClassId();
        return 0;
    }
}
