// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Genesis schema for the CLPR service.
 */
public class V0770ClprSchema extends Schema<SemanticVersion> {

    /** Channels state ID */
    public static final int CHANNELS_STATE_ID = StateKey.KeyOneOfType.CLPRSERVICE_I_CHANNELS.protoOrdinal();

    /** Channels state key */
    public static final String CHANNELS_KEY = "CHANNELS";

    /** Pending commitments state ID */
    public static final int PENDING_COMMITMENTS_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_PENDING_COMMITMENTS.protoOrdinal();

    /** Pending commitments state key */
    public static final String PENDING_COMMITMENTS_KEY = "PENDING_COMMITMENTS";

    /** Ledger configuration state ID */
    public static final int LEDGER_CONFIGURATION_STATE_ID =
            SingletonType.CLPRSERVICE_I_LEDGER_CONFIGURATION.protoOrdinal();

    /** Message queue state ID */
    public static final int MESSAGE_QUEUE_STATE_ID = StateKey.KeyOneOfType.CLPRSERVICE_I_MESSAGE_QUEUE.protoOrdinal();

    /** Message queue state key */
    public static final String MESSAGE_QUEUE_KEY = "MESSAGE_QUEUE";

    /** Connectors state ID */
    public static final int CONNECTORS_STATE_ID = StateKey.KeyOneOfType.CLPRSERVICE_I_CONNECTORS.protoOrdinal();

    /** Connectors state key */
    public static final String CONNECTORS_KEY = "CONNECTORS";

    /** Pending connector commitments state ID */
    public static final int PENDING_CONNECTOR_COMMITMENTS_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_PENDING_CONNECTOR_COMMITMENTS.protoOrdinal();

    /** Pending connector commitments state key */
    public static final String PENDING_CONNECTOR_COMMITMENTS_KEY = "PENDING_CONNECTOR_COMMITMENTS";

    /** Ledger configuration singleton state key */
    public static final String LEDGER_CONFIGURATION_KEY = "LEDGER_CONFIGURATION";

    /** Endpoint manifest singleton state ID */
    public static final int ENDPOINT_MANIFEST_STATE_ID = SingletonType.CLPRSERVICE_I_ENDPOINT_MANIFEST.protoOrdinal();

    /** Endpoint manifest singleton state key */
    public static final String ENDPOINT_MANIFEST_KEY = "ENDPOINT_MANIFEST";

    /** Endpoint manifest construction singleton state ID */
    public static final int ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID =
            SingletonType.CLPRSERVICE_I_ENDPOINT_MANIFEST_CONSTRUCTION.protoOrdinal();

    /** Endpoint manifest construction singleton state key */
    public static final String ENDPOINT_MANIFEST_CONSTRUCTION_KEY = "ENDPOINT_MANIFEST_CONSTRUCTION";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(77).patch(0).build();

    /** EVM address of the Hiero CLPR service contract: 0x000000000000000000000000000000000000016e */
    public static final Bytes CLPR_SERVICE_ADDRESS = CLPR_EVM_ADDRESS_BYTES;

    /** Default max gas per inbound message dispatch (15M, matching the consensus gas budget). */
    public static final long DEFAULT_MAX_GAS_PER_MESSAGE = 15_000_000L;

