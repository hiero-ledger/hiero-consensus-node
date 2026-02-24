// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import static org.hiero.consensus.pcli.utility.CommandBuilder.buildCommandLine;
import static org.hiero.consensus.pcli.utility.CommandBuilder.whitelistCliPackage;

import com.swirlds.base.formatting.TextEffect;
import com.swirlds.common.startup.Log4jSetup;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.hiero.consensus.pcli.utility.CommandBuilder;
import org.hiero.consensus.pcli.utility.PlatformCliLogo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;

/**
 * The Hiero Platform CLI.
 */
@Command(
        name = "pcli",
        version = "0.40.0",
        mixinStandardHelpOptions = true,
        description = "Miscellaneous platform utilities.")
public class Pcli extends AbstractCommand {

    @Option(
            names = {"-B", "--banner"},
            description = "Show the ASCII banner on startup")
    private boolean showBanner = false;

    @Option(
            names = {"--no-color"},
            description = "Disable colored output")
    private boolean noColor = false;

    @Option(
            names = {"--log4j"},
            description = "Path to log4j configuration file")
    private Path log4jPath;

    @Option(
            names = {"-C", "--cli"},
            description =
                    "Package prefix where CLI commands/subcommands can be found. "
                            + "Commands annotated with '@SubcommandOf' in these packages are automatically integrated into pcli.")
    private List<String> cliPackagePrefixes;

    /**
     * Custom execution strategy that handles setup before running commands.
     */
    static class SetupExecutionStrategy implements CommandLine.IExecutionStrategy {
        @Override
        public int execute(final ParseResult parseResult) {
            final Pcli pcli = parseResult.commandSpec().commandLine().getCommand();

            // Setup text effects
            TextEffect.setTextEffectsEnabled(!pcli.noColor);

            // Show banner if enabled (checks both --banner flag and system property from bash script)
            final boolean showBannerFromScript = Boolean.parseBoolean(System.getProperty("pcli.showBanner", "false"));
            if (pcli.showBanner || showBannerFromScript) {
                System.out.println(PlatformCliLogo.getColorizedLogo());
            }

            // Setup logging
            final CountDownLatch log4jLatch = Log4jSetup.startLoggingFramework(pcli.log4jPath);

            if (pcli.cliPackagePrefixes != null) {
                pcli.cliPackagePrefixes.forEach(CommandBuilder::whitelistCliPackage);
            }

            // Wait for logging to be ready
            try {
                log4jLatch.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for logging setup", e);
            }

            // Execute the actual command
            return new CommandLine.RunLast().execute(parseResult);
        }
    }

    /**
     * Main entrypoint for the platform CLI.
     *
     * @param args program arguments
     */
    @SuppressWarnings("java:S106")
    public static void main(final String[] args) {
        // Whitelist CLI packages
        whitelistCliPackage("org.hiero.consensus.pcli");
        whitelistCliPackage("com.swirlds.platform.state.editor");
        final CommandLine commandLine = buildCommandLine(Pcli.class);
        commandLine.setExecutionStrategy(new SetupExecutionStrategy());
        System.exit(commandLine.execute(args));
    }
}
