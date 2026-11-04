// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads this ledger's CLPR throttles from a {@link ClprLedgerConfiguration} file in protobuf JSON, the way
 * throttle definitions and fee schedules are loaded from disk.
 *
 * <p>Only {@code throttles} may be set in the file. The node fixes every other field ({@code chain_id},
 * {@code protocol_version}, {@code service_address}, {@code timestamp} and the trust anchors), so a file
 * that sets any of them is rejected rather than partly ignored.
 *
 * <p>At genesis the throttles come from {@link ClprConfig#ledgerConfigurationFile()} if present and valid,
 * otherwise from the bundled {@link #BUNDLED_RESOURCE}. At upgrade, a
 * {@link ClprConfig#upgradeLedgerConfigurationFile()} in the post-upgrade system files directory is applied
 * with a synthetic {@code ClprUpdateLedgerConfiguration}.
 */
public final class ClprLedgerConfigurationFile {
    private static final Logger log = LogManager.getLogger(ClprLedgerConfigurationFile.class);

    /**
     * Classpath resource with the default throttles. Deliberately not under {@code genesis/}, which
     * {@code hedera-file-service-impl} already uses; JPMS treats resource directories as packages, and two
     * modules with the same package fail at boot.
     */
    public static final String BUNDLED_RESOURCE = "clpr/clprLedgerConfiguration.json";

    private ClprLedgerConfigurationFile() {}

    /**
     * Parses and validates the throttles in a ledger configuration file.
     *
     * @param in the file contents, a {@link ClprLedgerConfiguration} in protobuf JSON
     * @return the validated throttles
     * @throws IllegalArgumentException if the file is malformed, has unknown fields, sets any field other than
     * {@code throttles}, or its throttles are invalid
     * @throws UncheckedIOException if the file cannot be read
     */
    public static ClprThrottles parse(@NonNull final InputStream in) {
        requireNonNull(in);
        final ClprLedgerConfiguration parsed;
        try {
            parsed = ClprLedgerConfiguration.JSON.parseStrict(new ReadableStreamingData(in));
        } catch (ParseException e) {
            throw new IllegalArgumentException("Malformed CLPR ledger configuration", e);
        }
        if (!parsed.hasThrottles()) {
            throw new IllegalArgumentException("CLPR ledger configuration must set throttles");
        }
        final var throttles = parsed.throttlesOrThrow();
        if (!parsed.equals(
                ClprLedgerConfiguration.newBuilder().throttles(throttles).build())) {
            throw new IllegalArgumentException("CLPR ledger configuration may only set throttles");
        }
        if (!ClprThrottlesValidator.areValidThrottles(throttles)) {
            throw new IllegalArgumentException("Invalid CLPR throttles " + throttles);
        }
        return throttles;
    }

    /**
     * Returns the throttles to seed at genesis: from {@link ClprConfig#ledgerConfigurationFile()} if it exists
     * and is valid, otherwise from {@link #BUNDLED_RESOURCE}. An invalid file is logged and ignored rather than
     * thrown, since seeding runs on the handle thread and an exception there would leave the CLPR singletons
     * missing on this node. {@code max_gas_per_message} is capped at {@code contracts.maxGasPerTransaction}.
     *
     * @param configuration the active configuration
     * @return the throttles to seed
     */
    public static ClprThrottles initialThrottles(@NonNull final Configuration configuration) {
        requireNonNull(configuration);
        final var path = Path.of(configuration.getConfigData(ClprConfig.class).ledgerConfigurationFile());
        ClprThrottles throttles = null;
        if (Files.exists(path)) {
            try (final var in = Files.newInputStream(path)) {
                throttles = parse(in);
                log.info("CLPR ledger configuration loaded from {}", path.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                log.error(
                        "Invalid CLPR ledger configuration at {}, using classpath resource {}",
                        path.toAbsolutePath(),
                        BUNDLED_RESOURCE,
                        e);
            }
        }
        if (throttles == null) {
            throttles = bundledThrottles();
        }
        return capped(throttles, configuration);
    }

    /**
     * Returns the configuration to dispatch in a {@code ClprUpdateLedgerConfiguration} to apply the given
     * throttles from an upgrade file, or empty if they match the existing throttles. Applying unchanged
     * throttles would still set a new timestamp, which queues a config update on every channel.
     * {@code max_gas_per_message} is capped at {@code contracts.maxGasPerTransaction}.
     *
     * @param existing the existing ledger configuration
     * @param throttles the validated throttles from the upgrade file
     * @param configuration the active configuration
     * @return the configuration to dispatch, if the throttles changed
     */
    public static Optional<ClprLedgerConfiguration> upgradeConfiguration(
            @NonNull final ClprLedgerConfiguration existing,
            @NonNull final ClprThrottles throttles,
            @NonNull final Configuration configuration) {
        requireNonNull(existing);
        requireNonNull(throttles);
        requireNonNull(configuration);
        final var cappedThrottles = capped(throttles, configuration);
        if (cappedThrottles.equals(existing.throttles())) {
            return Optional.empty();
        }
        return Optional.of(
                ClprLedgerConfiguration.newBuilder().throttles(cappedThrottles).build());
    }

    private static ClprThrottles bundledThrottles() {
        try (final var in = ClprLedgerConfigurationFile.class.getResourceAsStream("/" + BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + BUNDLED_RESOURCE);
            }
            final var throttles = parse(in);
            log.info("CLPR ledger configuration loaded from classpath resource {}", BUNDLED_RESOURCE);
            return throttles;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ClprThrottles capped(
            @NonNull final ClprThrottles throttles, @NonNull final Configuration configuration) {
        final var maxGasPerTransaction =
                configuration.getConfigData(ContractsConfig.class).maxGasPerTransaction();
        return throttles
                .copyBuilder()
                .maxGasPerMessage(Math.min(throttles.maxGasPerMessage(), maxGasPerTransaction))
                .build();
    }
}
