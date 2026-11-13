// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.hedera.node.app.history.impl.ProofControllers.groundsChainOfTrust;
import static com.hedera.node.app.service.roster.impl.ActiveRosters.Phase.BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.HintsGenerationWeights;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.hints.impl.WritableHintsStoreImpl;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.hints.schemas.V060HintsSchema;
import com.hedera.node.app.hints.schemas.V079HintsSchema;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.HistoryProofAddressBooks;
import com.hedera.node.app.history.impl.ReadableHistoryStoreImpl;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.app.history.schemas.V071HistorySchema;
import com.hedera.node.app.history.schemas.V0790HistorySchema;
import com.hedera.node.app.history.schemas.V0800HistorySchema;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.impl.ClprServiceImpl;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.tss.PreparedTssHandoff;
import com.hedera.node.app.tss.TssHandoffCoordinator;
import com.hedera.node.app.tss.TssKeyFiles;
import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.clpr.ClprRuntime;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link Hedera} constructor, focused on verifying that services are correctly
 * built and registered on the {@link ServicesRegistry}.
 */
final class HederaTest {

    private FakeServicesRegistry servicesRegistry;
    private Hedera hedera;
    private HederaInjectionComponent app;
    private ClprRuntime clprRuntime;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void beforeEach() {
        servicesRegistry = new FakeServicesRegistry();
        final ServicesRegistry.Factory capturingFactory = (_, _) -> servicesRegistry;

        final var hintsService = mock(HintsService.class);
        when(hintsService.getServiceName()).thenReturn(HintsService.NAME);
        final var historyService = mock(HistoryService.class);
        when(historyService.getServiceName()).thenReturn(HistoryService.NAME);

        hedera = new Hedera(
                ConstructableRegistry.getInstance(),
                capturingFactory,
                new FakeServiceMigrator(),
                InstantSource.system(),
                NodeId.FIRST_NODE_ID,
                _ -> mock(StartupNetworks.class),
                (_, _, _, _, _) -> hintsService,
                (_, _, _) -> historyService,
                (_, _, _, _) -> mock(BlockHashSigner.class),
                HederaTestConfigBuilder.createConfig(),
                new FileSystemManager(),
                TestUtils.metrics(),
                Time.getCurrent());

        app = mock(HederaInjectionComponent.class, RETURNS_DEEP_STUBS);
        clprRuntime = mock(ClprRuntime.class);
        when(app.clprRuntime()).thenReturn(clprRuntime);
        setHederaField("daggerApp", app);
        setHederaField("platform", mock(Platform.class, RETURNS_DEEP_STUBS));
        setHederaField("configProvider", new ConfigProviderImpl());
    }

    @AfterEach
    void afterEach() {
        if (hedera != null
                && hedera.getStateLifecycleManager().getMutableState() instanceof VirtualMapStateImpl state) {
            state.close();
        }
    }

    @Test
    @DisplayName("Constructor builds a ClprService and registers it with the sync method")
    void registersClprService() {
        final var clprService = serviceOfType(ClprServiceImpl.class);
        assertThat(clprService.getServiceName()).isEqualTo(ClprService.NAME);

        final Set<String> methodNames = clprService.rpcDefinitions().stream()
                .flatMap(def -> def.methods().stream())
                .map(RpcMethodDefinition::path)
                .collect(Collectors.toSet());
        assertThat(methodNames).contains("sync").doesNotContain("discoverEndpoints");
    }

    @Test
    @DisplayName("newPlatformStatus(ACTIVE) starts the CLPR runtime")
    void newPlatformStatusActiveStartsClprRuntime() {
        hedera.newPlatformStatus(PlatformStatus.ACTIVE);

        verify(clprRuntime).start();
        verify(clprRuntime, never()).stop();
    }

    @Test
    @DisplayName("newPlatformStatus(FREEZE_COMPLETE) stops the CLPR runtime")
    void newPlatformStatusFreezeCompleteStopsClprRuntime() {
        hedera.newPlatformStatus(PlatformStatus.FREEZE_COMPLETE);

        verify(clprRuntime).stop();
        verify(clprRuntime, never()).start();
    }

