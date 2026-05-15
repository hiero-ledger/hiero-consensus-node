// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.PROOF_KEY_SETS_STATE_ID;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.history.impl.ReadableHistoryStoreImpl;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.tss.TssKeyFiles;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dev-only helpers for exporting and bootstrapping TSS metadata in startup network JSON files.
 */
public final class TssStartupNetworks {
    private static final Logger log = LogManager.getLogger(TssStartupNetworks.class);

    private TssStartupNetworks() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns whether the given network carries any TSS bootstrap data.
     */
    public static boolean hasTssMetadata(@NonNull final Network network) {
        requireNonNull(network);
        return network.hasTssMetadata() || !network.nodeTssMetadata().isEmpty();
    }

    /**
     * Enriches the given base network with TSS metadata from state and local key files.
     */
    public static Network enrichFromState(
            @NonNull final State state,
            @NonNull final Network baseNetwork,
            @NonNull final Configuration config,
            final long selfNodeId,
            @Nullable final Network existingNetwork) {
        requireNonNull(state);
        requireNonNull(baseNetwork);
        requireNonNull(config);
        try {
            final var entityIdStore = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
            final var hintsStore =
                    new ReadableHintsStoreImpl(state.getReadableStates(HintsService.NAME), entityIdStore);
            final var historyStore = new ReadableHistoryStoreImpl(state.getReadableStates(HistoryService.NAME));

            final var activeHintsConstruction = hintsStore.getActiveConstruction();
            final var activeProofConstruction = historyStore.getActiveConstruction();

            final var tssMetadata = TssMetadata.newBuilder()
                    .crsState(hintsStore.getCrsState())
                    .activeHintsConstruction(activeHintsConstruction)
                    .activeProofConstruction(activeProofConstruction)
                    .historyProofVerificationKey(Bytes.wrap(new HistoryLibraryImpl().wrapsVerificationKey()))
                    .wrapsProvingKeyHash(Optional.ofNullable(historyStore.getWrapsProvingKeyHash())
                            .orElse(Bytes.EMPTY))
                    .build();

            final var nodeMetadata = nodeTssMetadataFrom(
                    state,
                    baseNetwork,
                    config,
                    selfNodeId,
                    existingNetwork,
                    activeHintsConstruction,
                    activeProofConstruction);
            final var builder = baseNetwork
                    .copyBuilder()
                    .tssMetadata(tssMetadata)
                    .nodeTssMetadata(nodeMetadata.values().stream().toList());
            Optional.ofNullable(historyStore.getLedgerId()).ifPresent(builder::ledgerId);
            return builder.build();
        } catch (Exception e) {
            log.warn("Unable to include TSS metadata in exported network info", e);
            return baseNetwork;
        }
    }

    /**
     * Initializes hinTS state from a startup network JSON file.
     *
     * @return the active hinTS construction loaded from the network
     */
    public static HintsConstruction initializeHintsState(
            @NonNull final WritableStates hintsStates, @NonNull final Network network) {
        requireNonNull(hintsStates);
        requireNonNull(network);
        if (!hasTssMetadata(network)) {
            return HintsConstruction.DEFAULT;
        }
        final var tssMetadata = network.tssMetadataOrElse(TssMetadata.DEFAULT);
        final var activeConstruction = tssMetadata.activeHintsConstructionOrElse(HintsConstruction.DEFAULT);
        hintsStates
                .<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .put(activeConstruction);
        hintsStates
                .<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID)
                .put(HintsConstruction.DEFAULT);
        hintsStates.<CRSState>getSingleton(CRS_STATE_STATE_ID).put(tssMetadata.crsStateOrElse(CRSState.DEFAULT));

