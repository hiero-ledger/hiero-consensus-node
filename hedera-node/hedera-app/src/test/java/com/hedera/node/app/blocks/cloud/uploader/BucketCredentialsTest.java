// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BucketCredentialsTest {

    @TempDir
    Path tempDir;

    /** All tests resolve the environment through the seam, so the JVM's real env can never leak in. */
    private static final UnaryOperator<String> NO_ENV = name -> null;

    @Test
    void loadsKeysFromPropertiesFile() throws IOException {
        final Path file = write("accessKey=fileAccess\nsecretKey=fileSecret\n");

        final BucketCredentials credentials = BucketCredentials.load(file, NO_ENV);

        assertThat(credentials.accessKey()).isEqualTo("fileAccess");
        assertThat(credentials.secretKey()).isEqualTo("fileSecret");
    }

    @Test
    void environmentVariablesOverrideTheFilePerField() throws IOException {
        final Path file = write("accessKey=fileAccess\nsecretKey=fileSecret\n");
        // Only the secret is overridden; the access key must still come from the file.
        final UnaryOperator<String> env = name -> BucketCredentials.ENV_SECRET_KEY.equals(name) ? "envSecret" : null;

        final BucketCredentials credentials = BucketCredentials.load(file, env);

        assertThat(credentials.accessKey()).isEqualTo("fileAccess");
        assertThat(credentials.secretKey()).isEqualTo("envSecret");
    }

    @Test
    void environmentAloneSufficesWithoutAFile() {
        final Map<String, String> values = Map.of(
                BucketCredentials.ENV_ACCESS_KEY, "envAccess",
                BucketCredentials.ENV_SECRET_KEY, "envSecret");

        final BucketCredentials credentials = BucketCredentials.load(tempDir.resolve("absent.properties"), values::get);

        assertThat(credentials.accessKey()).isEqualTo("envAccess");
        assertThat(credentials.secretKey()).isEqualTo("envSecret");
    }

    @Test
    void missingCredentialsThrowWithGuidance() {
        assertThatThrownBy(() -> BucketCredentials.load(tempDir.resolve("absent.properties"), NO_ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BucketCredentials.ENV_ACCESS_KEY);
    }

    @Test
    void blankValuesAreTreatedAsMissing() throws IOException {
        final Path file = write("accessKey=   \nsecretKey=fileSecret\n");

        assertThatThrownBy(() -> BucketCredentials.load(file, NO_ENV)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toStringNeverLeaksTheCredentials() {
        final var credentials = new BucketCredentials("topSecretAccess", "topSecretSecret");

        assertThat(credentials.toString()).doesNotContain("topSecretAccess").doesNotContain("topSecretSecret");
    }

    private Path write(final String content) throws IOException {
        return Files.writeString(tempDir.resolve("iss-bucket-credentials.properties"), content);
    }
}
