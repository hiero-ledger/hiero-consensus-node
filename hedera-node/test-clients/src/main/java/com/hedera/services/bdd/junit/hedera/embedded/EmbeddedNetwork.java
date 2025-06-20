// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded;

import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.PENDING;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.REACHED;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.classicMetadataFor;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.configTxtForLocal;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;
import static com.hedera.services.bdd.spec.TargetNetworkType.EMBEDDED_NETWORK;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.services.bdd.junit.hedera.AbstractNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class EmbeddedNetwork extends AbstractNetwork {
    private static final Logger log = LogManager.getLogger(EmbeddedNetwork.class);

    private static final String FAKE_HOST = "127.0.0.1";
    public static final String CONCURRENT_WORKING_DIR = "concurrent";
    private static final String CONCURRENT_NAME = CONCURRENT_WORKING_DIR.toUpperCase();
    public static final String REPEATABLE_WORKING_DIR = "repeatable";
    private static final String REPEATABLE_NAME = REPEATABLE_WORKING_DIR.toUpperCase();

    private final String configTxt;
    private final EmbeddedMode mode;
    private final EmbeddedNode embeddedNode;
    private final long shard;
    private final long realm;

    @Nullable
    private EmbeddedHedera embeddedHedera;

    /**
     * Creates an embedded "network" with default name and scope to be shared by many tests.
     *
     * @return the embedded network
     */
    public static HederaNetwork newSharedNetwork(@NonNull final EmbeddedMode mode) {
        requireNonNull(mode);
        return switch (mode) {
            case CONCURRENT -> new EmbeddedNetwork(CONCURRENT_NAME, CONCURRENT_WORKING_DIR, mode);
            case REPEATABLE -> new EmbeddedNetwork(REPEATABLE_NAME, REPEATABLE_WORKING_DIR, mode);
        };
    }

    public EmbeddedNetwork(
            @NonNull final String name, @NonNull final String workingDir, @NonNull final EmbeddedMode mode) {
        super(
                name,
                IntStream.range(0, CLASSIC_HAPI_TEST_NETWORK_SIZE)
                        .<HederaNode>mapToObj(nodeId -> new EmbeddedNode(
                                // All non-embedded node working directories are mapped to the embedded node0
                                classicMetadataFor(
                                        nodeId,
                                        name,
                                        FAKE_HOST,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        workingDirFor(0, workingDir),
                                        getConfigShard(),
                                        getConfigRealm())))
                        .toList());
        this.shard = getConfigShard();
        this.realm = getConfigRealm();
        this.mode = requireNonNull(mode);
        this.embeddedNode = (EmbeddedNode) nodes().getFirst();
        // Even though we are only embedding node0, we generate an address book
        // for a "classic" HapiTest network with 4 nodes so that tests can still
        // submit transactions with different creator accounts; c.f. EmbeddedHedera,
        // which skips ingest and directly submits transactions for other nodes
        this.configTxt = configTxtForLocal(name(), nodes(), 1, 1);
    }

    /**
     * Starts the embedded Hedera network from a saved state customized by the given specs.
     *
     * @param state the state to customize
     */
    public void restart(@NonNull final FakeState state, @NonNull final Map<String, String> bootstrapOverrides) {
        requireNonNull(state);
        startVia(hedera -> hedera.restart(state), bootstrapOverrides);
    }

    @Override
    public void start() {
        startWith(emptyMap());
    }

    @Override
    public void startWith(@NonNull final Map<String, String> bootstrapOverrides) {
        requireNonNull(bootstrapOverrides);
        startVia(EmbeddedHedera::start, bootstrapOverrides);
    }

    @Override
    public void terminate() {
        if (embeddedHedera != null) {
            embeddedHedera.stop();
            if (mode == REPEATABLE) {
                final var runningHashes = embeddedHedera
                        .state()
                        .getReadableStates("BlockRecordService")
                        .<RunningHashes>getSingleton("RUNNING_HASHES")
                        .get();
                if (runningHashes != null) {
                    log.info(
                            "Final record running hash - {}",
                            runningHashes.runningHash().toHex());
                }
            }
        }
    }

    @Override
    public void awaitReady(@NonNull Duration timeout) {
        if (embeddedHedera == null) {
            throw new IllegalStateException("EmbeddedNetwork is meant for single-threaded startup, please start it");
        }
        if (!embeddedHedera.hedera().systemEntitiesCreated()) {
            conditionFuture(() -> embeddedHedera.hedera().systemEntitiesCreated() ? REACHED : PENDING, () -> 1)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join();
        }
    }

    @NonNull
    @Override
    public Response send(
            @NonNull final Query query,
            @NonNull final HederaFunctionality functionality,
            @NonNull final AccountID nodeAccountId,
            final boolean asNodeOperator) {
        return requireNonNull(embeddedHedera).send(query, nodeAccountId, asNodeOperator);
    }

    @Override
    public TransactionResponse submit(
            @NonNull Transaction transaction,
            @NonNull HederaFunctionality functionality,
            @NonNull SystemFunctionalityTarget target,
            @NonNull AccountID nodeAccountId) {
        return requireNonNull(embeddedHedera).submit(transaction, nodeAccountId);
    }

    @Override
    public TargetNetworkType type() {
        return EMBEDDED_NETWORK;
    }

    public @NonNull EmbeddedHedera embeddedHederaOrThrow() {
        return requireNonNull(embeddedHedera);
    }

    /**
     * Returns whether the embedded network is in repeatable mode.
     */
    public boolean inRepeatableMode() {
        return mode == REPEATABLE;
    }

    /**
     * Returns the embedded mode of the network.
     */
    public EmbeddedMode mode() {
        return mode;
    }

    @Override
    protected HapiPropertySource networkOverrides() {
        return WorkingDirUtils.hapiTestStartupProperties();
    }

    private void startVia(
            @NonNull final Consumer<EmbeddedHedera> start, @NonNull final Map<String, String> bootstrapOverrides) {
        // Initialize the working directory
        embeddedNode.initWorkingDir(configTxt);
        if (!bootstrapOverrides.isEmpty()) {
            updateBootstrapProperties(embeddedNode.getExternalPath(APPLICATION_PROPERTIES), bootstrapOverrides);
        }
        embeddedNode.start();
        // Start the embedded Hedera "network"
        embeddedHedera = switch (mode) {
            case REPEATABLE -> new RepeatableEmbeddedHedera(embeddedNode);
            case CONCURRENT -> new ConcurrentEmbeddedHedera(embeddedNode);
        };
        start.accept(embeddedHedera);
    }

    @Override
    public long shard() {
        return shard;
    }

    @Override
    public long realm() {
        return realm;
    }
}