        final var partySize = partySizeFor(network);
        final WritableKVState<HintsPartyId, HintsKeySet> hintsKeys = hintsStates.get(HINTS_KEY_SETS_STATE_ID);
        for (final var nodeTssMetadata : network.nodeTssMetadata()) {
            if (nodeTssMetadata.hintsKey().length() > 0) {
                hintsKeys.put(
                        new HintsPartyId(nodeTssMetadata.partyId(), partySize),
                        HintsKeySet.newBuilder()
                                .nodeId(nodeTssMetadata.nodeId())
                                .adoptionTime(asTimestamp(Instant.EPOCH))
                                .key(nodeTssMetadata.hintsKey())
                                .build());
            }
        }
        return activeConstruction;
    }

    /**
     * Initializes history state from a startup network JSON file.
     *
     * @return the active history proof construction loaded from the network
     */
    public static HistoryProofConstruction initializeHistoryState(
            @NonNull final WritableStates historyStates, @NonNull final Network network) {
        requireNonNull(historyStates);
        requireNonNull(network);
        if (!hasTssMetadata(network)) {
            return HistoryProofConstruction.DEFAULT;
        }
        final var tssMetadata = network.tssMetadataOrElse(TssMetadata.DEFAULT);
        if (network.ledgerId().length() > 0) {
            historyStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID).put(new ProtoBytes(network.ledgerId()));
        }
        final var activeConstruction = tssMetadata.activeProofConstructionOrElse(HistoryProofConstruction.DEFAULT);
        historyStates
                .<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID)
                .put(activeConstruction);
        historyStates
                .<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID)
                .put(HistoryProofConstruction.DEFAULT);
        historyStates
                .<ProtoBytes>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID)
                .put(new ProtoBytes(tssMetadata.wrapsProvingKeyHash()));

        final WritableKVState<NodeId, ProofKeySet> proofKeys = historyStates.get(PROOF_KEY_SETS_STATE_ID);
        for (final var nodeTssMetadata : network.nodeTssMetadata()) {
            if (nodeTssMetadata.schnorrPublicKey().length() > 0) {
                proofKeys.put(
                        new NodeId(nodeTssMetadata.nodeId()),
                        ProofKeySet.newBuilder()
                                .adoptionTime(asTimestamp(Instant.EPOCH))
                                .key(nodeTssMetadata.schnorrPublicKey())
                                .build());
            }
        }
        return activeConstruction;
    }

    /**
     * Initializes runtime TSS service contexts from a startup network JSON file.
     */
    public static void initializeRuntime(
            @NonNull final Network network,
            @NonNull final HintsService hintsService,
            @NonNull final HistoryService historyService) {
        requireNonNull(network);
        requireNonNull(hintsService);
        requireNonNull(historyService);
        if (!hasTssMetadata(network)) {
            return;
        }
        final var tssMetadata = network.tssMetadataOrElse(TssMetadata.DEFAULT);
        initializeRuntime(
                tssMetadata.activeHintsConstructionOrElse(HintsConstruction.DEFAULT),
                tssMetadata.activeProofConstructionOrElse(HistoryProofConstruction.DEFAULT),
                hintsService,
                historyService);
    }

    /**
     * Initializes runtime TSS service contexts from active constructions already loaded from startup network JSON.
     */
    public static void initializeRuntime(
            @NonNull final HintsConstruction activeHintsConstruction,
            @NonNull final HistoryProofConstruction activeProofConstruction,
            @NonNull final HintsService hintsService,
            @NonNull final HistoryService historyService) {
        requireNonNull(activeHintsConstruction);
        requireNonNull(activeProofConstruction);
        requireNonNull(hintsService);
        requireNonNull(historyService);
        if (activeHintsConstruction.hasHintsScheme()) {
            hintsService.setActiveConstruction(activeHintsConstruction);
        }
        if (activeProofConstruction.hasTargetProof()) {
            historyService.setLatestHistoryProof(activeProofConstruction.targetProofOrThrow());
        }
    }

    /**
     * Writes this node's private TSS keys from a startup network JSON file to the local TSS key path.
     */
    public static void writePrivateKeys(
            @NonNull final Network network, @NonNull final Configuration config, final long selfNodeId) {
        requireNonNull(network);
        requireNonNull(config);
        if (!hasTssMetadata(network)) {
            return;
        }
        final var tssMetadata = network.tssMetadataOrElse(TssMetadata.DEFAULT);
        final var selfMetadata = network.nodeTssMetadata().stream()
                .filter(metadata -> metadata.nodeId() == selfNodeId)
                .findFirst();
        if (selfMetadata.isEmpty()) {
            log.warn("No TSS key material found for self node{} in startup network", selfNodeId);
            return;
        }
        final var metadata = selfMetadata.get();
        if (metadata.blsPrivateKey().length() > 0) {
            TssKeyFiles.writeBlsPrivateKey(config, hintsConstructionId(tssMetadata), metadata.blsPrivateKey());
        }
        if (metadata.schnorrPrivateKey().length() > 0
                && metadata.schnorrPublicKey().length() > 0) {
            TssKeyFiles.writeSchnorrKeyPair(
                    config,
                    proofConstructionId(tssMetadata),
                    new TssKeyFiles.SchnorrKeyPair(metadata.schnorrPrivateKey(), metadata.schnorrPublicKey()));
        }
    }

    private static TreeMap<Long, NodeTssMetadata> nodeTssMetadataFrom(
            @NonNull final State state,
            @NonNull final Network baseNetwork,
            @NonNull final Configuration config,
            final long selfNodeId,
            @Nullable final Network existingNetwork,
            @NonNull final HintsConstruction activeHintsConstruction,
            @NonNull final HistoryProofConstruction activeProofConstruction) {
        final var metadata = new TreeMap<Long, NodeTssMetadata>();
        if (existingNetwork != null) {
            existingNetwork
                    .nodeTssMetadata()
                    .forEach(nodeMetadata -> metadata.put(nodeMetadata.nodeId(), nodeMetadata));
        }
        final var nodeIds = nodeIdsFrom(baseNetwork, existingNetwork);
        nodeIds.forEach(nodeId -> metadata.putIfAbsent(
                nodeId, NodeTssMetadata.newBuilder().nodeId(nodeId).build()));

        addPartyMetadata(metadata, activeHintsConstruction);
        addPublicHintsKeys(state, baseNetwork, metadata);
        addPublicProofKeys(state, metadata);
        addPrivateKeys(config, selfNodeId, metadata, activeHintsConstruction, activeProofConstruction);
        return metadata;
    }

    private static TreeSet<Long> nodeIdsFrom(
            @NonNull final Network baseNetwork, @Nullable final Network existingNetwork) {
        final var nodeIds = new TreeSet<Long>();
        baseNetwork.nodeMetadata().stream()
                .filter(metadata -> metadata.hasRosterEntry() || metadata.hasNode())
                .map(metadata -> metadata.hasRosterEntry()
                        ? metadata.rosterEntryOrThrow().nodeId()
                        : metadata.nodeOrThrow().nodeId())
                .forEach(nodeIds::add);
        if (existingNetwork != null) {
            existingNetwork.nodeTssMetadata().stream()
                    .map(NodeTssMetadata::nodeId)
                    .forEach(nodeIds::add);
        }
        return nodeIds;
    }

    private static void addPartyMetadata(
            @NonNull final Map<Long, NodeTssMetadata> metadata, @NonNull final HintsConstruction construction) {
        if (!construction.hasHintsScheme()) {
            return;
        }
        construction
                .hintsSchemeOrThrow()
                .nodePartyIds()
                .forEach(nodePartyId -> metadata.compute(
                        nodePartyId.nodeId(), (nodeId, nodeMetadata) -> builderFor(nodeMetadata, nodeId)
                                .partyId(nodePartyId.partyId())
                                .tssWeight(nodePartyId.partyWeight())
                                .build()));
    }

    private static void addPublicHintsKeys(
            @NonNull final State state,
            @NonNull final Network baseNetwork,
            @NonNull final Map<Long, NodeTssMetadata> metadata) {
        final var activeRosterEntries = baseNetwork.nodeMetadata().stream()
                .filter(nodeMetadata -> nodeMetadata.hasRosterEntry())
                .map(nodeMetadata -> nodeMetadata.rosterEntryOrThrow())
                .toList();
        if (activeRosterEntries.isEmpty()) {
            return;
        }
        final var nodeIds =
                activeRosterEntries.stream().map(RosterEntry::nodeId).collect(Collectors.toSet());
        final var partySize = partySizeForRosterNodeCount(activeRosterEntries.size());
        final var entityIdStore = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
        final var hintsStore = new ReadableHintsStoreImpl(state.getReadableStates(HintsService.NAME), entityIdStore);
        hintsStore
                .getHintsKeyPublications(nodeIds, partySize)
                .forEach(publication -> metadata.compute(
                        publication.nodeId(), (nodeId, nodeMetadata) -> builderFor(nodeMetadata, nodeId)
                                .partyId(publication.partyId())
                                .hintsKey(publication.hintsKey())
                                .build()));
    }

    private static void addPublicProofKeys(
            @NonNull final State state, @NonNull final Map<Long, NodeTssMetadata> metadata) {
        final var historyStore = new ReadableHistoryStoreImpl(state.getReadableStates(HistoryService.NAME));
        final var activeProof = historyStore.getActiveConstruction();
        final var publicKeys = new HashMap<Long, Bytes>();
        if (activeProof.hasTargetProof()) {
            activeProof.targetProofOrThrow().targetProofKeys().stream()
                    .collect(Collectors.toMap(ProofKey::nodeId, ProofKey::key, (a, b) -> a, TreeMap::new))
                    .forEach(publicKeys::put);
        }
        final Set<Long> nodeIds = metadata.keySet();
        historyStore
                .getProofKeyPublications(nodeIds)
                .forEach(publication -> publicKeys.putIfAbsent(publication.nodeId(), publication.proofKey()));
        publicKeys.forEach((nodeId, proofKey) ->
                metadata.compute(nodeId, (ignore, nodeMetadata) -> builderFor(nodeMetadata, nodeId)
                        .schnorrPublicKey(proofKey)
                        .build()));
    }

    private static void addPrivateKeys(
            @NonNull final Configuration config,
            final long selfNodeId,
            @NonNull final Map<Long, NodeTssMetadata> metadata,
            @NonNull final HintsConstruction activeHintsConstruction,
            @NonNull final HistoryProofConstruction activeProofConstruction) {
        metadata.computeIfPresent(selfNodeId, (nodeId, nodeMetadata) -> {
            var builder = nodeMetadata.copyBuilder();
            TssKeyFiles.readBlsPrivateKey(config, activeHintsConstruction.constructionId())
                    .ifPresent(builder::blsPrivateKey);
            TssKeyFiles.readSchnorrKeyPair(config, activeProofConstruction.constructionId())
                    .ifPresent(keyPair ->
                            builder.schnorrPrivateKey(keyPair.privateKey()).schnorrPublicKey(keyPair.publicKey()));
            return builder.build();
        });
    }

    private static NodeTssMetadata.Builder builderFor(@Nullable final NodeTssMetadata metadata, final long nodeId) {
        return metadata == null
                ? NodeTssMetadata.newBuilder().nodeId(nodeId)
                : metadata.copyBuilder().nodeId(nodeId);
    }

    private static int partySizeFor(@NonNull final Network network) {
        final var maxPartyId = network.nodeTssMetadata().stream()
                .map(NodeTssMetadata::partyId)
                .max(Comparator.naturalOrder())
                .orElse(0);
        final var rosterSize = Math.max(1, nodeIdsFrom(network, null).size());
        return Math.max(maxPartyId + 1, partySizeForRosterNodeCount(rosterSize));
    }

    private static long hintsConstructionId(@NonNull final TssMetadata tssMetadata) {
        return tssMetadata
                .activeHintsConstructionOrElse(HintsConstruction.DEFAULT)
                .constructionId();
    }

    private static long proofConstructionId(@NonNull final TssMetadata tssMetadata) {
        return tssMetadata
                .activeProofConstructionOrElse(HistoryProofConstruction.DEFAULT)
                .constructionId();
    }
}
