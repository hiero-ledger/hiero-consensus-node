// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Bucket access credentials for ISS block upload (an access key / secret key pair).
 *
 * <p>Loaded from a {@code key=value} properties file (keys {@code accessKey} and {@code secretKey}); either value may
 * be overridden by the {@code ISS_BUCKET_ACCESS_KEY} / {@code ISS_BUCKET_SECRET_KEY} environment variables, which take
 * precedence and suit secret-manager / container deployments. Credentials are deliberately never logged (see
 * {@link #toString()}) and never flow through the configuration system.
 *
 * @param accessKey the bucket access key (for GCP, the HMAC interoperability access key)
 * @param secretKey the bucket secret key (for GCP, the HMAC interoperability secret)
 */
public record BucketCredentials(
        @NonNull String accessKey, @NonNull String secretKey) {
    /** Environment variable that overrides the access key from the credentials file. */
    public static final String ENV_ACCESS_KEY = "ISS_BUCKET_ACCESS_KEY";
    /** Environment variable that overrides the secret key from the credentials file. */
    public static final String ENV_SECRET_KEY = "ISS_BUCKET_SECRET_KEY";

    private static final String PROP_ACCESS_KEY = "accessKey";
    private static final String PROP_SECRET_KEY = "secretKey";

    public BucketCredentials {
        requireNonNull(accessKey);
        requireNonNull(secretKey);
    }

    /**
     * Loads credentials from the given properties file, then applies environment-variable overrides (which take
     * precedence per field). The file need not exist if both environment variables are set.
     *
     * @param credentialsFile the path to the credentials properties file
     * @return the resolved credentials
     * @throws IllegalStateException if either credential is missing/blank after considering the file and environment
     */
    @NonNull
    public static BucketCredentials load(@NonNull final Path credentialsFile) {
        requireNonNull(credentialsFile);
        String accessKey = null;
        String secretKey = null;
        if (Files.isRegularFile(credentialsFile)) {
            final var props = new Properties();
            try (final InputStream in = Files.newInputStream(credentialsFile)) {
                props.load(in);
            } catch (final IOException e) {
                throw new IllegalStateException("Failed to read ISS bucket credentials from " + credentialsFile, e);
            }
            accessKey = trimToNull(props.getProperty(PROP_ACCESS_KEY));
            secretKey = trimToNull(props.getProperty(PROP_SECRET_KEY));
        }
        final String envAccess = trimToNull(System.getenv(ENV_ACCESS_KEY));
        if (envAccess != null) {
            accessKey = envAccess;
        }
        final String envSecret = trimToNull(System.getenv(ENV_SECRET_KEY));
        if (envSecret != null) {
            secretKey = envSecret;
        }
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException("Missing ISS bucket credentials: provide '" + credentialsFile
                    + "' with '" + PROP_ACCESS_KEY + "'/'" + PROP_SECRET_KEY + "', or set the " + ENV_ACCESS_KEY + "/"
                    + ENV_SECRET_KEY + " environment variables");
        }
        return new BucketCredentials(accessKey, secretKey);
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "BucketCredentials{accessKey=***, secretKey=***}";
    }
}