    /**
     * Constructor for this schema.
     */
    public V0770ClprSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.keyValue(CHANNELS_STATE_ID, CHANNELS_KEY, ProtoBytes.PROTOBUF, ClprChannel.PROTOBUF),
                StateDefinition.keyValue(
                        PENDING_COMMITMENTS_STATE_ID,
                        PENDING_COMMITMENTS_KEY,
                        ProtoBytes.PROTOBUF,
                        ProtoBytes.PROTOBUF),
                StateDefinition.keyValue(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID,
                        PENDING_CONNECTOR_COMMITMENTS_KEY,
                        ProtoBytes.PROTOBUF,
                        ProtoBytes.PROTOBUF),
                StateDefinition.keyValue(
                        MESSAGE_QUEUE_STATE_ID, MESSAGE_QUEUE_KEY, ClprMessageKey.PROTOBUF, ClprMessageValue.PROTOBUF),
                StateDefinition.keyValue(
                        CONNECTORS_STATE_ID, CONNECTORS_KEY, ClprConnectorKey.PROTOBUF, ClprConnector.PROTOBUF),
                StateDefinition.singleton(
                        LEDGER_CONFIGURATION_STATE_ID, LEDGER_CONFIGURATION_KEY, ClprLedgerConfiguration.PROTOBUF),
                StateDefinition.singleton(
                        ENDPOINT_MANIFEST_STATE_ID, ENDPOINT_MANIFEST_KEY, ClprEndpointManifest.PROTOBUF),
                StateDefinition.singleton(
                        ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID,
                        ENDPOINT_MANIFEST_CONSTRUCTION_KEY,
                        ClprEndpointManifestConstruction.PROTOBUF));
    }

    /**
     * Initializes CLPR singleton state during a non-genesis migration.
     *
     * <p>Genesis initialization is deliberately deferred to
     * {@link com.hedera.node.app.service.clpr.impl.ClprServiceImpl#doGenesisSetup} so the empty
     * genesis hash is externalized before these writes. On an upgrade that introduces CLPR, the
     * migration framework captures and streams these writes with the other migration state changes.
     */
    @Override
    public void migrate(@NonNull final MigrationContext<SemanticVersion> ctx) {
        requireNonNull(ctx);
        if (!ctx.isGenesis()) {
            initializeSingletons(ctx.newStates(), ctx.appConfig());
        }
    }

    /**
     * Initializes any missing CLPR singleton values without overwriting existing ledger state.
     *
     * @param writableStates the CLPR writable states
     * @param configuration the active application configuration
     * @return whether at least one singleton was initialized
     */
    public static boolean initializeSingletons(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);

        var initialized = false;
        final var ledgerConfigurationState =
                writableStates.<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID);
        if (ledgerConfigurationState.get() == null) {
            final var clprConfig = configuration.getConfigData(ClprConfig.class);
            final var defaultThrottles = ClprThrottles.newBuilder()
                    .maxSyncBytes(4_194_304L) // 4 MB
                    .maxMessagesPerBundle(1_000)
                    .maxQueueDepth(10_000)
                    .maxMessagePayloadBytes(65_536) // 64 KB per message
                    .maxGasPerMessage(DEFAULT_MAX_GAS_PER_MESSAGE)
                    .build();
            // Use seconds=1 as the initialization sentinel so timestamp is non-zero and
            // distinguishable from the proto default. The first admin update replaces it with
            // the actual consensus time.
            final var initializationTimestamp =
                    Timestamp.newBuilder().seconds(1L).build();
            // The trust anchor fields remain unset during initialization because the Hiero TSS
            // ledger ID is only available after a signed block snapshot has been produced. The
            // first configuration update after that point populates them.
            final var initialConfig = ClprLedgerConfiguration.newBuilder()
                    .chainId(clprConfig.chainId())
                    .protocolVersion(clprConfig.protocolVersion())
                    .serviceAddress(CLPR_SERVICE_ADDRESS)
                    .timestamp(initializationTimestamp)
                    .throttles(defaultThrottles)
                    .build();
            ledgerConfigurationState.put(initialConfig);
            initialized = true;
        }

        final var manifestState = writableStates.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID);
        if (manifestState.get() == null) {
            // Version >= 1 with no endpoints is a valid state per spec §2.4.1. Population from
            // the consensus roster happens outside initialization.
            final var initialManifest = ClprEndpointManifest.newBuilder()
                    .version(1L)
                    .serviceAddress(CLPR_SERVICE_ADDRESS)
                    .build();
            manifestState.put(initialManifest);
            initialized = true;
        }
        return initialized;
    }
}
