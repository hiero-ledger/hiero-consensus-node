// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine;

/**
 * This class does preliminary parsing for arguments that need to be handled prior to the main CLI argument parsing.
 */
@CommandLine.Command()
public class PlatformCliPreParser implements Runnable {

    private List<String> cliPackagePrefixes;
    private Path log4jPath;
    private String[] unparsedArgs;
    private boolean colorDisabled;
    private String bootstrapFunction;

    private static final ClassLoader classLoader = PlatformCliPreParser.class.getClassLoader();

    @CommandLine.Option(names = {"-C", "--cli"})
    private void setCliPackagePrefixes(final List<String> cliPackagePrefixes) {
        this.cliPackagePrefixes = cliPackagePrefixes;
    }

    /**
     * Set the log4j path.
     */
    @CommandLine.Option(names = {"-L", "--log4j"})
    private void setLog4jPath(final Path log4jPath) {
        this.log4jPath = log4jPath;
    }

    @CommandLine.Option(names = {"--no-color"})
    private void setColorDisabled(final boolean colorDisabled) {
        this.colorDisabled = colorDisabled;
    }

    @CommandLine.Option(names = {"-B", "--bootstrap"})
    private void setBoostrapFunction(final String boostrapFunction) {
        this.bootstrapFunction = boostrapFunction;
    }

    /**
     * Get CLI package prefixes that should be scanned for additional CLI commands. Returns null if there are none.
     */
    public List<String> getCliPackagePrefixes() {
        return cliPackagePrefixes;
    }

    /**
     * The path where log4j configuration can be found. Returns null if there is none provided.
     */
    public Path getLog4jPath() {
        return log4jPath;
    }

    /**
     * Check if color is disabled.
     */
    public boolean isColorDisabled() {
        return colorDisabled;
    }

    /**
     * Get the arguments that were not parsed by this object.
     */
    public String[] getUnparsedArgs() {
        return unparsedArgs;
    }

    /**
     * Set the arguments that were not parsed by this object.
     */
    public void setUnparsedArgs(final String[] unparsedArgs) {
        this.unparsedArgs = unparsedArgs;
    }

    /**
     * An example bootstrap function.
     */
    public static void exampleBootstrapFunction() {
        System.out.println("running example bootstrap function");
    }

    /**
     * Run the bootstrap function if one was provided.
     */
    public void runBootstrapFunction() {
        if (bootstrapFunction == null) {
            return;
        }

        try {
            final int lastPeriodIndex = bootstrapFunction.lastIndexOf('.');
            final String className = bootstrapFunction.substring(0, lastPeriodIndex);
            final String methodName = bootstrapFunction.substring(lastPeriodIndex + 1);

            final Class<?> clazz = classLoader.loadClass(className);
            final Method method = clazz.getMethod(methodName);

            method.invoke(null);

        } catch (final Throwable t) {
            System.err.println("Error running bootstrap function " + bootstrapFunction);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        // no-op
    }
}
