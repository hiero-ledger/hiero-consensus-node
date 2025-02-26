// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.state.merkle.StateUtils.decomposeLabel;
import static com.swirlds.state.merkle.StateUtils.getVirtualMapKey;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.threading.interrupt.InterruptableConsumer;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.common.utility.RuntimeObjectRecord;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.config.api.Configuration;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.state.State;
import com.swirlds.state.merkle.queue.QueueCodec;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.queue.QueueState;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.merkle.singleton.ValueLeaf;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapMigration;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An implementation of {@link State}.
 *
 * <p>Among {@link MerkleStateRoot}'s child nodes are the various {@link
 * com.swirlds.merkle.map.MerkleMap}'s and {@link com.swirlds.virtualmap.VirtualMap}'s that make up
 * the service's states. Each such child node has a label specified that is computed from the
 * metadata for that state. Since both service names and state keys are restricted to characters
 * that do not include the period, we can use it to separate service name from state key. When we
 * need to find all states for a service, we can do so by iteration and string comparison.
 *
 * <p>NOTE: The implementation of this class must change before we can support state proofs
 * properly. In particular, a wide n-ary number of children is less than ideal, since the hash of
 * each child must be part of the state proof. It would be better to have a binary tree. We should
 * consider nesting service nodes in a MerkleMap, or some other such approach to get a binary tree.
 */
