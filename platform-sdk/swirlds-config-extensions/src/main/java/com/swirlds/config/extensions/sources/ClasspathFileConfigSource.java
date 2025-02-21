// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link ConfigSource} that can read files in the application classpath.
 */
public class ClasspathFileConfigSource extends AbstractFileConfigSource {

    /**
     * Creates a new instance based on a file
     *
     * @param filePath the properties file
     * @throws IOException if the file can not be loaded or parsed
     */
    public ClasspathFileConfigSource(final @NonNull Path filePath) throws IOException {
        super(filePath);
    }

    /**
     * Creates a new instance based on a file
     *
     * @param filePath the properties file
     * @param ordinal  the ordinal of the config source
     * @throws IOException if the file can not be loaded or parsed
     */
    public ClasspathFileConfigSource(final @NonNull Path filePath, final int ordinal) throws IOException {
        super(filePath, ordinal);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    protected BufferedReader getReader() {
        final InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(filePath.toString());
        Objects.requireNonNull(inputStream, "Could not load properties from classpath resource " + filePath);
        return new BufferedReader(new InputStreamReader(inputStream));
    }
}
