// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static com.hedera.hapi.node.state.hints.CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.*;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.*;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore.HintsKeyPublication;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.junit.jupiter.api.Test;

/** Exercises the production service, controllers and stores with the actual native hinTS library. */
class CrsRosterTransitionTest {
    private static final Instant NOW = Instant.ofEpochSecond(12345);
    private static final Bytes OLD_HASH = Bytes.wrap("six nodes");
    private static final Bytes NEW_HASH = Bytes.wrap("seven nodes");
    private static final Bytes MESSAGE = Bytes.wrap("a block signed across a CRS transition");

    @Test
    void growsWithFreshCeremonyAndRestartThenKeepsCapacityWhenRosterShrinks() throws Exception {
        final var library = new HintsLibraryImpl();
        library.resetCache();
        final var config = HederaTestConfigBuilder.createConfig();
        final var tssConfig = config.getConfigData(TssConfig.class);
        final var oldRoster = roster(6);
        final var newRoster = roster(7);
        final var seed = library.newCrs((short) 8);
        final var oldCrsBytes = decodeCrsUpdate(seed.length(), library.updateCrs(seed, entropy(1)))
                .crs();
        final var privateKeys = new ArrayList<Bytes>();
        final var oldHints = new TreeMap<Integer, Bytes>();
        final var oldWeights = new TreeMap<Integer, Long>();
        for (int i = 0; i < 6; i++) {
            final var key = library.newBlsPrivateKey();
            privateKeys.add(key);
            oldHints.put(i + 1, library.computeHints(oldCrsBytes, key, i + 1, 8));
            oldWeights.put(i + 1, 1L);
        }
        final var oldKeys = library.preprocess(oldCrsBytes, oldHints, oldWeights, 8);
        assertNotNull(oldKeys);
        final var oldConstruction = HintsConstruction.newBuilder()
                .constructionId(1)
                .crsId(1)
                .numParties(8)
                .sourceRosterHash(OLD_HASH)
                .targetRosterHash(OLD_HASH)
                .hintsScheme(new HintsScheme(
                        new PreprocessedKeys(
                                Bytes.wrap(oldKeys.aggregationKey()), Bytes.wrap(oldKeys.verificationKey())),
                        IntStream.range(0, 6)
                                .mapToObj(i -> new NodePartyId(i, i + 1, 1))
                                .toList()))
                .build();
        final var oldCrs = CRSState.newBuilder()
                .ceremonyId(1)
                .numParties(8)
                .stage(COMPLETED)
                .lastUsedCeremonyId(1)
                .crs(oldCrsBytes)
                .initialCrs(seed)
                .build();
        final var store = store(oldConstruction, oldCrs);
        final var signingContext = new HintsContext(library, () -> config, mock(HintsSigningMetrics.class));
        signingContext.setConstruction(oldConstruction, oldCrs);
        signingContext.onBlockStarted(100);
        final var oldPartial = HintsPartialSignatureTransactionBody.newBuilder()
                .constructionId(1)
                .message(MESSAGE)
                .partialSignature(library.signBls(MESSAGE, privateKeys.getFirst()))
                .build();
        assertTrue(signingContext.validate(0, oldCrsBytes, oldPartial));
        final var submissions = mock(HintsSubmissions.class);
        final var publishedKeys = new AtomicReference<PreprocessedKeys>();
        when(submissions.submitHintsVote(anyLong(), any(PreprocessedKeys.class)))
                .thenAnswer(inv -> {
                    publishedKeys.set(inv.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        final var accessor = mock(HintsKeyAccessor.class);
        when(accessor.getOrCreateBlsPrivateKey(anyLong())).thenReturn(privateKeys.getFirst());
        final var self = mock(NodeInfo.class);
        when(self.nodeId()).thenReturn(0L);
        final var controllers = new HintsControllers(
                Runnable::run,
                accessor,
                library,
                submissions,
                signingContext,
                () -> self,
                () -> config,
                (s, c, context) -> {});
        final var component = mock(HintsServiceComponent.class);
        when(component.controllers()).thenReturn(controllers);
        when(component.signingContext()).thenReturn(signingContext);
        final var service = new HintsServiceImpl(component, library);
        final var growth = transition(oldRoster, OLD_HASH, newRoster, NEW_HASH);
        service.reconcile(growth, store, NOW, tssConfig, false);
        final var next = store.getNextConstruction();
        assertEquals(16, next.numParties());
        assertNotEquals(oldCrs.ceremonyId(), next.crsId());
        assertFalse(next.hasGracePeriodEndTime());
        assertFalse(store.isReadyToAdopt(NEW_HASH));
        assertEquals(oldCrsBytes, store.getCrsState().crs());
        CrsPublicationTransactionBody stale = null;
        for (int i = 0; i < 6; i++) {
            final var current = store.getNextCrsState();
            final var update =
                    decodeCrsUpdate(current.crs().length(), library.updateCrs(current.crs(), entropy(i + 2)));
            final var publication = CrsPublicationTransactionBody.newBuilder()
                    .ceremonyId(current.ceremonyId())
                    .attempt(current.attempt())
                    .previousCrsHash(noThrowSha384HashOf(current.crs()))
                    .newCrs(update.crs())
                    .proof(update.proof())
                    .build();
            if (stale == null) stale = publication;
            controllers
                    .getAnyInProgress()
                    .orElseThrow()
                    .addCrsPublication(publication, NOW.plusSeconds(i + 1), store, i);
            assertEquals(i + 1, store.getNextCrsState().contributedNodeIds().size());
            assertEquals(oldCrsBytes, store.getCrsState().crs());
            assertTrue(signingContext.validate(0, oldCrsBytes, oldPartial));
            if (i == 2) {
                controllers.stop();
                service.reconcile(growth, store, NOW.plusSeconds(i + 1), tssConfig, false);
                assertEquals(3, store.getNextCrsState().contributedNodeIds().size());
            }
        }
        final var completedHead = store.getNextCrsState().crs();
        controllers.getAnyInProgress().orElseThrow().addCrsPublication(stale, NOW.plusSeconds(7), store, 0);
        assertEquals(completedHead, store.getNextCrsState().crs());
        service.reconcile(growth, store, NOW.plusSeconds(7), tssConfig, false);
        assertEquals(WAITING_FOR_ADOPTING_FINAL_CRS, store.getNextCrsState().stage());
        controllers.stop();
        final var keysStart = NOW.plusSeconds(7).plus(tssConfig.crsFinalizationDelay());
        service.reconcile(growth, store, keysStart, tssConfig, false);
        assertEquals(COMPLETED, store.getNextCrsState().stage());
        assertNotEquals(oldCrsBytes, store.getNextCrsState().crs());
        assertTrue(store.getNextConstruction().hasGracePeriodEndTime());
        final var controller = controllers.getAnyInProgress().orElseThrow();
        for (int i = 0; i < 6; i++) {
            final int partyId = controller.partyIdOf(i).orElseThrow();
            final var hints = library.computeHints(completedHead, privateKeys.get(i), partyId, 16);
            assertNotNull(hints);
            final var publicationTime = keysStart.plusNanos(i + 1);
            assertTrue(store.setHintsKey(i, partyId, 16, next.crsId(), hints, publicationTime));
            controller.addHintsKeyPublication(
                    new HintsKeyPublication(i, hints, partyId, publicationTime), completedHead);
        }
        controller.advanceConstruction(keysStart.plusSeconds(1), store, true);
        final var outputs = publishedKeys.get();
        assertNotNull(outputs, "The production controller must preprocess the native hints");
        final var vote =
                PreprocessingVote.newBuilder().preprocessedKeys(outputs).build();
        assertTrue(controller.addPreprocessingVote(0, vote, store));
        assertFalse(store.isReadyToAdopt(NEW_HASH));
        assertTrue(controller.addPreprocessingVote(1, vote, store));
        assertTrue(store.isReadyToAdopt(NEW_HASH));
        final var oldSigning = signingContext.newSigningForActiveConstruction(MESSAGE, () -> {});
        assertTrue(service.handoff(store, oldRoster, newRoster, NEW_HASH, false));
        assertEquals(16, store.getCrsState().numParties());
        assertEquals(completedHead, store.getCrsState().crs());
        assertEquals(CRSState.DEFAULT, store.getNextCrsState());
        assertTrue(signingContext.validate(0, oldCrsBytes, oldPartial), "Old signing keeps its CRS in mixed mode");
        final var activeId = store.getActiveConstruction().constructionId();
        final var nextPartial = HintsPartialSignatureTransactionBody.newBuilder()
                .constructionId(activeId)
                .message(MESSAGE)
                .partialSignature(library.signBls(MESSAGE, privateKeys.getFirst()))
                .build();
        assertTrue(signingContext.validate(0, completedHead, nextPartial));
        assertFalse(signingContext.validate(0, oldCrsBytes, nextPartial));
        for (int i = 0; i < 4; i++) {
            oldSigning.incorporateValid(oldCrsBytes, i, library.signBls(MESSAGE, privateKeys.get(i)));
        }
        assertTrue(library.verifyAggregate(
                oldSigning.future().get(10, java.util.concurrent.TimeUnit.SECONDS),
                MESSAGE,
                Bytes.wrap(oldKeys.verificationKey()),
                1,
                2));
        final var newSigning = signingContext.newSigningForActiveConstruction(MESSAGE, () -> {});
        for (int i = 0; i < 4; i++) {
            newSigning.incorporateValid(completedHead, i, library.signBls(MESSAGE, privateKeys.get(i)));
        }
        assertTrue(library.verifyAggregate(
                newSigning.future().get(10, java.util.concurrent.TimeUnit.SECONDS),
                MESSAGE,
                outputs.verificationKey(),
                1,
                2));
        publishedKeys.set(null);
        final var shrink = transition(newRoster, NEW_HASH, oldRoster, OLD_HASH);
        service.reconcile(shrink, store, keysStart.plusSeconds(2), tssConfig, false);
        final var smaller = store.getNextConstruction();
        assertEquals(16, smaller.numParties());
        assertEquals(next.crsId(), smaller.crsId());
        assertEquals(CRSState.DEFAULT, store.getNextCrsState());
        final var retainedHints = library.computeHints(completedHead, privateKeys.getFirst(), 1, smaller.numParties());
        assertTrue(library.validateHintsKey(completedHead, retainedHints, 1, 16));
        final var shrinkController = controllers.getAnyInProgress().orElseThrow();
        shrinkController.advanceConstruction(keysStart.plusSeconds(3), store, true);
        final var shrinkOutputs = publishedKeys.get();
        assertNotNull(shrinkOutputs, "Shrinking must preprocess using the retained CRS degree");
        final var shrinkVote =
                PreprocessingVote.newBuilder().preprocessedKeys(shrinkOutputs).build();
        assertTrue(shrinkController.addPreprocessingVote(0, shrinkVote, store));
        assertTrue(shrinkController.addPreprocessingVote(1, shrinkVote, store));
        assertTrue(store.isReadyToAdopt(OLD_HASH));
        assertTrue(service.handoff(store, newRoster, oldRoster, OLD_HASH, false));
        assertEquals(16, store.getActiveConstruction().numParties());
        assertEquals(next.crsId(), store.getCrsState().ceremonyId());
        assertEquals(completedHead, store.getCrsState().crs());
        final var shrinkSigning = signingContext.newSigningForActiveConstruction(MESSAGE, () -> {});
        for (int i = 0; i < 4; i++) {
            shrinkSigning.incorporateValid(completedHead, i, library.signBls(MESSAGE, privateKeys.get(i)));
        }
        assertTrue(library.verifyAggregate(
                shrinkSigning.future().get(10, java.util.concurrent.TimeUnit.SECONDS),
                MESSAGE,
                shrinkOutputs.verificationKey(),
                1,
                2));
        library.resetCache();
    }

    private static Roster roster(int count) {
        return new Roster(IntStream.range(0, count)
                .mapToObj(i -> RosterEntry.newBuilder().nodeId(i).weight(1).build())
                .toList());
    }

    private static ActiveRosters transition(Roster source, Bytes sourceHash, Roster target, Bytes targetHash) {
        final var rosterStore = mock(ReadableRosterStore.class);
        when(rosterStore.getActiveRosterHash()).thenReturn(sourceHash);
        when(rosterStore.getCandidateRosterHash()).thenReturn(targetHash);
        when(rosterStore.get(sourceHash)).thenReturn(source);
        when(rosterStore.get(targetHash)).thenReturn(target);
        return ActiveRosters.from(rosterStore, false, () -> false, null);
    }

    private static WritableHintsStoreImpl store(HintsConstruction active, CRSState crs) {
        final var states = new MapWritableStates(Map.of(
                ACTIVE_HINTS_CONSTRUCTION_STATE_ID,
                        singleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID, ACTIVE_HINTS_CONSTRUCTION_STATE_LABEL, active),
                NEXT_HINTS_CONSTRUCTION_STATE_ID,
                        singleton(
                                NEXT_HINTS_CONSTRUCTION_STATE_ID,
                                NEXT_HINTS_CONSTRUCTION_STATE_LABEL,
                                HintsConstruction.DEFAULT),
                CRS_STATE_STATE_ID, singleton(CRS_STATE_STATE_ID, CRS_STATE_STATE_LABEL, crs),
                NEXT_CRS_STATE_ID, singleton(NEXT_CRS_STATE_ID, NEXT_CRS_STATE_LABEL, CRSState.DEFAULT),
                HINTS_KEY_SETS_STATE_ID,
                        new MapWritableKVState<>(HINTS_KEY_SETS_STATE_ID, HINTS_KEY_SETS_STATE_LABEL, new HashMap<>()),
                PREPROCESSING_VOTES_STATE_ID,
                        new MapWritableKVState<>(
                                PREPROCESSING_VOTES_STATE_ID, PREPROCESSING_VOTES_STATE_LABEL, new HashMap<>()),
                CRS_PUBLICATIONS_STATE_ID,
                        new MapWritableKVState<>(
                                CRS_PUBLICATIONS_STATE_ID, CRS_PUBLICATIONS_STATE_LABEL, new HashMap<>())));
        return new WritableHintsStoreImpl(states, mock(WritableEntityIdStore.class));
    }

    private static <T> FunctionWritableSingletonState<T> singleton(int id, String label, T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, label, value::get, value::set);
    }

    private static Bytes entropy(int value) {
        final var entropy = new byte[32];
        entropy[0] = (byte) value;
        return Bytes.wrap(entropy);
    }
}
