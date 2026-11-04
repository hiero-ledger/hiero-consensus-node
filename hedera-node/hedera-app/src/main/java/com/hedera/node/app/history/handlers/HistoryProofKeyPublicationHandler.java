// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.HintsKeyWitnessVerifier;
import com.hedera.node.app.history.impl.ProofControllers;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class HistoryProofKeyPublicationHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(HistoryProofKeyPublicationHandler.class);
    private final ProofControllers controllers;
    private final LoadingCache<WitnessVerification, Boolean> witnessVerifications;

    /** Only pure cryptography is cached; consensus admission is always checked again against current state. */
    private record WitnessVerification(Bytes verificationKey, HintsKeyWitness witness) {}

    @Inject
    public HistoryProofKeyPublicationHandler(
            @NonNull final ProofControllers controllers, @NonNull final HintsKeyWitnessVerifier witnessVerifier) {
        this.controllers = requireNonNull(controllers);
        requireNonNull(witnessVerifier);
        witnessVerifications = Caffeine.newBuilder()
                .maximumSize(2048)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build(input -> witnessVerifier.verify(input.witness(), input.verificationKey()));
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().historyProofKeyPublicationOrThrow();
        if (!op.hasKeyWitness()
                || context.configuration().getConfigData(TssConfig.class).forceMockSignatures()) {
            return;
        }
        final var witness = op.keyWitnessOrThrow();
        final var record = context.createStore(ReadableHistoryStore.class)
                .getKeyWitnessRecord(witness.targetHintsConstructionId());
        if (isEligiblePendingRecord(witness, record)) {
            // Pairings normally run on the pre-handle workers. Both valid and invalid results are cached,
            // with bounded memory; neither cache residency nor pre-handle's state snapshot decides admission.
            witnessVerifications.get(
                    new WitnessVerification(record.provenanceOrThrow().targetVerificationKey(), witness));
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().historyProofKeyPublicationOrThrow();
        final var historyStore = context.storeFactory().writableStore(WritableHistoryStore.class);
        if (op.hasKeyWitness()) {
            if (context.configuration().getConfigData(TssConfig.class).forceMockSignatures()) {
                return;
            }
            final var witness = op.keyWitnessOrThrow();
            final var record = historyStore.getKeyWitnessRecord(witness.targetHintsConstructionId());
            // Publication is independent of an in-progress history proof, including historical backfill.
            // Never let local aggregation completion, or a certificate for an unadopted key, create this record.
            if (!isEligiblePendingRecord(witness, record)) {
                return;
            }
            final var provenance = record.provenanceOrThrow();
            final var timestamp = asInstant(witness.timestampOrThrow());
            if (timestamp.isAfter(context.consensusNow())
                    // Recompute on a cache miss (e.g. adoption since pre-handle) so cache eviction or worker
                    // scheduling cannot change consensus results.
                    || !Boolean.TRUE.equals(witnessVerifications.get(
                            new WitnessVerification(provenance.targetVerificationKey(), witness)))) {
                log.debug(
                        "Ignoring invalid block-signing witness for hinTS construction #{}",
                        witness.targetHintsConstructionId());
                return;
            }
            if (historyStore.setKeyWitness(witness)) {
                log.info(
                        "Recorded block-signing witness for hinTS construction #{} at {}",
                        witness.targetHintsConstructionId(),
                        timestamp);
            }
            return;
        }
        final long nodeId = context.creatorInfo().nodeId();
        if (op.hasProofKey()) {
            final var key = op.proofKeyOrThrow();
            log.info("node{} published new proof key '{}'", nodeId, key);
            // Returns true if the key is immediately in use, hence needs to be given to the in-progress controller
            if (historyStore.setProofKey(nodeId, key, context.consensusNow())) {
                controllers.getAnyInProgress().ifPresent(controller -> {
                    final var publication = new ProofKeyPublication(nodeId, key, context.consensusNow());
                    controller.addProofKeyPublication(publication);
                    log.info("  - Added proof key to ongoing construction #{}", controller.constructionId());
                });
            }
        } else if (op.hasWrapsMessage()) {
            final var message = op.wrapsMessageOrThrow();
            log.info("node{} published new WRAPS message '{}'", nodeId, message);
            controllers.getInProgressById(op.constructionId()).ifPresent(controller -> {
                final var publication = new WrapsMessagePublication(
                        nodeId, message, op.phase(), context.consensusNow(), op.hintsPartialSignature());
                if (controller.addWrapsMessagePublication(publication, historyStore)) {
                    historyStore.addWrapsMessage(controller.constructionId(), publication);
                }
            });
        }
    }

    private static boolean isEligiblePendingRecord(
            @NonNull final HintsKeyWitness witness, @Nullable final HintsKeyWitnessRecord record) {
        return record != null
                && !record.hasWitness()
                && record.hasProvenance()
                && witness.targetHintsConstructionId() > 0
                && record.provenanceOrThrow().targetHintsConstructionId() == witness.targetHintsConstructionId()
                && HintsKeyWitnessVerifier.hasValidStructure(witness)
                && (!record.hasActivationTime()
                        || asInstant(witness.timestampOrThrow()).isAfter(asInstant(record.activationTimeOrThrow())));
    }
}
