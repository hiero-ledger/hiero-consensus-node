// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_PRICING_FUNCTIONS;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_THROTTLE_FUNCTIONS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.ThrottleBucket;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
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
    private static final String TOTAL_BUCKET = "HVTotalThrottles";
    // CryptoTransfer takes its implicit creations from the CryptoCreate buckets, so it has no bucket of its own
    private static final Set<HederaFunctionality> BUCKETED_FUNCTIONS = HIGH_VOLUME_THROTTLE_FUNCTIONS.stream()
            .filter(function -> function != CRYPTO_TRANSFER)
            .collect(Collectors.toUnmodifiableSet());

    @ParameterizedTest
    @ValueSource(strings = {"mainnet", "testnet", "previewnet"})
    void upgradeFilesMatchTheHighVolumeSets(final String network) throws Exception {
        // build.gradle.kts declares these files as test inputs
        final var dir = Path.of("..", "configuration", network, "upgrade");
        assertBucketsMatchTheHighVolumeSet(Files.readString(dir.resolve("throttles.json")));
        final var fees = Files.readAllBytes(dir.resolve("simpleFeesSchedules.json"));
        assertThat(highVolumeRateFunctions(
                        FeeSchedule.JSON.parse(Bytes.wrap(fees).toReadableSequentialData())))
                .isEqualTo(HIGH_VOLUME_PRICING_FUNCTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"genesis/throttles.json", "genesis/throttles-dev.json"})
    void genesisThrottlesMatchTheHighVolumeSet(final String resource) throws Exception {
        try (final var in = Objects.requireNonNull(V0490FileSchema.loadResourceInPackage(resource))) {
            assertBucketsMatchTheHighVolumeSet(new String(in.readAllBytes(), UTF_8));
        }
    }

    @Test
    void genesisFeeScheduleMatchesTheHighVolumeSet() throws Exception {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var genesis = new V0490FileSchema().genesisSimpleFeesSchedules(config);
        assertThat(highVolumeRateFunctions(FeeSchedule.PROTOBUF.parseStrict(genesis)))
                .isEqualTo(HIGH_VOLUME_PRICING_FUNCTIONS);
    }

    private static void assertBucketsMatchTheHighVolumeSet(final String throttlesJson) throws Exception {
        final var buckets = ThrottleDefinitions.PROTOBUF
                .parse(Bytes.wrap(V0490FileSchema.parseThrottleDefinitions(throttlesJson)))
                .throttleBuckets()
                .stream()
                .filter(ThrottleBucket::highVolume)
                .toList();
        final Predicate<ThrottleBucket> isTotal = bucket -> TOTAL_BUCKET.equals(bucket.name());
        assertThat(operationsOf(buckets, isTotal))
                .as("operations in %s", TOTAL_BUCKET)
                .containsExactlyInAnyOrderElementsOf(BUCKETED_FUNCTIONS);
        assertThat(buckets)
                .filteredOn(isTotal.negate())
                .allSatisfy(bucket -> assertThat(operationsOf(List.of(bucket), b -> true))
                        .as("operations in %s", bucket.name())
                        .hasSize(1));
        assertThat(operationsOf(buckets, isTotal.negate()))
                .as("operations in the dedicated high-volume buckets")
                .containsExactlyInAnyOrderElementsOf(BUCKETED_FUNCTIONS);
    }

    private static List<HederaFunctionality> operationsOf(
            final List<ThrottleBucket> buckets, final Predicate<ThrottleBucket> filter) {
        return buckets.stream()
                .filter(filter)
                .flatMap(bucket -> bucket.throttleGroups().stream())
                .flatMap(group -> group.operations().stream())
                .toList();
    }

    private static Set<HederaFunctionality> highVolumeRateFunctions(final FeeSchedule schedule) {
        return schedule.services().stream()
                .flatMap(service -> service.schedule().stream())
                .filter(ServiceFeeDefinition::hasHighVolumeRates)
                .map(ServiceFeeDefinition::name)
                .collect(Collectors.toSet());
    }
}
