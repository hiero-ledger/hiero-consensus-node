// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.hints.HintsLibrary.isSupportedPartySize;
import static com.hedera.node.app.hints.HintsLibrary.isValidPartyId;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/** Isolates upcoming CRS ceremonies from the active signing generation. */
public class V079HintsSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(79).build();
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

    private void initializeState(@NonNull final MigrationContext ctx) {
        final var states = ctx.newStates();
        final var activeCrs = states.<CRSState>getSingleton(CRS_STATE_STATE_ID);
        final var nextCrs = states.<CRSState>getSingleton(NEXT_CRS_STATE_ID);
        final var active = states.<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID);
        final var next = states.<HintsConstruction>getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID);
        final var crs = requireNonNullElse(activeCrs.get(), CRSState.DEFAULT);
        // CRS byte length is not a format/version discriminator: pre-Nova material can have
        // the same size. Never reinterpret populated, unbound legacy state as a new generation.
        if (crs.ceremonyId() == 0
                && crs.lastUsedConstructionId() == 0
                && (!crs.equals(CRSState.DEFAULT)
                        || (active.get() != null && !active.get().equals(HintsConstruction.DEFAULT))
                        || (next.get() != null && !next.get().equals(HintsConstruction.DEFAULT))
                        || (nextCrs.get() != null && !nextCrs.get().equals(CRSState.DEFAULT)))) {
            throw new IllegalStateException("Legacy hinTS state is incompatible with TSS 3.23.0; "
                    + "a coordinated hinTS/history reset and rebootstrap is required before upgrade. "
                    + "The existing ledger trust root cannot be silently replaced.");
        }
        if (activeCrs.get() == null) {
            activeCrs.put(CRSState.DEFAULT);
        }
        if (nextCrs.get() == null) {
            nextCrs.put(CRSState.DEFAULT);
        }
        if (active.get() == null) {
            active.put(HintsConstruction.DEFAULT);
        }
        if (next.get() == null) {
            next.put(HintsConstruction.DEFAULT);
        }
        validatePersistedState(requireNonNull(activeCrs.get()), requireNonNull(active.get()), "ACTIVE");
        validatePersistedState(requireNonNull(nextCrs.get()), requireNonNull(next.get()), "NEXT");
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis()) {
            return;
        }
        initializeState(ctx);
        if (!ctx.appConfig().getConfigData(TssConfig.class).hintsEnabled()) {
            return;
        }
        final var states = ctx.newStates();
        final var active = states.<HintsConstruction>getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID)
                .get();
        final var activeCrs =
                requireNonNull(states.<CRSState>getSingleton(CRS_STATE_STATE_ID).get());
        if (active.hasHintsScheme()) {
            signingContext.setConstruction(active, activeCrs);
        }
    }

    /** Fail closed even when this schema already ran under an earlier cryptography library. */
    private void validatePersistedState(final CRSState crs, final HintsConstruction construction, final String label) {
        validateCapacityMetadata(crs, construction, label);
        if (crs.crs().length() > 0) {
            final int capacity;
            try {
                capacity = library.crsPartySize(crs.crs());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(label + " hinTS CRS is unsupported or malformed", e);
            }
            if (!isSupportedPartySize(capacity) || (crs.ceremonyId() > 0 && capacity != crs.numParties())) {
                throw new IllegalStateException(label + " hinTS CRS capacity does not match its binding");
            }
            if (crs.initialCrs().length() > 0 && library.crsPartySize(crs.initialCrs()) != capacity) {
                throw new IllegalStateException(label + " hinTS initial CRS capacity does not match its binding");
            }
        } else if (crs.ceremonyId() > 0) {
            throw new IllegalStateException(label + " hinTS ceremony has no CRS");
        }
        if (construction.crsId() > 0 && !isSupportedPartySize(construction.numParties())) {
            throw new IllegalStateException(label + " hinTS construction has no supported CRS capacity");
        }
        validateSchemeParties(construction, construction.numParties(), label);
    }

    private static void validateCapacityMetadata(
            final CRSState crs, final HintsConstruction construction, final String label) {
        if ((crs.numParties() != 0 && !isSupportedPartySize(crs.numParties()))
                || (construction.numParties() != 0 && !isSupportedPartySize(construction.numParties()))) {
            throw new IllegalStateException(label + " hinTS state has unsupported capacity; refusing restart");
        }
    }

    private static void validateSchemeParties(
            final HintsConstruction construction, final int capacity, final String label) {
        if (construction.hasHintsScheme()
                && (!isSupportedPartySize(capacity)
                        || construction.hintsSchemeOrThrow().nodePartyIds().stream()
                                .anyMatch(p -> !isValidPartyId(p.partyId(), capacity)))) {
            throw new IllegalStateException(label + " hinTS scheme uses an unsupported or reserved party ID");
        }
    }
}
