// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_PRICING_FUNCTIONS;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_THROTTLE_FUNCTIONS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the shipped throttle and fee schedule files to the high-volume sets in code, so no file gives high-volume
 * capacity or pricing to a function the code does not route or price as high volume, or the other way round.
 */
class HighVolumeConfigTest {
    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void upgradeFilesMatchTheHighVolumeSets(final String network) throws Exception {
        // build.gradle.kts declares these files as test inputs
        final var dir = Path.of("..", "configuration", network, "upgrade");
        assertThat(highVolumeBucketFunctions(Files.readString(dir.resolve("throttles.json"))))
                .isEqualTo(HIGH_VOLUME_THROTTLE_FUNCTIONS);
        final var fees = Files.readAllBytes(dir.resolve("simpleFeesSchedules.json"));
        assertThat(highVolumeRateFunctions(
                        FeeSchedule.JSON.parse(Bytes.wrap(fees).toReadableSequentialData())))
                .isEqualTo(HIGH_VOLUME_PRICING_FUNCTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"genesis/throttles.json", "genesis/throttles-dev.json"})
    void genesisThrottlesMatchTheHighVolumeSet(final String resource) throws Exception {
        try (final var in = Objects.requireNonNull(V0490FileSchema.loadResourceInPackage(resource))) {
            assertThat(highVolumeBucketFunctions(new String(in.readAllBytes(), UTF_8)))
                    .isEqualTo(HIGH_VOLUME_THROTTLE_FUNCTIONS);
        }
    }

    @Test
    void genesisFeeScheduleMatchesTheHighVolumeSet() throws Exception {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var genesis = new V0490FileSchema().genesisSimpleFeesSchedules(config);
        assertThat(highVolumeRateFunctions(FeeSchedule.PROTOBUF.parseStrict(genesis)))
                .isEqualTo(HIGH_VOLUME_PRICING_FUNCTIONS);
    }

    private static Set<HederaFunctionality> highVolumeBucketFunctions(final String throttlesJson) throws Exception {
        final var defs =
                ThrottleDefinitions.PROTOBUF.parse(Bytes.wrap(V0490FileSchema.parseThrottleDefinitions(throttlesJson)));
        return defs.throttleBuckets().stream()
                .filter(bucket -> bucket.highVolume())
                .flatMap(bucket -> bucket.throttleGroups().stream())
                .flatMap(group -> group.operations().stream())
                .collect(Collectors.toSet());
    }

    private static Set<HederaFunctionality> highVolumeRateFunctions(final FeeSchedule schedule) {
        return schedule.services().stream()
                .flatMap(service -> service.schedule().stream())
                .filter(ServiceFeeDefinition::hasHighVolumeRates)
                .map(ServiceFeeDefinition::name)
                .collect(Collectors.toSet());
    }
}
