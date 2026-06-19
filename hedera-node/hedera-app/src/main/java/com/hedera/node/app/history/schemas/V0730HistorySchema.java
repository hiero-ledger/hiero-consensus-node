// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.history.schemas.V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.LEDGER_ID_STATE_ID;
import static com.hedera.node.app.history.schemas.V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Registers the expected WRAPS proving key hash singleton state for the {@link HistoryService}.
 */
public class V0730HistorySchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0730HistorySchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    public static final String WRAPS_PROVING_KEY_HASH_KEY = "WRAPS_PROVING_KEY_HASH";
    public static final int WRAPS_PROVING_KEY_HASH_STATE_ID =
            SingletonType.HISTORYSERVICE_I_WRAPS_PROVING_KEY_HASH.protoOrdinal();

    private final HistoryService historyService;

    public V0730HistorySchema(@NonNull final HistoryService historyService) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.historyService = Objects.requireNonNull(historyService);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(
                WRAPS_PROVING_KEY_HASH_STATE_ID, WRAPS_PROVING_KEY_HASH_KEY, ProtoBytes.PROTOBUF));
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            if (ctx.appConfig().getConfigData(TssConfig.class).historyEnabled()) {
                final var writableStates = ctx.newStates();
                if (writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID).get() == null) {
                    writableStates.<ProtoBytes>getSingleton(LEDGER_ID_STATE_ID).put(ProtoBytes.DEFAULT);
                }
                if (writableStates
                                .<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID)
                                .get()
                        == null) {
                    writableStates
                            .<HistoryProofConstruction>getSingleton(ACTIVE_PROOF_CONSTRUCTION_STATE_ID)
                            .put(HistoryProofConstruction.DEFAULT);
                }
                if (writableStates
                                .<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID)
                                .get()
                        == null) {
                    writableStates
                            .<HistoryProofConstruction>getSingleton(NEXT_PROOF_CONSTRUCTION_STATE_ID)
                            .put(HistoryProofConstruction.DEFAULT);
                }
                V071HistorySchema.restoreLatestHistoryProof(writableStates, historyService);
            }
            final var hashState = ctx.newStates().<ProtoBytes>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID);
            final var currentProtoBytes = hashState.get();
            if (currentProtoBytes == null) {
                hashState.put(ProtoBytes.DEFAULT);
            }

            // Only put() when the configured hash actually differs from state. A no-op put of the
            // same value is captured by the boundary state-change listener as a spurious change,
            // which diverges numPrecedingStateChangesItems on replay -> SELF_ISS.
            final var configuredHash =
                    ctx.appConfig().getConfigData(TssConfig.class).wrapsProvingKeyHash();
            if (!isBlank(configuredHash)) {
                final var newHash = Bytes.fromHex(configuredHash);
                final var existingHash = currentProtoBytes != null ? currentProtoBytes.value() : Bytes.EMPTY;
                if (Bytes.EMPTY.equals(existingHash)) {
                    log.info("Persisted first WRAPS proving key hash {} to state", newHash);
                    hashState.put(ProtoBytes.newBuilder().value(newHash).build());
                } else if (!existingHash.equals(newHash)) {
                    log.info(
                            "Overwriting previous WRAPS proving key hash {} with new pending hash {}",
                            existingHash,
                            newHash);
                    hashState.put(ProtoBytes.newBuilder().value(newHash).build());
                } else {
                    log.info("Pending WRAPS proving key hash {} matches proving key in state", newHash);
                }
            }
        }
    }
}
