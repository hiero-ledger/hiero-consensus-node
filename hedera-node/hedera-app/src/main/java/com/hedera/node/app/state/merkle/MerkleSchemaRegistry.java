// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.node.app.state.merkle.SchemaApplicationType.MIGRATION;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.RESTART;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.STATE_DEFINITIONS;
import static com.hedera.node.app.state.merkle.VersionUtils.alreadyIncludesStateDefs;
import static com.hedera.node.app.state.merkle.VersionUtils.isSoOrdered;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.services.MigrationContextImpl;
import com.hedera.node.app.services.MigrationStateChanges;
import com.hedera.node.app.services.StartupNetworks;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState.MerkleWritableStates;
import com.swirlds.state.spi.FilteredReadableStates;
import com.swirlds.state.spi.FilteredWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An implementation of {@link SchemaRegistry}.
 *
 * <p>When the Hedera application starts, it creates an instance of {@link MerkleSchemaRegistry} for
 * each {@link Service}, and passes it to the service as part of construction. The {@link Service}
 * then registers each and every {@link Schema} that it has. Each {@link Schema} is associated with
 * a {@link SemanticVersion}.
 *
 * <p>The Hedera application then calls {@code com.hedera.node.app.Hedera#onMigrate(MerkleNodeState, InitTrigger, Metrics)} on each {@link MerkleSchemaRegistry} instance, supplying it the
 * application version number and the newly created (or deserialized) but not yet hashed copy of the {@link
 * MerkleNodeState}. The registry determines which {@link Schema}s to apply, possibly taking multiple migration steps,
 * to transition the merkle tree from its current version to the final version.
 */
public class MerkleSchemaRegistry implements SchemaRegistry<SemanticVersion> {
    private static final Logger logger = LogManager.getLogger(MerkleSchemaRegistry.class);

    /**
     * The name of the service using this registry.
     */
    private final String serviceName;
    /**
     * The ordered set of all schemas registered by the service
     */
    private final SortedSet<Schema<SemanticVersion>> schemas = new TreeSet<>();
    /**
     * The analysis to use when determining how to apply a schema.
     */
    private final SchemaApplications schemaApplications;

    /**
     * Create a new instance with the default {@link SchemaApplications}.
     *
     * @param serviceName The name of the service using this registry.
     * @param schemaApplications the analysis to use when determining how to apply a schema
     */
    public MerkleSchemaRegistry(
            @NonNull final String serviceName, @NonNull final SchemaApplications schemaApplications) {
        this.serviceName = StateMetadata.validateServiceName(requireNonNull(serviceName));
        this.schemaApplications = requireNonNull(schemaApplications);
    }

    /**
     * {@inheritDoc}
     *
     * @return
     */
    @Override
    public SchemaRegistry register(@NonNull Schema<SemanticVersion> schema) {
        schemas.remove(schema);
        schemas.add(requireNonNull(schema));
        logger.debug(
                "Registering schema {} for service {} ",
                () -> HapiUtils.toString(schema.getVersion()),
                () -> serviceName);

        return this;
    }

    /**
     * Encapsulates the writable states before and after applying a schema's state definitions.
     *
     * @param beforeStates the writable states before applying the schema's state definitions
     * @param afterStates the writable states after applying the schema's state definitions
     */
    private record RedefinedWritableStates(WritableStates beforeStates, WritableStates afterStates) {}

    /**
     * Called by the application after saved states have been loaded to perform the migration. Given
     * the supplied versions, applies all necessary migrations for every {@link Schema} newer than
     * {@code previousVersion} and no newer than {@code currentVersion}.
     *
     * <p>If the {@code previousVersion} and {@code currentVersion} are the same, then we do not need
     * to migrate, but instead we just call {@link Schema#restart(MigrationContext)} to allow the schema
     * to perform any necessary logic on restart. Most services have nothing to do, but some may need
     * to read files from disk, and could potentially change their state as a result.
     *
     * @param stateRoot the state for this registry to use.
     * @param previousVersion The version of state loaded from disk. Possibly null.
     * @param currentVersion The current version. Never null. Must be newer than {@code
     * previousVersion}.
     * @param appConfig The system configuration to use at the time of migration
     * @param platformConfig The platform configuration to use for subsequent object initializations
     * @param sharedValues A map of shared values for cross-service migration patterns
     * @param migrationStateChanges Tracker for state changes during migration
     * @param startupNetworks The startup networks to use for the migrations
     * @param platformStateFacade The platform state facade to use for the migrations
     * @throws IllegalArgumentException if the {@code currentVersion} is not at least the
     * {@code previousVersion} or if the {@code state} is not an instance of {@link MerkleNodeState}
     */
    // too many parameters, commented out code
    @SuppressWarnings({"java:S107", "java:S125"})
    public void migrate(
            @NonNull final MerkleNodeState stateRoot,
            @Nullable final SemanticVersion previousVersion,
            @NonNull final SemanticVersion currentVersion,
            @NonNull final Configuration appConfig,
            @NonNull final Configuration platformConfig,
            @NonNull final Map<String, Object> sharedValues,
            @NonNull final MigrationStateChanges migrationStateChanges,
            @NonNull final StartupNetworks startupNetworks,
            @NonNull final PlatformStateFacade platformStateFacade) {
        requireNonNull(stateRoot);
        requireNonNull(currentVersion);
        requireNonNull(appConfig);
        requireNonNull(platformConfig);
        requireNonNull(sharedValues);
        requireNonNull(migrationStateChanges);
        if (isSoOrdered(currentVersion, previousVersion)) {
            throw new IllegalArgumentException("The currentVersion must be at least the previousVersion");
        }
        final long roundNumber = platformStateFacade.roundOf(stateRoot);
        if (schemas.isEmpty()) {
            logger.info("Service {} does not use state", serviceName);
            return;
        }
        final var latestVersion = schemas.getLast().getVersion();
        logger.info(
                "Applying {} schemas for service {} with state version {}, "
                        + "software version {}, and latest service schema version {}",
                schemas::size,
                () -> serviceName,
                () -> HapiUtils.toString(previousVersion),
                () -> HapiUtils.toString(currentVersion),
                () -> HapiUtils.toString(latestVersion));
        for (final var schema : schemas) {
            final var applications =
                    schemaApplications.computeApplications(previousVersion, latestVersion, schema, appConfig);
            logger.info("Applying {} schema {} ({})", serviceName, schema.getVersion(), applications);
            // Now we can migrate the schema and then commit all the changes
            // We just have one merkle tree -- the just-loaded working tree -- to work from.
            // We get a ReadableStates for everything in the current tree, but then wrap
            // it with a FilteredReadableStates that is locked into exactly the set of states
            // available at this moment in time. This is done to make sure that even after we
            // add new states into the tree, it doesn't increase the number of states that can
            // be seen by the schema migration code
            final var readableStates = stateRoot.getReadableStates(serviceName);
            final var previousStates = new FilteredReadableStates(readableStates, readableStates.stateIds());
            // Similarly, we distinguish between the writable states before and after
            // applying the schema's state definitions. This is done to ensure that we
            // commit all state changes made by applying this schema's state definitions;
            // but also prevent its migrate() and restart() hooks from accidentally using
            // states that were actually removed by this schema
            final WritableStates writableStates;
            final WritableStates newStates;
            if (applications.contains(STATE_DEFINITIONS)) {
                final var schemasAlreadyInState = schemas.tailSet(schema).stream()
                        .filter(s -> s != schema
                                && previousVersion != null
                                && alreadyIncludesStateDefs(previousVersion, s.getVersion()))
                        .toList();
                final var redefinedWritableStates =
                        applyStateDefinitions(schema, schemasAlreadyInState, appConfig, stateRoot);
                writableStates = redefinedWritableStates.beforeStates();
                newStates = redefinedWritableStates.afterStates();
            } else {
                newStates = writableStates = stateRoot.getWritableStates(serviceName);
            }

            final var migrationContext = new MigrationContextImpl(
                    previousStates,
                    newStates,
                    appConfig,
                    platformConfig,
                    previousVersion,
                    roundNumber,
                    sharedValues,
                    startupNetworks);
            if (applications.contains(MIGRATION)) {
                schema.migrate(migrationContext);
            }
            if (applications.contains(RESTART)) {
                schema.restart(migrationContext);
            }
            // Now commit all the service-specific changes made during this service's update or migration
            if (writableStates instanceof MerkleWritableStates mws) {
                mws.commit();
                migrationStateChanges.trackCommit();
            }
            // And finally we can remove any states we need to remove
            schema.statesToRemove().forEach(stateId -> stateRoot.removeServiceState(serviceName, stateId));
        }
    }

    private RedefinedWritableStates applyStateDefinitions(
            @NonNull final Schema<SemanticVersion> schema,
            @NonNull final List<Schema<SemanticVersion>> schemasAlreadyInState,
            @NonNull final Configuration nodeConfiguration,
            @NonNull final MerkleNodeState stateRoot) {
        // Create the new states (based on the schema) which, thanks to the above, does not
        // expand the set of states that the migration code will see
        schema.statesToCreate(nodeConfiguration).stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var stateKey = def.stateKey();
                    if (schemasAlreadyInState.stream()
                            .anyMatch(s -> s.statesToRemove().contains(stateKey))) {
                        logger.info("  Skipping {} as it is removed by a later schema", stateKey);
                        return;
                    }
                    logger.info("  Ensuring {} has state {}", serviceName, stateKey);
                    final var md = new StateMetadata<>(serviceName, def);
                    stateRoot.initializeState(md);
                });

        // Create the "before" and "after" writable states (we won't commit anything
        // from these states until we have completed migration for this schema)
        final var statesToRemove = schema.statesToRemove();
        final var writableStates = stateRoot.getWritableStates(serviceName);
        final var remainingStates = new HashSet<>(writableStates.stateIds());
        remainingStates.removeAll(statesToRemove);
        logger.info("  Removing states {} from service {}", statesToRemove, serviceName);
        final var newStates = new FilteredWritableStates(writableStates, remainingStates);
        return new RedefinedWritableStates(writableStates, newStates);
    }
}
