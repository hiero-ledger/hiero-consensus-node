// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli;

import static com.swirlds.cli.utility.CommandBuilder.buildCommandLine;
import static com.swirlds.cli.utility.CommandBuilder.whitelistCliPackage;

import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.PlatformCliLogo;
import com.swirlds.cli.utility.PlatformCliPreParser;
import com.swirlds.common.formatting.TextEffect;
import com.swirlds.common.startup.Log4jSetup;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The Swirlds Platform CLI.
 */
@Command(
        name = "pcli",
        version = "0.34.0",
        mixinStandardHelpOptions = true,
        description = "Miscellaneous platform utilities.")
public class PlatformCli extends AbstractCommand {

    private static final String DOCUMENTATION_PURPOSES_ONLY =
            "This argument is included here for documentation purposes only.";

    /**
     * Set the paths where jar files should be loaded from.
     */
    @CommandLine.Option(
            names = {"-L", "--load", "--cp"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "A path where additional java libs should be loaded from. "
                    + "Can be a path to a jar file or a path to a directory containing jar files.")
    private void setLoadPath(final List<Path> loadPath) {
        throw buildParameterException(
                "The load path parameter is expected to be parsed prior to the JVM being started. "
                        + DOCUMENTATION_PURPOSES_ONLY);
    }

    @CommandLine.Option(
            names = {"-J", "--jvm"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "An argument that will be passed to the JVM, e.g. '-Xmx10g'")
    private void setJvmArgs(final List<String> jvmArgs) {
        throw buildParameterException("The jvm args parameter is expected to be parsed prior to the JVM being started. "
                + DOCUMENTATION_PURPOSES_ONLY);
    }

    @CommandLine.Option(
            names = {"-C", "--cli"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "A package prefix where CLI commands/subcommands can be found. "
                    + "Commands annotated with '@SubcommandOf' in these packages are automatically "
                    + "integrated into pcli.")
    private void setCliPackagePrefixes(final List<String> cliPackagePrefixes) {
        throw buildParameterException(
                "The cli parameter is expected to be parsed using a different pathway. " + DOCUMENTATION_PURPOSES_ONLY);
    }

    @CommandLine.Option(
            names = {"-D", "--debug"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "Pause the JVM at startup, and wait until a debugger "
                    + "is attached to port 8888 before continuing.")
    private void setDebug(final boolean debug) {
        throw buildParameterException("The debug parameter is expected to be parsed prior to the JVM being started. "
                + DOCUMENTATION_PURPOSES_ONLY);
    }

    @CommandLine.Option(
            names = {"-M", "--memory"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "Set the amount of memory to allocate to the JVM, in gigabytes. "
                    + "'-M 16' is equivalent to '-J -Xmx16g'.")
    private void setJvmArgs(final int memory) {
        throw buildParameterException("The memory parameter is expected to be parsed prior to the JVM being started. "
                + DOCUMENTATION_PURPOSES_ONLY);
    }

    /**
     * Set the log4j path.
     */
    @CommandLine.Option(
            names = {"--log4j"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "The path where the log4j configuration file can be found.")
    private void setLog4jPath(final Path log4jPath) {
        throw buildParameterException(
                "The log4j path parameter is expected to be parsed manually. " + DOCUMENTATION_PURPOSES_ONLY);
    }

    @CommandLine.Option(
            names = {"--no-color"},
            description = "Disable color output. This option is designed for "
                    + "boring people living boring lives, who want their console "
                    + "output to be just as boring as they are.")
    private void setColorDisabled(final boolean colorDisabled) {
        throw buildParameterException(
                "The setColorDisabled parameter is expected to be parsed manually. " + DOCUMENTATION_PURPOSES_ONLY);
    }

    /**
     * Set the paths where jar files should be loaded from.
     */
    @CommandLine.Option(
            names = {"-I", "--ignore-jars"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "If running in a platform development environment, "
                    + "if this flag is present then ignore jar files in the standard platform sdk/data/lib directory. "
                    + "This can be useful if running on a machine with a platform build environment, but where you "
                    + "don't want to load the jars from that build environment. If not running in a platform "
                    + "development environment, this flag has no effect")
    private void setIgnoreJars(final boolean ignoreJars) {
        throw buildParameterException(
                "The ignore jars parameter is expected to be parsed prior to the JVM being started. "
                        + DOCUMENTATION_PURPOSES_ONLY);
    }

    @CommandLine.Option(
            names = {"-B", "--bootstrap"},
            scope = CommandLine.ScopeType.INHERIT,
            description = "The fully qualified name of the function to run before the command is executed."
                    + "Can be used to do arbitrary bootstrapping. Should be a static method "
                    + "that implements the interface Runnable. "
                    + "e.g. '-B com.swirlds.cli.utility.PlatformCliPreParser.exampleBootstrapFunction'")
    private void setBoostrapFunction(final String boostrapFunction) {
        throw buildParameterException("This argument is parsed by the pre-parser. " + DOCUMENTATION_PURPOSES_ONLY);
    }

    /**
     * Before we do the main parsing pass, we first need to extract a small selection of parameters.
     *
     * @param args the program arguments
     * @return the arguments that weren't handled by pre-parsing
     */
    private static PlatformCliPreParser preParse(final String[] args) {
        final CommandLine commandLine = new CommandLine(PlatformCliPreParser.class);
        commandLine.setUnmatchedArgumentsAllowed(true);
        commandLine.execute(args);
        final List<String> unmatchedArgs = commandLine.getUnmatchedArguments();
        final PlatformCliPreParser parser = commandLine.getCommand();
        parser.setUnparsedArgs(unmatchedArgs.toArray(new String[0]));

        return parser;
    }

    /**
     * Main entrypoint for the platform CLI.
     *
     * @param args program arguments
     */
    @SuppressWarnings("java:S106")
    public static void main(final String[] args) throws InterruptedException {
        final PlatformCliPreParser preParser = preParse(args);

        TextEffect.setTextEffectsEnabled(!preParser.isColorDisabled());

        // Will lack actual color if color has been disabled
        System.out.println(PlatformCliLogo.getColorizedLogo());

        final CountDownLatch log4jLatch = Log4jSetup.startLoggingFramework(preParser.getLog4jPath());

        preParser.runBootstrapFunction();

        whitelistCliPackage("com.swirlds.cli");
        whitelistCliPackage("com.swirlds.platform.cli");
        whitelistCliPackage("com.swirlds.platform.state.editor");
        if (preParser.getCliPackagePrefixes() != null) {
            for (final String cliPackagePrefix : preParser.getCliPackagePrefixes()) {
                whitelistCliPackage(cliPackagePrefix);
            }
        }
        final CommandLine commandLine = buildCommandLine(PlatformCli.class);

        log4jLatch.await();
        System.exit(commandLine.execute(preParser.getUnparsedArgs()));
    }
}