    @Test
    @DisplayName("newPlatformStatus(CATASTROPHIC_FAILURE) stops the CLPR runtime")
    void newPlatformStatusCatastrophicFailureStopsClprRuntime() {
        hedera.newPlatformStatus(PlatformStatus.CATASTROPHIC_FAILURE);

        verify(clprRuntime).stop();
        verify(clprRuntime, never()).start();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEV", "TEST", "PROD"})
    void respectsProfileBeforeApplyingTssOverride(final String profile) throws ReflectiveOperationException {
        final var configProvider = new ConfigProviderImpl(
                false,
                null,
                Map.of(
                        "hedera.profiles.active",
                        profile,
                        "tss.forceMockSignatures",
                        "true",
                        "tss.tssKeysPath",
                        tempDir.toAbsolutePath().toString()));
        setHederaField("configProvider", configProvider);
        final var config = configProvider.getConfiguration();
        final var hintsService = serviceOfType(HintsService.class);
        final var historyService = serviceOfType(HistoryService.class);
        clearInvocations(hintsService, historyService);
        final var state = mock(State.class);
        setHederaField("initState", state);

        final var activeHintsConstruction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .crsId(1L)
                .numParties(4)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        final var activeCrs = CRSState.newBuilder()
                .ceremonyId(1L)
                .numParties(4)
                .stage(CRSStage.COMPLETED)
                .crs(Bytes.wrap(new byte[304 + 4 * 288]))
                .build();
        final var proof = HistoryProof.DEFAULT;
        final var activeProofConstruction = HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .targetProof(proof)
                .build();
        final var importedBlsKey = Bytes.wrap("imported-bls-key");
        final var importedSchnorrKeys =
                new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("imported-private"), Bytes.wrap("imported-public"));
        final var network = Network.newBuilder()
                .tssMetadata(TssMetadata.newBuilder()
                        .activeHintsConstruction(activeHintsConstruction)
                        .crsState(activeCrs)
                        .activeProofConstruction(activeProofConstruction))
                .nodeTssMetadata(NodeTssMetadata.newBuilder()
                        .nodeId(0L)
                        .blsPrivateKey(importedBlsKey)
                        .schnorrPrivateKey(importedSchnorrKeys.privateKey())
                        .schnorrPublicKey(importedSchnorrKeys.publicKey())
                        .build())
                .build();
        final var existingBlsKey = Bytes.wrap("existing-bls-key");
        final var existingSchnorrKeys =
                new TssKeyFiles.SchnorrKeyPair(Bytes.wrap("existing-private"), Bytes.wrap("existing-public"));
        TssKeyFiles.writeBlsPrivateKey(config, 1L, existingBlsKey);
        TssKeyFiles.writeSchnorrKeyPair(config, 1L, existingSchnorrKeys);

        final var hintsStates = mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).defaultAnswer(RETURNS_DEEP_STUBS));
        final var historyStates = mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).defaultAnswer(RETURNS_DEEP_STUBS));
        if (!profile.equals("PROD")) {
            when(state.getWritableStates(HintsService.NAME)).thenReturn(hintsStates);
            when(state.getWritableStates(HistoryService.NAME)).thenReturn(historyStates);
            when(state.getReadableStates(HintsService.NAME)).thenReturn(hintsStates);
            when(state.getReadableStates(EntityIdService.NAME))
                    .thenReturn(mock(ReadableStates.class, RETURNS_DEEP_STUBS));
            when(hintsStates
                            .<CRSState>getSingleton(V060HintsSchema.CRS_STATE_STATE_ID)
                            .get())
                    .thenReturn(activeCrs);
        }

        final var onOverrideNetwork = Hedera.class.getDeclaredMethod("onOverrideNetwork", Network.class);
        onOverrideNetwork.setAccessible(true);
        onOverrideNetwork.invoke(hedera, network);

        if (profile.equals("PROD")) {
            verifyNoInteractions(state, hintsStates, historyStates, hintsService, historyService);
            assertThat(TssKeyFiles.readBlsPrivateKey(config, 1L)).contains(existingBlsKey);
            assertThat(TssKeyFiles.readSchnorrKeyPair(config, 1L)).contains(existingSchnorrKeys);
        } else {
            verify(hintsStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                    .put(activeHintsConstruction);
            verify(historyStates.<HistoryProofConstruction>getSingleton(
                            V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID))
                    .put(activeProofConstruction);
            verify((CommittableWritableStates) hintsStates).commit();
            verify((CommittableWritableStates) historyStates).commit();
            verify(hintsService).setActiveConstruction(activeHintsConstruction, activeCrs);
            verify(hintsService, never())
                    .setActiveConstruction(activeHintsConstruction, activeCrs, activeProofConstruction);
            verify(historyService).setLatestHistoryProof(proof);
            assertThat(TssKeyFiles.readBlsPrivateKey(config, 1L)).contains(importedBlsKey);
            assertThat(TssKeyFiles.readSchnorrKeyPair(config, 1L)).contains(importedSchnorrKeys);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartAdoptionRequiresAndForwardsTheSnapshotConsensusTime(final boolean missingTime) throws Exception {
        final var boundary = Instant.ofEpochSecond(1_800_000_000L, 456);
        final var roster = Roster.newBuilder()
                .rosterEntries(RosterEntry.newBuilder().nodeId(0L).weight(1L).build())
                .build();
        final var prepared = new PreparedTssHandoff(RosterUtils.hash(roster).getBytes());
        final var state = mock(State.class, RETURNS_DEEP_STUBS);
        final var writableStates = mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).defaultAnswer(RETURNS_DEEP_STUBS));
        when(state.getWritableStates(any())).thenReturn(writableStates);
        setHederaField("initState", state);
        setHederaField(
                "configProvider",
                new ConfigProviderImpl(false, null, Map.of("tss.hintsEnabled", "true", "tss.historyEnabled", "true")));
        final var hintsService = serviceOfType(HintsService.class);
        when(hintsService.handoff(any(), any(), any(), any(), any())).thenReturn(true);
        try (final var platformState = mockStatic(PlatformStateUtils.class);
                final var coordinator = mockStatic(TssHandoffCoordinator.class);
                final var readableHistory =
                        mockConstruction(ReadableHistoryStoreImpl.class, (store, _) -> prepared.stub(store));
                final var readableHints =
                        mockConstruction(ReadableHintsStoreImpl.class, (store, _) -> prepared.stub(store));
                final var writableHistory = mockConstruction(WritableHistoryStoreImpl.class, (store, _) -> {
                    prepared.stub(store);
                    when(store.handoff(any(), any(), any(), any())).thenReturn(true);
                });
                final var writableHints =
                        mockConstruction(WritableHintsStoreImpl.class, (store, _) -> prepared.stub(store))) {
            platformState
                    .when(() -> PlatformStateUtils.consensusTimestampOf(state))
                    .thenReturn(missingTime ? null : boundary);
            coordinator
                    .when(() -> TssHandoffCoordinator.preparedRosterHashForAdoption(any(), any(), any(), any()))
                    .thenCallRealMethod();
            coordinator
                    .when(() -> TssHandoffCoordinator.isReadyForHandoff(any(), any(), any(), anyBoolean()))
                    .thenCallRealMethod();
            coordinator
                    .when(() -> TssHandoffCoordinator.tryJointHandoff(
                            any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenCallRealMethod();
            coordinator
                    .when(() -> TssHandoffCoordinator.rebindActiveTargetsForAdoption(
                            any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(true);
            final var onAdopt = Hedera.class.getDeclaredMethod("onAdoptRoster", Roster.class, Roster.class);
            onAdopt.setAccessible(true);
            if (missingTime) {
                final var error =
                        assertThrows(InvocationTargetException.class, () -> onAdopt.invoke(hedera, roster, roster));
                assertThat(error.getCause()).isInstanceOf(NullPointerException.class);
                verify(writableHistory.constructed().getFirst(), never()).handoff(any(), any(), any(), any());
                verify((CommittableWritableStates) writableStates, never()).commit();
            } else {
                onAdopt.invoke(hedera, roster, roster);
                verify(writableHistory.constructed().getFirst())
                        .handoff(roster, roster, prepared.rosterHash(), boundary);
            }
            platformState.verify(() -> PlatformStateUtils.consensusTimestampOf(state));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartDefersCompletedBootstrapHintsUntilMatchingGenesisHistoryIsComplete(final boolean historyStarted)
            throws ReflectiveOperationException {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("bootstrap-roster"));
        final var active = prepared.next()
                .copyBuilder()
                .sourceRosterHash(prepared.rosterHash())
                .crsId(1L)
                .build();
        final var unfinishedHistory = historyStarted
                ? HistoryProofConstruction.newBuilder()
                        .constructionId(2L)
                        .sourceRosterHash(prepared.rosterHash())
                        .targetRosterHash(prepared.rosterHash())
                        .build()
                : HistoryProofConstruction.DEFAULT;
        final var hintsService = serviceOfType(HintsService.class);
        clearInvocations(hintsService);

        initializeHintsSigning(active, completedBootstrapCrs(), unfinishedHistory);

        verifyNoInteractions(hintsService);
    }

    @Test
    void restartDefersCompletedTransitionHintsUntilTheirHistoryIsComplete() throws ReflectiveOperationException {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("transition-target"));
        final var sourceHash = Bytes.wrap("transition-source");
        final var active = prepared.next()
                .copyBuilder()
                .sourceRosterHash(sourceHash)
                .crsId(1L)
                .build();
        final var unfinishedHistory = HistoryProofConstruction.newBuilder()
                .constructionId(3L)
                .sourceRosterHash(sourceHash)
                .targetRosterHash(prepared.rosterHash())
                .build();
        final var hintsService = serviceOfType(HintsService.class);
        clearInvocations(hintsService);

        initializeHintsSigning(active, completedBootstrapCrs(), unfinishedHistory);

        verifyNoInteractions(hintsService);
    }

    @Test
    void restartInitializesCompletedHintsWithItsExactCompletedHistorySnapshot() throws ReflectiveOperationException {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("bootstrap-roster"));
        final var active = prepared.next()
                .copyBuilder()
                .sourceRosterHash(prepared.rosterHash())
                .crsId(1L)
                .build();
        final var crs = completedBootstrapCrs();
        final var hintsService = serviceOfType(HintsService.class);
        clearInvocations(hintsService);
        doAnswer(invocation -> {
                    HintsGenerationWeights.from(invocation.getArgument(0), invocation.getArgument(2));
                    return null;
                })
                .when(hintsService)
                .setActiveConstruction(any(), any(), any());

        validateHintsSchema(active, crs);
        verifyNoInteractions(hintsService);
        initializeHintsSigning(active, crs, prepared.history());

        verify(hintsService).setActiveConstruction(active, crs, prepared.history());
        verify(hintsService, never()).setActiveConstruction(any(), any());
    }

    @Test
    void restartDefersMatchedCompletedLegacyHistoryUntilItsCanonicalSnapshotIsRecovered()
            throws ReflectiveOperationException {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("completed-roster"));
        final var legacyHistory = prepared.history()
                .copyBuilder()
                .targetProof(
                        prepared.history().targetProofOrThrow().copyBuilder().targetNodeWeights(List.of()))
                .build();
        final var hintsService = serviceOfType(HintsService.class);
        clearInvocations(hintsService);

        initializeHintsSigning(prepared.next(), completedBootstrapCrs(), legacyHistory);

        verifyNoInteractions(hintsService);
    }

    @Test
    void restartRejectsNonWrapsLegacyHistoryWithoutACanonicalSnapshot() {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("legacy-roster"));
        final var legacyHistory = prepared.history()
                .copyBuilder()
                .targetProof(prepared.history()
                        .targetProofOrThrow()
                        .copyBuilder()
                        .targetNodeWeights(List.of())
                        .uncompressedWrapsProof(Bytes.EMPTY))
                .build();
        final var hintsService = serviceOfType(HintsService.class);
        clearInvocations(hintsService);

        final var error = assertThrows(
                InvocationTargetException.class,
                () -> initializeHintsSigning(prepared.next(), completedBootstrapCrs(), legacyHistory));

        assertThat(error.getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy proof is not WRAPS-extensible")
                .hasMessageContaining("restore a verified snapshot or reset TSS");
        verifyNoInteractions(hintsService);
    }

    @Test
    void legacyNonWrapsStateRestartsWithRealSigningAfterAFreshGenesisWrapsProof() throws ReflectiveOperationException {
        final var rosterHash = Bytes.wrap("legacy-roster");
        final var roster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(0L).weight(5L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(3L).build(),
                RosterEntry.newBuilder().nodeId(2L).weight(2L).build()));
        final var verificationKey = Bytes.wrap("active-key");
        // Node 2 holds no party in the active scheme
        final var activeHints = HintsConstruction.newBuilder()
                .constructionId(1L)
                .crsId(1L)
                .numParties(8)
                .sourceRosterHash(rosterHash)
                .targetRosterHash(rosterHash)
                .hintsScheme(new HintsScheme(
                        new PreprocessedKeys(Bytes.wrap("aggregation-key"), verificationKey),
                        List.of(new NodePartyId(0L, 1, 5L), new NodePartyId(1L, 2, 3L))))
                .build();
        final var crs = completedBootstrapCrs();
        final var legacyHistory = HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .sourceRosterHash(Bytes.wrap("previous-roster"))
                .targetRosterHash(rosterHash)
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(new History(Bytes.wrap("legacy-book-hash"), verificationKey)))
                .build();
        final var hintsService = serviceOfType(HintsService.class);
        doAnswer(invocation -> {
                    HintsGenerationWeights.from(invocation.getArgument(0), invocation.getArgument(2));
                    return null;
                })
                .when(hintsService)
                .setActiveConstruction(any(), any(), any());

        // Real signing refuses to start from the legacy proof
        final var refused = assertThrows(
                InvocationTargetException.class, () -> initializeHintsSigning(activeHints, crs, legacyHistory));
        assertThat(refused.getCause()).isInstanceOf(IllegalStateException.class);

        // The upgrade that requests a fresh genesis proof also forces mock signatures, and that does start
        clearInvocations(hintsService);
        initializeHintsSigning(activeHints, crs, legacyHistory, true, true);
        verify(hintsService).setActiveConstruction(activeHints, crs);

        // The fresh genesis proof grounds a new chain of trust in the same roster and hinTS key
        final var historyStore = historyStoreWith(legacyHistory);
        final var rosters = mock(ActiveRosters.class);
        when(rosters.phase()).thenReturn(BOOTSTRAP);
        when(rosters.sourceRosterHash()).thenReturn(rosterHash);
        when(rosters.targetRosterHash()).thenReturn(rosterHash);
        when(rosters.findRelatedRoster(rosterHash)).thenReturn(roster);
        final var tssConfig = HederaTestConfigBuilder.createConfig().getConfigData(TssConfig.class);
        final var fresh =
                historyStore.getOrCreateConstruction(rosters, Instant.EPOCH, tssConfig, true, verificationKey);
        assertThat(fresh.constructionId()).isNotEqualTo(legacyHistory.constructionId());
        assertThat(groundsChainOfTrust(fresh)).isTrue();
        final var rosterWeights = new TreeMap<Long, Long>();
        roster.rosterEntries().forEach(entry -> rosterWeights.put(entry.nodeId(), entry.weight()));
        // The same fields the WRAPS prover sets on the proof it votes for
        historyStore.completeProof(
                fresh.constructionId(),
                HistoryProof.newBuilder()
                        .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(rosterWeights))
                        .targetHistory(new History(Bytes.wrap("fresh-book-hash"), verificationKey))
                        .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("compressed")))
                        .uncompressedWrapsProof(Bytes.wrap("uncompressed"))
                        .build());
        assertThat(historyStore.handoff(roster, null, null, null)).isTrue();
        final var freshHistory = historyStore.getActiveConstruction();
        assertThat(freshHistory.constructionId()).isEqualTo(fresh.constructionId());

        // With mock signatures off again, the node starts and signs against the full roster weight
        clearInvocations(hintsService);
        initializeHintsSigning(activeHints, crs, freshHistory);
        verify(hintsService).setActiveConstruction(activeHints, crs, freshHistory);
        final var generation = HintsGenerationWeights.from(activeHints, freshHistory);
        assertThat(generation.totalWeight()).isEqualTo(10L);
        assertThat(generation.schemeWeight()).isEqualTo(8L);
    }

    private static WritableHistoryStoreImpl historyStoreWith(final HistoryProofConstruction active) {
        return new WritableHistoryStoreImpl(new MapWritableStates(Map.of(
                V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID,
                mutableSingleton(V071HistorySchema.ACTIVE_PROOF_CONSTRUCTION_STATE_ID, active),
                V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID,
                mutableSingleton(V071HistorySchema.NEXT_PROOF_CONSTRUCTION_STATE_ID, HistoryProofConstruction.DEFAULT),
                V071HistorySchema.LEDGER_ID_STATE_ID,
                mutableSingleton(V071HistorySchema.LEDGER_ID_STATE_ID, new ProtoBytes(Bytes.wrap("ledger"))),
                V071HistorySchema.PROOF_KEY_SETS_STATE_ID,
                new MapWritableKVState<>(V071HistorySchema.PROOF_KEY_SETS_STATE_ID, "proofKeys"),
                V071HistorySchema.PROOF_VOTES_STATE_ID,
                new MapWritableKVState<>(V071HistorySchema.PROOF_VOTES_STATE_ID, "votes"),
                V071HistorySchema.WRAPS_MESSAGE_HISTORIES_STATE_ID,
                new MapWritableKVState<>(V071HistorySchema.WRAPS_MESSAGE_HISTORIES_STATE_ID, "messages"),
                V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID,
                ListWritableQueueState.<HintsKeyProvenance>builder(
                                V0790HistorySchema.HINTS_KEY_PROVENANCE_STATE_ID, "provenance")
                        .build(),
                V0800HistorySchema.HINTS_KEY_WITNESSES_STATE_ID,
                new MapWritableKVState<ProtoLong, HintsKeyWitnessRecord>(
                        V0800HistorySchema.HINTS_KEY_WITNESSES_STATE_ID, "witnesses"))));
    }

    private static <T> FunctionWritableSingletonState<T> mutableSingleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "HistoryService." + id, value::get, value::set);
    }

    @Test
    void restartRejectsCompletedHistoryFromAnotherGenerationWithoutSchemeOnlyFallback() {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("bootstrap-roster"));
        final var active = prepared.next()
                .copyBuilder()
                .sourceRosterHash(prepared.rosterHash())
                .crsId(1L)
                .build();
        final var mismatchedHistory = prepared.history()
                .copyBuilder()
                .targetRosterHash(Bytes.wrap("different-generation"))
                .build();
        final var hintsService = serviceOfType(HintsService.class);
        final var crs = completedBootstrapCrs();
        clearInvocations(hintsService);
        doAnswer(invocation -> {
                    HintsGenerationWeights.from(invocation.getArgument(0), invocation.getArgument(2));
                    return null;
                })
                .when(hintsService)
                .setActiveConstruction(any(), any(), any());

        final var error = assertThrows(
                InvocationTargetException.class, () -> initializeHintsSigning(active, crs, mismatchedHistory));

        assertThat(error.getCause()).isInstanceOf(IllegalArgumentException.class);
        verify(hintsService).setActiveConstruction(active, crs, mismatchedHistory);
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, true"})
    void restoresSchemeSigningInCompatibleConfigurations(final boolean historyEnabled, final boolean forceMock)
            throws ReflectiveOperationException {
        final var prepared = new PreparedTssHandoff(Bytes.wrap("compatible-roster"));
        final var active = prepared.next().copyBuilder().crsId(1L).build();
        final var crs = completedBootstrapCrs();
        final var hintsService = serviceOfType(HintsService.class);
        clearInvocations(hintsService);

        validateHintsSchema(active, crs);
        verifyNoInteractions(hintsService);
        initializeHintsSigning(active, crs, HistoryProofConstruction.DEFAULT, historyEnabled, forceMock);

        verify(hintsService).setActiveConstruction(active, crs);
        verify(hintsService, never()).setActiveConstruction(any(), any(), any());
    }

    private static void validateHintsSchema(final HintsConstruction active, final CRSState crs) {
        final var library = mock(HintsLibrary.class);
        when(library.crsPartySize(crs.crs())).thenReturn(crs.numParties());
        final var migration = mock(MigrationContext.class);
        when(migration.newStates())
                .thenReturn(new MapWritableStates(Map.of(
                        V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID,
                        unmodifiedSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID, active),
                        V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID,
                        unmodifiedSingleton(
                                V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID, HintsConstruction.DEFAULT),
                        V060HintsSchema.CRS_STATE_STATE_ID,
                        unmodifiedSingleton(V060HintsSchema.CRS_STATE_STATE_ID, crs),
                        V079HintsSchema.NEXT_CRS_STATE_ID,
                        unmodifiedSingleton(V079HintsSchema.NEXT_CRS_STATE_ID, CRSState.DEFAULT))));

        new V079HintsSchema(library).restart(migration);
    }

    private static <T> FunctionWritableSingletonState<T> unmodifiedSingleton(final int id, final T value) {
        return new FunctionWritableSingletonState<>(id, "HintsService." + id, () -> value, _ -> {
            throw new AssertionError("Validating a complete hinTS schema must not change it");
        });
    }

    private static CRSState completedBootstrapCrs() {
        return CRSState.newBuilder()
                .ceremonyId(1L)
                .numParties(8)
                .stage(CRSStage.COMPLETED)
                .crs(Bytes.wrap(new byte[304 + 288 * 8]))
                .build();
    }

    private void initializeHintsSigning(
            final HintsConstruction active, final CRSState crs, final HistoryProofConstruction history)
            throws ReflectiveOperationException {
        initializeHintsSigning(active, crs, history, true, false);
    }

    private void initializeHintsSigning(
            final HintsConstruction active,
            final CRSState crs,
            final HistoryProofConstruction history,
            final boolean historyEnabled,
            final boolean forceMock)
            throws ReflectiveOperationException {
        setHederaField(
                "configProvider",
                new ConfigProviderImpl(
                        false,
                        null,
                        Map.of(
                                "tss.hintsEnabled", "true",
                                "tss.historyEnabled", Boolean.toString(historyEnabled),
                                "tss.forceMockSignatures", Boolean.toString(forceMock))));
        final var state = mock(State.class, RETURNS_DEEP_STUBS);
        try (final var hintsStores = mockConstruction(ReadableHintsStoreImpl.class, (store, _) -> {
                    when(store.getActiveConstruction()).thenReturn(active);
                    when(store.getCrsStateFor(active)).thenReturn(crs);
                });
                final var historyStores = mockConstruction(
                        ReadableHistoryStoreImpl.class,
                        (store, _) -> when(store.getActiveConstruction()).thenReturn(history))) {
            final var initialize = Hedera.class.getDeclaredMethod("initializeHintsSigning", State.class);
            initialize.setAccessible(true);
            initialize.invoke(hedera, state);
            if (!historyEnabled || forceMock) {
                assertThat(historyStores.constructed()).isEmpty();
            }
        }
    }

    // As the Hedera class is not directly accessible for testing, this method provides a way to set private fields.
    // Although this is not ideal, it is the only way to access private fields and configure them in a test environment.
    private void setHederaField(final String name, final Object value) {
        try {
            final Field field = Hedera.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(hedera, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set field '" + name + "' on Hedera", e);
        }
    }

    private <T extends Service> T serviceOfType(final Class<T> type) {
        return servicesRegistry.registrations().stream()
                .map(ServicesRegistry.Registration::service)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No service of type " + type.getSimpleName() + " registered"));
    }
}
