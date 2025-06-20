// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.util.FileSigningUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

/**
 * An abstract command type for generating signature files
 */
public abstract class SignCommand extends AbstractCommand {
    private static final Logger logger = LogManager.getLogger(SignCommand.class);

    /**
     * The paths to the sources that signature files will be generated for. Can contain individual files, as well as
     * directories
     * <p>
     * If an element of this list is a directory, then all files in that directory will be signed, recursively
     * <p>
     * Defaults to the current working directory
     */
    private List<Path> pathsToSign = List.of(FileUtils.getAbsolutePath());

    /**
     * The directory where signature files will be generated. Defaults to in-place signatures (null)
     */
    @Nullable
    private Path destinationDirectory = null;

    /**
     * The path to the key file to use to generate signatures
     */
    private Path keyFilePath;

    /**
     * The password to the key file
     */
    private String keyFilePassword;

    /**
     * The alias of the key in the key file
     */
    private String keyAlias;

    /**
     * The key pair to use to generate signatures
     */
    private KeyPair keyPair;

    @CommandLine.Parameters(description = "The path to the key file to use to generate signatures", index = "0")
    private void setKeyFile(@NonNull final Path keyFilePath) {
        this.keyFilePath = pathMustExist(keyFilePath.toAbsolutePath().normalize());
    }

    @CommandLine.Parameters(description = "The password to the key file", index = "1")
    private void setKeyFilePassword(@NonNull final String keyFilePassword) {
        this.keyFilePassword = keyFilePassword;
    }

    @CommandLine.Parameters(description = "The alias of the key in the key file", index = "2")
    private void setKeyAlias(@NonNull final String keyAlias) {
        this.keyAlias = keyAlias;
    }

    @CommandLine.Option(
            names = {"-p", "--paths-to-sign"},
            description = "The paths to what will be signed. Can contain single files, as well as directories."
                    + "Defaults to the current working directory")
    private void setPathsToSign(@NonNull final List<Path> pathsToSign) {
        this.pathsToSign = pathsToSign.stream()
                .map((path -> pathMustExist(path.toAbsolutePath().normalize())))
                .toList();
    }

    @CommandLine.Option(
            names = {"-d", "--destination-directory"},
            description = "Specify the destination directory where signature files will be generated. If specified,"
                    + "source files will be copied to the destination directory. If not specified,"
                    + "the signature file will simply be generated in the same directory as the source file")
    private void setDestinationDirectory(@NonNull final Path destinationDirectory) {
        this.destinationDirectory = destinationDirectory.toAbsolutePath().normalize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Integer call() {
        keyPair = FileSigningUtils.loadPfxKey(keyFilePath, keyFilePassword, keyAlias);

        pathsToSign.forEach(this::signFilesAtPath);

        return 0;
    }

    /**
     * Generate a signature file for a single source file
     *
     * @param signatureFileDestination the full path where the signature file will be generated
     * @param fileToSign               the file to generate a signature file for
     * @param keyPair                  the key pair to use to generate the signature
     * @return true if the signature file was generated successfully, false otherwise
     */
    public abstract boolean generateSignatureFile(
            @NonNull final Path signatureFileDestination,
            @NonNull final Path fileToSign,
            @NonNull final KeyPair keyPair);

    /**
     * Check if a file is supported by this command
     *
     * @param path the path to the file to check
     * @return true if the file is supported, false otherwise
     */
    public abstract boolean isFileSupported(@NonNull final Path path);

    /**
     * Perform necessary tasks to sign a single file. Generates a signature file via {@link #generateSignatureFile} if
     * one doesn't already exist
     * <p>
     * If a destinationDirectory has been specified, the source file will additionally be copied to the destination
     * directory
     *
     * @param fileToSign the file to generate a signature file for
     */
    private void signFile(@NonNull final Path fileToSign) {
        final Path signatureFileDestinationPath;
        if (destinationDirectory == null) {
            // if destinationDirectory is null, then we are generating in-place signatures
            signatureFileDestinationPath = FileSigningUtils.buildSignatureFilePath(fileToSign.getParent(), fileToSign);
        } else {
            signatureFileDestinationPath = FileSigningUtils.buildSignatureFilePath(destinationDirectory, fileToSign);
        }

        // if signature file already exists, don't continue
        if (Files.exists(signatureFileDestinationPath)) {
            logger.warn(
                    LogMarker.CLI.getMarker(),
                    "Signature file {} already exists. Skipping file {}",
                    signatureFileDestinationPath,
                    fileToSign);
            return;
        }

        // if signature generation fails, don't continue
        if (!generateSignatureFile(signatureFileDestinationPath, fileToSign, keyPair)) {
            // don't print anything here, as a specific error message should be printed by the signing implementation
            return;
        }

        // if input destinationDirectory was null, then we generated in-place signatures. No need to copy source files
        if (destinationDirectory == null) {
            return;
        }

        try {
            Files.copy(fileToSign, destinationDirectory.resolve(fileToSign.getFileName()));
        } catch (final IOException e) {
            logger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "Failed to copy source file {} to destination directory {}",
                    fileToSign.getFileName(),
                    destinationDirectory,
                    e);
        }
    }

    /**
     * Sign file(s) at a path. Individual files will simply be signed, and directories will have all supported files
     * signed, recursively
     *
     * @param path the path to sign files at
     */
    private void signFilesAtPath(@NonNull final Path path) {
        try (final Stream<Path> stream = Files.walk(path)) {
            stream.filter(this::isFileSupported).forEach(this::signFile);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to list files: " + path);
        }
    }
}
