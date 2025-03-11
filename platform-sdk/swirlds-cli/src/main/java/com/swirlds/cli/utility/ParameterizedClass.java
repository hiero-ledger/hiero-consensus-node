// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;

/**
 * A picocli class that contains parameter definitions.
 */
public class ParameterizedClass {

    @CommandLine.Spec
    @SuppressWarnings("unused") // used by picocli
    private CommandLine.Model.CommandSpec spec;

    /**
     * Get the picocli command line spec.
     */
    public CommandLine.Model.CommandSpec getSpec() {
        return spec;
    }

    /**
     * Build an exception caused by invalid parameters.
     *
     * @param message the message on the exception
     */
    protected @NonNull CommandLine.ParameterException buildParameterException(@NonNull final String message) {
        throw new CommandLine.ParameterException(spec.commandLine(), message);
    }

    /**
     * Ensure that a path from the command line exists and that this process has read access to it.
     *
     * @param path a path from the command line
     * @return the path if it passes validation
     */
    protected @NonNull Path pathMustExist(@NonNull final Path path) {
        if (!Files.exists(path)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Path " + path + " does not exist");
        }
        if (!Files.isReadable(path)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "No read permission for " + path);
        }
        return path;
    }

    /**
     * Ensure that a path from the command line exists and that it is a directory.
     *
     * @param path a path from the command line
     * @return the path if it passes validation
     */
    protected @NonNull Path dirMustExist(@NonNull final Path path) {
        pathMustExist(path);
        if (!Files.isDirectory(path)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Path " + path + " is not a directory");
        }
        return path;
    }

    /**
     * Ensure that a path from the command line does not exist.
     *
     * @param path a path from the command line
     * @return the path if it passes validation
     */
    protected @NonNull Path pathMustNotExist(@NonNull final Path path) {
        if (Files.exists(path)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Path " + path + " already exists");
        }
        return path;
    }

    /**
     * Ensure that a path exists from the command line to a file and return the file handle.
     *
     * @param path a path from the command line
     * @return the file if it passes validation
     */
    protected @NonNull File fileMustExist(@NonNull final Path path) {
        if (!Files.isRegularFile(pathMustExist(path))) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Path " + path + " is not a file.");
        }
        return path.toFile();
    }
}