@ConstructableIgnored
public abstract class MerkleStateRoot<T extends MerkleStateRoot<T>> extends PartialNaryMerkleInternal
        implements MerkleInternal, State {

    private static final Logger logger = LogManager.getLogger(MerkleStateRoot.class);

    private static final long CLASS_ID = 0x8e300b0dfdafbb1bL;

    // Migrates from `PlatformState` to State API singleton
    public static final int CURRENT_VERSION = 32;

    /**
     * Used to track the lifespan of this state.
     */
    private final RuntimeObjectRecord registryRecord;

    /**
     * Create a new instance. This constructor must be used for all creations of this class.
     *
     */
    public MerkleStateRoot() {
        this.registryRecord = RuntimeObjectRegistry.createRecord(getClass());
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return CURRENT_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroyNode() {
        registryRecord.release();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public T copy() {
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
        return copyingConstructor();
    }

    protected abstract T copyingConstructor();

    /**
     * Returns the number of the current round
     */
    public abstract long getCurrentRound();

    // Migration things
    // TODO: double check assert usage

    // Config constants (TODO: move to config)
    // Threads which iterate over the given Virtual Map, perform some operation and write into its own output
    // queue/buffer
    private static final int THREAD_COUNT = 1;
    private static final long MEGA_MAP_MAX_KEYS_HINT = 1_000_000_000;
    private static final boolean VALIDATE_MIGRATION_FF = true;

    @Override
    public MerkleNode migrate(@NonNull final Configuration configuration, int version) {
        if (version < 32) {

            // Create Virtual Map

            final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
            final var tableConfig = new MerkleDbTableConfig(
                    (short) 1, DigestType.SHA_384, MEGA_MAP_MAX_KEYS_HINT, merkleDbConfig.hashesRamToDiskThreshold());
            final var virtualMapLabel = "VirtualMap";
            final var dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, configuration);
            final var virtualMap = new VirtualMap(virtualMapLabel, dsBuilder, configuration);

            // Initialize migration metrics

            AtomicLong totalMigratedObjects = new AtomicLong(0);
            AtomicLong totalMigrationTimeMs = new AtomicLong(0);
            AtomicLong totalValidationTimeMs = new AtomicLong(0);

            // Migration

            logger.info(
                    STARTUP.getMarker(),
                    "Migrating all of the states (Singleton, KV and Queue) to the one Virtual Map...");

            migrateKVStates(virtualMap, totalMigratedObjects, totalMigrationTimeMs, totalValidationTimeMs);
            migrateQueueStates(virtualMap, totalMigratedObjects, totalMigrationTimeMs, totalValidationTimeMs);
            migrateSingletonStates(virtualMap, totalMigratedObjects, totalMigrationTimeMs, totalValidationTimeMs);

            // Validate all states migrated to the Virtual Map
            if (VALIDATE_MIGRATION_FF) {
                assert virtualMap.size() == totalMigratedObjects.get();
            }

            return virtualMap;
        }

        return this;
    }

    private void migrateKVStates(
            VirtualMap virtualMap,
            AtomicLong totalMigratedObjects,
            AtomicLong totalMigrationTimeMs,
            AtomicLong totalValidationTimeMs) {
        logger.info(STARTUP.getMarker(), "Migrating KV states to the one Virtual Map...");

        final AtomicLong kvMigrationStartTime = new AtomicLong(0);
        IntStream.range(0, getNumberOfChildren())
                .mapToObj(this::getChild)
                .filter(child -> child instanceof VirtualMap)
                .map(child -> (VirtualMap) child)
                .forEach(virtualMapToMigrate -> {
                    final var virtualMapLabel = virtualMapToMigrate.getLabel();
                    final var labelPair = decomposeLabel(virtualMapToMigrate.getLabel());
                    final var serviceName = labelPair.key();
                    final var stateKey = labelPair.value();
                    final var stateIdBytes = getVirtualMapKey(serviceName, stateKey);

                    // TODO: check possibilities for optimization
                    InterruptableConsumer<Pair<Bytes, Bytes>> handler =
                            pair -> virtualMap.putBytes(stateIdBytes.append(pair.key()), pair.value());

                    try {
                        logger.info(
                                STARTUP.getMarker(),
                                "\nMigrating {} (size: {})...",
                                virtualMapLabel,
                                virtualMapToMigrate.size());
                        long migrationStartTime = System.currentTimeMillis();

                        // TODO: decide on method from VirtualMapMigration
                        VirtualMapMigration.extractVirtualMapData(
                                AdHocThreadManager.getStaticThreadManager(),
                                virtualMapToMigrate,
                                handler,
                                THREAD_COUNT);

                        long migrationTimeMs = System.currentTimeMillis() - migrationStartTime;
                        logger.info(
                                STARTUP.getMarker(),
                                "Migration complete for {} took {} ms",
                                virtualMapLabel,
                                migrationTimeMs);
                        logger.info(STARTUP.getMarker(), "New Virtual Map size: {}", virtualMap.size());
                        kvMigrationStartTime.addAndGet(migrationTimeMs);
                        totalMigrationTimeMs.addAndGet(migrationTimeMs);
                        totalMigratedObjects.addAndGet(virtualMapToMigrate.size());
                    } catch (InterruptedException e) { // TODO: revisit exception handling
                        throw new RuntimeException(e);
                    }

                    if (VALIDATE_MIGRATION_FF) {
                        long validationStartTime = System.currentTimeMillis();
                        logger.info(
                                STARTUP.getMarker(),
                                "Validating the new Virtual Map contains all data from the KV State {}",
                                virtualMapToMigrate.getLabel());

                        validateKVStateMigrated(virtualMap, virtualMapToMigrate);

                        long validationTimeMs = System.currentTimeMillis() - validationStartTime;
                        logger.info(
                                STARTUP.getMarker(),
                                "Validation complete for the KV State {} took {} ms",
                                virtualMapToMigrate.getLabel(),
                                validationTimeMs);
                        totalValidationTimeMs.addAndGet(validationTimeMs);
                    }
                });

        logger.info(STARTUP.getMarker(), "Migration complete for KV states, took {} ms", kvMigrationStartTime.get());
    }

    private static void validateKVStateMigrated(VirtualMap virtualMap, VirtualMap virtualMapToMigrate) {
        MerkleIterator<MerkleNode> merkleNodeMerkleIterator = virtualMapToMigrate.treeIterator();

        while (merkleNodeMerkleIterator.hasNext()) {
            MerkleNode next = merkleNodeMerkleIterator.next();
            if (next instanceof VirtualLeafBytes leafBytes) { // TODO: double check this
                assert virtualMap.containsKey(leafBytes.keyBytes());
            }
        }
    }

    private void migrateQueueStates(
            VirtualMap virtualMap,
            AtomicLong totalMigratedObjects,
            AtomicLong totalMigrationTimeMs,
            AtomicLong totalValidationTimeMs) {
        logger.info(STARTUP.getMarker(), "Migrating Queue states to the one Virtual Map...");

        final AtomicLong queueMigrationStartTime = new AtomicLong(0);
        IntStream.range(0, getNumberOfChildren())
                .mapToObj(this::getChild)
                .filter(child -> child instanceof QueueNode<?>)
                .map(child -> (QueueNode<?>) child)
                .forEach(queueNode -> {
                    final var queueNodeLabel = queueNode.getLabel();
                    final var labelPair = decomposeLabel(queueNodeLabel);
                    final var serviceName = labelPair.key();
                    final var stateKey = labelPair.value();
                    final FCQueue<ValueLeaf> originalStore = queueNode.getRight();

                    logger.info(
                            STARTUP.getMarker(), "\nMigrating {} (size: {})...", queueNodeLabel, originalStore.size());
                    long migrationStartTime = System.currentTimeMillis();

                    // Migrate data
                    final long head = 1;
                    long tail = 1;

                    for (ValueLeaf leaf : originalStore) {
                        final var codec = leaf.getCodec();
                        final var value = Objects.requireNonNull(leaf.getValue(), "Null value is not expected here");
                        virtualMap.put(getVirtualMapKey(serviceName, stateKey, tail++), value, codec);
                    }

                    final var queueState = new QueueState(head, tail);
                    virtualMap.put(getVirtualMapKey(serviceName, stateKey), queueState, QueueCodec.INSTANCE);

                    long migrationTimeMs = System.currentTimeMillis() - migrationStartTime;
                    logger.info(
                            STARTUP.getMarker(),
                            "Migration complete for {} took {} ms",
                            queueNodeLabel,
                            migrationTimeMs);
                    logger.info(STARTUP.getMarker(), "New Virtual Map size: {}", virtualMap.size());
                    queueMigrationStartTime.addAndGet(migrationTimeMs);
                    totalMigrationTimeMs.addAndGet(migrationTimeMs);
                    totalMigratedObjects.addAndGet(originalStore.size());

                    if (VALIDATE_MIGRATION_FF) {
                        long validationStartTime = System.currentTimeMillis();
                        logger.info(
                                STARTUP.getMarker(),
                                "Validating the new Virtual Map contains all data from the Queue State {}",
                                queueNodeLabel);

                        validateQueueStateMigrated(virtualMap, queueNodeLabel, serviceName, head, tail);

                        long validationTimeMs = System.currentTimeMillis() - validationStartTime;
                        logger.info(
                                STARTUP.getMarker(),
                                "Validation complete for the Queue State {} took {} ms",
                                queueNodeLabel,
                                validationTimeMs);
                        totalValidationTimeMs.addAndGet(validationTimeMs);
                    }
                });

        logger.info(
                STARTUP.getMarker(), "Migration complete for Queue states, took {} ms", queueMigrationStartTime.get());
    }

    private static void validateQueueStateMigrated(
            VirtualMap virtualMap, String serviceName, String stateKey, long head, long tail) {
        // Validate Queue State object
        assert virtualMap.containsKey(getVirtualMapKey(serviceName, stateKey));

        // Validate Queue State values
        for (long i = head; i < tail; i++) {
            assert virtualMap.containsKey(getVirtualMapKey(serviceName, stateKey, i));
        }
    }

    private void migrateSingletonStates(
            VirtualMap virtualMap,
            AtomicLong totalMigratedObjects,
            AtomicLong totalMigrationTimeMs,
            AtomicLong totalValidationTimeMs) {
        logger.info(STARTUP.getMarker(), "Migrating Singleton states to the one Virtual Map...");

        final AtomicLong singletonMigrationTimeMs = new AtomicLong(0);
        IntStream.range(0, getNumberOfChildren())
                .mapToObj(this::getChild)
                .filter(child -> child instanceof SingletonNode<?>)
                .map(child -> (SingletonNode<?>) child)
                .forEach(singletonNode -> {
                    final StringLeaf originalLabeled = singletonNode.getLeft();
                    final String singletonStateLabel = originalLabeled.getLabel();
                    final var labelPair = decomposeLabel(singletonStateLabel);
                    final var serviceName = labelPair.key();
                    final var stateKey = labelPair.value();
                    final ValueLeaf originalStore = singletonNode.getRight();

                    logger.info(STARTUP.getMarker(), "\nMigrating {}...", singletonStateLabel);
                    long migrationStartTime = System.currentTimeMillis();

                    final var codec = originalStore.getCodec();
                    final var value =
                            Objects.requireNonNull(originalStore.getValue(), "Null value is not expected here");
                    virtualMap.put(getVirtualMapKey(serviceName, stateKey), value, codec);

                    long migrationTimeMs = System.currentTimeMillis() - migrationStartTime;
                    logger.info(
                            STARTUP.getMarker(),
                            "Migration complete for {} took {} ms",
                            singletonStateLabel,
                            migrationTimeMs);
                    logger.info(STARTUP.getMarker(), "New Virtual Map size: {}", virtualMap.size());
                    singletonMigrationTimeMs.addAndGet(migrationTimeMs);
                    totalMigrationTimeMs.addAndGet(migrationTimeMs);
                    totalMigratedObjects.addAndGet(1);

                    if (VALIDATE_MIGRATION_FF) {
                        long validationStartTime = System.currentTimeMillis();
                        logger.info(
                                STARTUP.getMarker(),
                                "Validating the new Virtual Map contains all data from the Singleton State {}",
                                singletonStateLabel);

                        validateSingletonStateMigrated(virtualMap, serviceName, stateKey);

                        final long validationTimeMs = System.currentTimeMillis() - validationStartTime;
                        logger.info(
                                STARTUP.getMarker(),
                                "Validation complete for the Singleton State {} took {} ms",
                                singletonStateLabel,
                                validationTimeMs);
                        totalValidationTimeMs.addAndGet(validationTimeMs);
                    }
                });

        logger.info(
                STARTUP.getMarker(), "Migration complete for Singleton states, took {} ms", singletonMigrationTimeMs);
    }

    private static void validateSingletonStateMigrated(VirtualMap virtualMap, String serviceName, String stateKey) {
        assert virtualMap.containsKey(getVirtualMapKey(serviceName, stateKey));
    }
}
