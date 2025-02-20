// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.props;

import static com.swirlds.common.io.utility.FileUtils.rethrowIO;

import com.google.common.io.ByteSource;
import com.google.common.io.Resources;
import com.hedera.services.bdd.spec.HapiPropertySource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JutilPropertySource implements HapiPropertySource {
    static final Logger log = LogManager.getLogger(JutilPropertySource.class);

    private static final String DEFAULT_PROPERTY_PATH = "spec-default.properties";
    private static final JutilPropertySource DEFAULT_INSTANCE;

    static {
        DEFAULT_INSTANCE = new JutilPropertySource(DEFAULT_PROPERTY_PATH);
    }

    public static JutilPropertySource getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    private final Properties props;

    public JutilPropertySource(String resource) {
        if (!resource.endsWith(".properties")) {
            resource += ".properties";
        }
        props = new Properties();
        loadInto(props, resource);
    }

    public JutilPropertySource(@NonNull final Path path) {
        Objects.requireNonNull(path);
        props = new Properties();
        rethrowIO(() -> props.load(Files.newInputStream(path)));
    }

    @Override
    public boolean has(String property) {
        return props.containsKey(property);
    }

    @Override
    public String get(String property) {
        return props.getProperty(property);
    }

    public static void loadInto(Properties target, String path) {
        try {
            URL url = Resources.getResource(path);
            final ByteSource byteSource = Resources.asByteSource(url);
            try (InputStream inputStream = byteSource.openBufferedStream()) {
                target.load(inputStream);
            } catch (IOException ioE) {
                final String message = String.format("Unable to load properties from '%s'!", path);
                log.warn(message, ioE);
            }
        } catch (Exception e) {
            final String message = String.format("Unable to load properties from '%s'!", path);
            log.warn(message, e);
        }
    }
}
