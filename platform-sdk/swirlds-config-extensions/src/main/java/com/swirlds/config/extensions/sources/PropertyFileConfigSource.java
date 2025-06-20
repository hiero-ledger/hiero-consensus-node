// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link com.swirlds.config.api.source.ConfigSource} implementation that can be used to provide values from a
 * property file.
 */
public class PropertyFileConfigSource extends AbstractFileConfigSource {

    /**
     * Creates a new instance based on a file by using the {@link ConfigSourceOrdinalConstants#PROPERTY_FILE_ORDINAL}
     * ordinal.
     *
     * @param filePath the properties file
     * @throws IOException if the file can not loaded or parsed
     */
    public PropertyFileConfigSource(@NonNull final Path filePath) throws IOException {
        super(filePath);
    }

    /**
     * Creates a new instance based on a file.
     *
     * @param filePath the properties file
     * @param ordinal  the ordinal of the config source
     * @throws IOException if the file can not loaded or parsed
     */
    public PropertyFileConfigSource(@NonNull final Path filePath, final int ordinal) throws IOException {
        super(filePath, ordinal);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    protected BufferedReader getReader() throws IOException {
        return Files.newBufferedReader(filePath);
    }
}
