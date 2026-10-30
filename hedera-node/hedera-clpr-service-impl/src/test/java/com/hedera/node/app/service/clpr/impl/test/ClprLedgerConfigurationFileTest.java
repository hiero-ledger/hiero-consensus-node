// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprLedgerConfigurationFile;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprLedgerConfigurationFileTest {
    /** The throttles in the bundled resource, which match the throttles seeded before the file existed. */
    private static final ClprThrottles BUNDLED_THROTTLES = ClprThrottles.newBuilder()
            .maxMessagesPerBundle(1_000)
            .maxMessagePayloadBytes(65_536)
            .maxGasPerMessage(15_000_000L)
            .maxQueueDepth(10_000)
            .maxSyncBytes(4_194_304L)
            .build();

    private static final ClprThrottles CUSTOM_THROTTLES = BUNDLED_THROTTLES
            .copyBuilder()
            .maxMessagesPerBundle(500)
            .maxQueueDepth(20_000)
            .build();

    @TempDir
    private Path tempDir;

    @Test
    void parsesThrottlesInDocumentedFormat() {
        final var json = """
                {
                  "throttles": {
                    "maxMessagesPerBundle": 500,
                    "maxMessagePayloadBytes": 65536,
                    "maxGasPerMessage": 15000000,
                    "maxQueueDepth": 20000,
                    "maxSyncBytes": 4194304
                  }
                }
                """;

        assertThat(ClprLedgerConfigurationFile.parse(streamOf(json))).isEqualTo(CUSTOM_THROTTLES);
    }

    @Test
    void parsesThrottlesWrittenByPbj() {
        assertThat(ClprLedgerConfigurationFile.parse(streamOf(jsonOf(CUSTOM_THROTTLES))))
                .isEqualTo(CUSTOM_THROTTLES);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{\"throttles\": {\"maxMessagesPerBundle\": 500, \"unknownField\": 1}}",
                "{\"unknownField\": 1}",
                "{}",
            })
    void rejectsMalformedOrMissingThrottles(final String json) {
        assertThatThrownBy(() -> ClprLedgerConfigurationFile.parse(streamOf(json)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonThrottleFields() {
        final var withChainId = ClprLedgerConfiguration.newBuilder()
                .chainId("eip155:1")
                .throttles(CUSTOM_THROTTLES)
                .build();
        final var withTimestamp = ClprLedgerConfiguration.newBuilder()
                .timestamp(Timestamp.newBuilder().seconds(1L).build())
                .throttles(CUSTOM_THROTTLES)
                .build();
        final var withProtocolVersion = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(2)
                .throttles(CUSTOM_THROTTLES)
                .build();

        for (final var config : new ClprLedgerConfiguration[] {withChainId, withTimestamp, withProtocolVersion}) {
            assertThatThrownBy(() ->
                            ClprLedgerConfigurationFile.parse(streamOf(ClprLedgerConfiguration.JSON.toJSON(config))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("may only set throttles");
        }
    }

    @Test
    void rejectsInvalidThrottles() {
        final var zeroQueueDepth =
                CUSTOM_THROTTLES.copyBuilder().maxQueueDepth(0).build();

        assertThatThrownBy(() -> ClprLedgerConfigurationFile.parse(streamOf(jsonOf(zeroQueueDepth))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CLPR throttles");
    }

    @Test
    void initialThrottlesUseBundledResourceWithoutFile() {
        final var config = configWithFile(tempDir.resolve("missing.json"), 15_000_000L);

        assertThat(ClprLedgerConfigurationFile.initialThrottles(config)).isEqualTo(BUNDLED_THROTTLES);
    }

    @Test
    void initialThrottlesUseValidFile() throws IOException {
        final var file = writeFile(jsonOf(CUSTOM_THROTTLES));

        assertThat(ClprLedgerConfigurationFile.initialThrottles(configWithFile(file, 15_000_000L)))
                .isEqualTo(CUSTOM_THROTTLES);
    }

    @Test
    void initialThrottlesCapGasAtMaxGasPerTransaction() throws IOException {
        final var file = writeFile(jsonOf(CUSTOM_THROTTLES));

        assertThat(ClprLedgerConfigurationFile.initialThrottles(configWithFile(file, 1_000_000L)))
                .isEqualTo(CUSTOM_THROTTLES
                        .copyBuilder()
                        .maxGasPerMessage(1_000_000L)
                        .build());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Malformed JSON
                "not json",
                // Unknown field
                "{\"throttles\": {\"maxMessagesPerBundle\": 500, \"unknownField\": 1}}",
                // Non-throttle field
                "{\"chainId\": \"eip155:1\", \"throttles\": {\"maxMessagesPerBundle\": 500,"
                        + " \"maxMessagePayloadBytes\": 65536, \"maxGasPerMessage\": 15000000,"
                        + " \"maxQueueDepth\": 20000, \"maxSyncBytes\": 4194304}}",
                // Throttles that fail validation (max_sync_bytes leaves no room for the proof overhead)
                "{\"throttles\": {\"maxMessagesPerBundle\": 500, \"maxMessagePayloadBytes\": 65536,"
                        + " \"maxGasPerMessage\": 15000000, \"maxQueueDepth\": 20000, \"maxSyncBytes\": 65536}}",
            })
    void initialThrottlesFallBackToBundledResourceForInvalidFile(final String json) throws IOException {
        final var file = writeFile(json);

        assertThat(ClprLedgerConfigurationFile.initialThrottles(configWithFile(file, 15_000_000L)))
                .isEqualTo(BUNDLED_THROTTLES);
    }

    @Test
    void upgradeConfigurationIsEmptyForUnchangedThrottles() {
        final var existing = existingWith(BUNDLED_THROTTLES);

        assertThat(ClprLedgerConfigurationFile.upgradeConfiguration(
                        existing, BUNDLED_THROTTLES, configWithFile(tempDir, 15_000_000L)))
                .isEmpty();
    }

    @Test
    void upgradeConfigurationIsEmptyWhenOnlyCappedGasDiffers() {
        final var existing = existingWith(
                BUNDLED_THROTTLES.copyBuilder().maxGasPerMessage(1_000_000L).build());

        assertThat(ClprLedgerConfigurationFile.upgradeConfiguration(
                        existing, BUNDLED_THROTTLES, configWithFile(tempDir, 1_000_000L)))
                .isEmpty();
    }

    @Test
    void upgradeConfigurationHasOnlyChangedThrottles() {
        final var existing = existingWith(BUNDLED_THROTTLES);

        assertThat(ClprLedgerConfigurationFile.upgradeConfiguration(
                        existing, CUSTOM_THROTTLES, configWithFile(tempDir, 15_000_000L)))
                .contains(ClprLedgerConfiguration.newBuilder()
                        .throttles(CUSTOM_THROTTLES)
                        .build());
    }

    private static ClprLedgerConfiguration existingWith(final ClprThrottles throttles) {
        return ClprLedgerConfiguration.newBuilder()
                .chainId("eip155:298")
                .protocolVersion(1)
                .timestamp(Timestamp.newBuilder().seconds(1L).build())
                .throttles(throttles)
                .build();
    }

    private static Configuration configWithFile(final Path file, final long maxGasPerTransaction) {
        return HederaTestConfigBuilder.create()
                .withValue("clpr.ledgerConfigurationFile", file.toString())
                .withValue("contracts.maxGasPerTransaction", maxGasPerTransaction)
                .getOrCreateConfig();
    }

    private Path writeFile(final String json) throws IOException {
        final var file = tempDir.resolve("clprLedgerConfiguration.json");
        Files.writeString(file, json);
        return file;
    }

    private static String jsonOf(final ClprThrottles throttles) {
        return ClprLedgerConfiguration.JSON.toJSON(
                ClprLedgerConfiguration.newBuilder().throttles(throttles).build());
    }

    private static ByteArrayInputStream streamOf(final String json) {
        return new ByteArrayInputStream(json.getBytes(UTF_8));
    }
}
