// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/** Isolates upcoming CRS ceremonies from the active signing generation. */
public class V079HintsSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(79).build();
    private static final int MAX_PARTIES = 512;
    public static final String NEXT_CRS_STATE_KEY = "NEXT_CRS_STATE";
    public static final int NEXT_CRS_STATE_ID = SingletonType.HINTSSERVICE_I_NEXT_CRS_STATE.protoOrdinal();
    public static final String NEXT_CRS_STATE_LABEL = computeLabel(HintsService.NAME, NEXT_CRS_STATE_KEY);

    private final HintsLibrary library;
    private final HintsContext signingContext;

    public V079HintsSchema(@NonNull final HintsLibrary library, @NonNull final HintsContext signingContext) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.library = requireNonNull(library);
        this.signingContext = requireNonNull(signingContext);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(NEXT_CRS_STATE_ID, NEXT_CRS_STATE_KEY, CRSState.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var states = ctx.newStates();
        final var nextCrs = states.<CRSState>getSingleton(NEXT_CRS_STATE_ID);
        if (nextCrs.get() == null) {
            nextCrs.put(CRSState.DEFAULT);
        }
        final var activeCrs = states.<CRSState>getSingleton(CRS_STATE_STATE_ID);
        final var activeState = states.<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID);
        final var nextState = states.<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID);
        if (activeCrs.get() == null) {
            activeCrs.put(CRSState.DEFAULT);
        }
        if (activeState.get() == null) {
            activeState.put(HintsConstruction.DEFAULT);
        }
        if (nextState.get() == null) {
            nextState.put(HintsConstruction.DEFAULT);
        }
        final var legacy = requireNonNull(activeCrs.get());
        if (ctx.isGenesis() || legacy.ceremonyId() > 0) {
            return;
        }
        final var active = requireNonNull(activeState.get());
        final var next = requireNonNull(nextState.get());
        final var keys = states.<HintsPartyId, HintsKeySet>get(HINTS_KEY_SETS_STATE_ID);
        long lastConstructionId = Math.max(active.constructionId(), next.constructionId());
        if (legacy.stage() == COMPLETED && legacy.crs().length() > 0) {
            final int capacity = library.crsPartySize(legacy.crs());
            if (active.hasHintsScheme() && legacyPartySize(active, keys) != capacity) {
                throw new IllegalStateException(
                        "Legacy hinTS scheme capacity does not match its CRS; refusing migration");
            }
            final long ceremonyId = Math.incrementExact(legacy.lastUsedCeremonyId());
            activeCrs.put(legacy.copyBuilder()
                    .ceremonyId(ceremonyId)
                    .numParties(capacity)
                    .lastUsedCeremonyId(ceremonyId)
                    .build());
            final var bound = active.hasHintsScheme()
                    ? active.copyBuilder()
                            .crsId(ceremonyId)
                            .numParties(capacity)
                            .build()
                    : unstarted(active, ++lastConstructionId)
                            .copyBuilder()
                            .crsId(ceremonyId)
                            .numParties(capacity)
                            .build();
            activeState.put(bound);
            migrateKeys(keys, capacity, ceremonyId);
        } else {
            if (active.hasHintsScheme()) {
                throw new IllegalStateException("Completed legacy hinTS scheme has no completed CRS");
            }
            // Legacy publications have no epoch or complete retry transcript. Never replay them.
            activeCrs.put(CRSState.newBuilder()
                    .lastUsedCeremonyId(legacy.lastUsedCeremonyId())
                    .build());
            activeState.put(unstarted(active, ++lastConstructionId));
            migrateKeys(keys, 0, 0);
        }
        if (!next.equals(HintsConstruction.DEFAULT)) {
            // Preserve roster identity but allocate a new construction ID so old votes cannot be adopted.
            nextState.put(unstarted(next, ++lastConstructionId));
        }
        activeCrs.put(requireNonNull(activeCrs.get())
                .copyBuilder()
                .lastUsedConstructionId(Math.max(lastConstructionId, legacy.lastUsedConstructionId()))
                .build());
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis() || !ctx.appConfig().getConfigData(TssConfig.class).hintsEnabled()) {
            return;
        }
        final var states = ctx.newStates();
        final var active = states.<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .get();
        if (active != null && active.hasHintsScheme()) {
            signingContext.setConstruction(
                    active,
                    requireNonNull(
                            states.<CRSState>getSingleton(CRS_STATE_STATE_ID).get()));
        }
    }

    private static HintsConstruction unstarted(final HintsConstruction previous, final long id) {
        return HintsConstruction.newBuilder()
                .constructionId(id)
                .sourceRosterHash(previous.sourceRosterHash())
                .targetRosterHash(previous.targetRosterHash())
                .build();
    }

    /** The legacy key namespace records the effective n; sparse scheme party IDs do not. */
    private static int legacyPartySize(
            final HintsConstruction construction, final WritableKVState<HintsPartyId, HintsKeySet> keys) {
        if (construction.numParties() > 0) {
            return construction.numParties();
        }
        final var parties = construction.hintsSchemeOrThrow().nodePartyIds();
        if (parties.isEmpty()) {
            throw new IllegalStateException("Cannot determine legacy hinTS capacity from an empty scheme");
        }
        int matchingCapacity = 0;
        for (int n = 1; n <= MAX_PARTIES; n *= 2) {
            boolean matches = true;
            for (final var party : parties) {
                final var key = keys.get(new HintsPartyId(party.partyId(), n, 0));
                if (party.partyId() >= n || key == null || key.nodeId() != party.nodeId()) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (matchingCapacity != 0) {
                    throw new IllegalStateException("Legacy hinTS capacity is ambiguous across key namespaces");
                }
                matchingCapacity = n;
            }
        }
        if (matchingCapacity == 0) {
            throw new IllegalStateException("Cannot determine legacy hinTS capacity from its stored keys");
        }
        return matchingCapacity;
    }

    private static void migrateKeys(
            final WritableKVState<HintsPartyId, HintsKeySet> keys, final int capacity, final long ceremonyId) {
        for (int n = 1; n <= MAX_PARTIES; n *= 2) {
            for (int partyId = 0; partyId < n; partyId++) {
                final var oldId = new HintsPartyId(partyId, n, 0);
                final var value = keys.get(oldId);
                if (value != null) {
                    if (n == capacity) {
                        keys.put(new HintsPartyId(partyId, n, ceremonyId), value);
                    }
                    keys.remove(oldId);
                }
            }
        }
    }
}
