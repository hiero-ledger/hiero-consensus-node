// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.io;

import static com.swirlds.logging.api.extensions.handler.LogHandler.PROPERTY_HANDLER;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.utils.ConfigUtils;
import com.swirlds.logging.utils.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A factory for {@link OutputStream} instances based on {@link Configuration} values.
 */
public class OutputStreamFactory {
    private static final String FILE_NAME_PROPERTY = ".file";
    private static final String APPEND_PROPERTY = ".append";
    private static final String SIZE_PROPERTY = ".file-rolling.maxFileSize";
    private static final String MAX_ROLLOVER = ".file-rolling.maxFiles";
    private static final String DEFAULT_FILE_NAME = "swirlds-log.log";
    private static final int DEFAULT_MAX_ROLLOVER_FILES = 1;
    private static final int BUFFER_CAPACITY = 8192 * 8;

    private static final class InstanceHolder {
        private static final OutputStreamFactory INSTANCE = new OutputStreamFactory();
    }

    public static OutputStreamFactory getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Creates an output stream based on the {@code configuration} parameter. It also checks the folder for the
     * directory under  {@code propertyPrefix + FILE_NAME_PROPERTY} exists or creates it
     *
     * @param configuration the configuration to read the properties from
     * @param handlerName   the name of the handler that is configuring the output-stream
     * @return a {@link FileOutputStream} or a {@link RolloverFileOutputStream} depending on the configuration
     * @throws IOException          if there was a problem when creating the underlying file
     * @throws NullPointerException if any of the parameters is null
     */
    @NonNull
    public OutputStream outputStream(final @NonNull Configuration configuration, final @NonNull String handlerName)
            throws IOException {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(handlerName, "handlerName must not be null");

        final String propertyPrefix = PROPERTY_HANDLER.formatted(handlerName);

        final Path filePath = ConfigUtils.configValueOrElse(
                configuration, propertyPrefix + FILE_NAME_PROPERTY, Path.class, Path.of(DEFAULT_FILE_NAME));

        try {
            FileUtils.checkOrCreateParentDirectory(filePath);
            final boolean append =
                    ConfigUtils.configValueOrElse(configuration, propertyPrefix + APPEND_PROPERTY, Boolean.class, true);
            final Long maxFileSize = ConfigUtils.readDataSizeInBytes(configuration, propertyPrefix + SIZE_PROPERTY);

            if (maxFileSize == null) {
                return new FileOutputStream(filePath.toFile(), append);
            }

            final int maxRollingOver = ConfigUtils.configValueOrElse(
                    configuration, propertyPrefix + MAX_ROLLOVER, Integer.class, DEFAULT_MAX_ROLLOVER_FILES);
            return new RolloverFileOutputStream(filePath, maxFileSize, append, maxRollingOver);
        } catch (IOException | IllegalStateException e) {
            throw new IOException("Could not create log file " + filePath.toAbsolutePath(), e);
        }
    }

    /**
     * Creates an output stream based on the {@code configuration} parameter. It also checks the folder for the
     * directory under  {@code propertyPrefix + FILE_NAME_PROPERTY} exists or creates it
     *
     * @param configuration the configuration to read the properties from
     * @param handlerName   the name of the handler that is configuring the output-stream
     * @return a {@link BufferedOutputStream} wrapping a {@link FileOutputStream} or a {@link RolloverFileOutputStream}
     * depending on the configuration
     * @throws IOException          if there was a problem when creating the underlying file
     * @throws NullPointerException if any of the parameters is null
     */
    @NonNull
    public OutputStream bufferedOutputStream(
            final @NonNull Configuration configuration, final @NonNull String handlerName) throws IOException {

        return new BufferedOutputStream(outputStream(configuration, handlerName), BUFFER_CAPACITY);
    }
}
