// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;

@CommandLine.Command(
        name = "operator",
        mixinStandardHelpOptions = true,
        subcommands = {
            ValidateCommand.class,
            AnalyzeCommand.class,
            IntrospectCommand.class,
            ExportCommand.class,
            CompactionCommand.class,
            ApplyBlocksCommand.class
        },
        description = "CLI tool with validation and introspection modes")
public class StateOperatorCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(StateOperatorCommand.class);

    @Parameters(index = "0", description = "State directory")
    private File stateDir;

    File getStateDir() {
        return stateDir;
    }

    @Override
    public void run() {
        // This runs if no subcommand is provided
        System.out.println("Specify a subcommand (validate/analyze/introspect/export/compact/apply-blocks).");
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        try {
            int exitCode = new CommandLine(new StateOperatorCommand()).execute(args);
            log.info("Execution time: {}ms", System.currentTimeMillis() - startTime);
            System.exit(exitCode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
