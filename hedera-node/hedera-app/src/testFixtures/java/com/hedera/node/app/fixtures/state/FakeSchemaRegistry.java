// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.MIGRATION;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.RESTART;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.STATE_DEFINITIONS;
import static com.swirlds.state.test.fixtures.merkle.TestSchema.CURRENT_VERSION;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.state.merkle.SchemaApplications;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.spi.FilteredReadableStates;
import com.swirlds.state.spi.FilteredWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FakeSchemaRegistry implements SchemaRegistry<SemanticVersion> {

    private static final Logger logger = LogManager.getLogger(FakeSchemaRegistry.class);

    private final SchemaApplications schemaApplications = new SchemaApplications();

    /**
     * The ordered set of all schemas registered by the service
     */
    private final SortedSet<Schema<SemanticVersion>> schemas = new TreeSet<>();

    @Override
    public SchemaRegistry register(@NonNull final Schema<SemanticVersion> schema) {
        requireNonNull(schema);
        schemas.add(schema);
        return this;
    }

    @SuppressWarnings("rawtypes")
    public void migrate(
            @NonNull final String serviceName,
            @NonNull final FakeState state,
            @NonNull final StartupNetworks startupNetworks) {
        migrate(serviceName, state, CURRENT_VERSION, DEFAULT_CONFIG, DEFAULT_CONFIG, new HashMap<>(), startupNetworks);
    }

    public void migrate(
            @NonNull final String serviceName,
            @NonNull final FakeState state,
            @Nullable final SemanticVersion previousVersion,
            @NonNull final Configuration appConfig,
            @NonNull final Configuration platformConfig,
            @NonNull final Map<String, Object> sharedValues,
            @NonNull final StartupNetworks startupNetworks) {
        requireNonNull(serviceName);
        requireNonNull(state);
        requireNonNull(appConfig);
        requireNonNull(platformConfig);
        requireNonNull(sharedValues);
        requireNonNull(startupNetworks);
        if (schemas.isEmpty()) {
            logger.info("Service {} does not use state", serviceName);
            return;
        }
        // For each schema, create the underlying raw data sources (maps, or lists) and the writable states that
        // will wrap them. Then call the schema's migrate method to populate those states, and commit each of them
        // to the underlying data sources. At that point, we have properly migrated the state.
        final var latestVersion = schemas.getLast().getVersion();
        logger.info(
                "Applying {} schemas for service {} and latest service schema version {}",
                schemas::size,
                () -> serviceName,
                () -> HapiUtils.toString(latestVersion));
        for (final var schema : schemas) {
            final var applications =
                    schemaApplications.computeApplications(previousVersion, latestVersion, schema, appConfig);
            logger.info("Applying {} schema {} ({})", serviceName, schema.getVersion(), applications);
            final var readableStates = state.getReadableStates(serviceName);
            final var previousStates = new FilteredReadableStates(readableStates, readableStates.stateIds());
            final WritableStates writableStates;
            final WritableStates newStates;
            if (applications.contains(STATE_DEFINITIONS)) {
                final var redefinedWritableStates = applyStateDefinitions(serviceName, schema, appConfig, state);
                writableStates = redefinedWritableStates.beforeStates();
                newStates = redefinedWritableStates.afterStates();
            } else {
                newStates = writableStates = state.getWritableStates(serviceName);
            }
            final var context = newMigrationContext(
                    previousVersion,
                    previousStates,
                    newStates,
                    appConfig,
                    platformConfig,
                    sharedValues,
                    startupNetworks);
            if (applications.contains(MIGRATION)) {
                schema.migrate(context);
            }
            if (applications.contains(RESTART)) {
                schema.restart(context);
            }
            if (writableStates instanceof MapWritableStates mws) {
                mws.commit();
            }
            // And finally we can remove any states we need to remove
            schema.statesToRemove().forEach(stateKey -> state.removeServiceState(serviceName, stateKey));
        }
    }

    /**
     * Encapsulates the writable states before and after applying a schema's state definitions.
     *
     * @param beforeStates the writable states before applying the schema's state definitions
     * @param afterStates the writable states after applying the schema's state definitions
     */
    private record RedefinedWritableStates(WritableStates beforeStates, WritableStates afterStates) {}

    private RedefinedWritableStates applyStateDefinitions(
            @NonNull final String serviceName,
            @NonNull final Schema<SemanticVersion> schema,
            @NonNull final Configuration configuration,
            @NonNull final FakeState state) {
        final Map<Integer, Object> stateDataSources = new HashMap<>();
        schema.statesToCreate(configuration).forEach(def -> {
            final var stateId = def.stateId();
            logger.info("  Ensuring {} has state {}", serviceName, stateId);
            if (def.singleton()) {
                stateDataSources.put(def.stateId(), new AtomicReference<>());
            } else if (def.queue()) {
                stateDataSources.put(def.stateId(), new ConcurrentLinkedDeque<>());
            } else {
                stateDataSources.put(def.stateId(), new ConcurrentHashMap<>());
            }
        });
        state.addService(serviceName, stateDataSources);

        final var statesToRemove = schema.statesToRemove();
        final var writableStates = state.getWritableStates(serviceName);
        final var remainingStates = new HashSet<>(writableStates.stateIds());
        remainingStates.removeAll(statesToRemove);
        final var newStates = new FilteredWritableStates(writableStates, remainingStates);
        return new RedefinedWritableStates(writableStates, newStates);
    }

    private MigrationContext newMigrationContext(
            @Nullable final SemanticVersion previousVersion,
            @NonNull final ReadableStates previousStates,
            @NonNull final WritableStates writableStates,
            @NonNull final Configuration appConfig,
            @NonNull final Configuration platformConfig,
            @NonNull final Map<String, Object> sharedValues,
            @NonNull final StartupNetworks startupNetworks) {
        return new MigrationContext<SemanticVersion>() {
            @Override
            public void copyAndReleaseOnDiskState(final int stateId) {
                // No-op
            }

            @Override
            public long roundNumber() {
                return 0;
            }

            @NonNull
            @Override
            public StartupNetworks startupNetworks() {
                return startupNetworks;
            }

            @Override
            public SemanticVersion previousVersion() {
                return previousVersion;
            }

            @NonNull
            @Override
            public ReadableStates previousStates() {
                return previousStates;
            }

            @NonNull
            @Override
            public WritableStates newStates() {
                return writableStates;
            }

            @NonNull
            @Override
            public Configuration appConfig() {
                return appConfig;
            }

            @NonNull
            @Override
            public Configuration platformConfig() {
                return platformConfig;
            }

            @Override
            public Map<String, Object> sharedValues() {
                return sharedValues;
            }

            @Override
            public SemanticVersion getDefaultVersion() {
                return SemanticVersion.DEFAULT;
            }

            @Override
            public Comparator<SemanticVersion> getVersionComparator() {
                return SEMANTIC_VERSION_COMPARATOR;
            }
        };
    }
}
