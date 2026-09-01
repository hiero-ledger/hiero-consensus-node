// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.clpr.ClprEndpointPublicationTransactionBody;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestConstructionStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprEndpointPublicationHandler;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The publication IS the trigger. The handler admits into an active construction, or —
 * when none is in flight and the published endpoint is not already in the manifest — opens one.
 * Publications are matched to the manifest by full-value membership.
 */
@ExtendWith(MockitoExtension.class)
class ClprEndpointPublicationHandlerTest {

    private static final long PUBLISHER_NODE_ID = 7L;
    private static final long PEER_NODE_ID = 8L;
    private static final ClprEndpoint ENDPOINT = ClprEndpoint.newBuilder()
            .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                    .ipAddress("127.0.0.1")
                    .port(50211)
                    .build())
            .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3, 4}))
            .build();

    @Mock
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableEndpointManifestConstructionStore constructionStore;

    @Mock
    private ReadableEndpointManifestStore manifestStore;

    @Mock
    private ReadableRosterStore rosterStore;

    private ClprEndpointPublicationHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ClprEndpointPublicationHandler();
    }

    // ---- pureChecks ----

    @Test
    @DisplayName("pureChecks accepts endpoint variant")
    void pureChecksAcceptsEndpointVariant() {
        given(pureChecksContext.body()).willReturn(bodyWithEndpoint());
        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pureChecks rejects when neither oneof variant is set")
    void pureChecksRejectsWhenNeitherSet() {
        given(pureChecksContext.body()).willReturn(bodyWithUnsetOneof());
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    // ---- handle ----

    @Test
    @DisplayName("handle throws CLPR_NOT_ENABLED when clpr.enabled=false")
    void handleRejectsWhenDisabled() {
        setupHandleContext(bodyWithEndpoint(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("C: admits into an active construction (no account_id cross-check)")
    void handleAdmitsIntoActiveConstruction() {
        setupHandleContext(bodyWithEndpoint(), true);
        given(constructionStore.get()).willReturn(constructionTargeting(PUBLISHER_NODE_ID, PEER_NODE_ID));
        given(constructionStore.admitPublication(eq(PUBLISHER_NODE_ID), any(ClprEndpointPublication.class)))
                .willReturn(true);

        subject.handle(handleContext);

        verify(constructionStore).admitPublication(eq(PUBLISHER_NODE_ID), any(ClprEndpointPublication.class));
        verify(constructionStore, never()).put(any());
    }

    @Test
    @DisplayName("C: opens a construction when the published endpoint is not already in the manifest")
    void handleOpensConstructionOnDifferingEndpoint() {
        setupHandleContext(bodyWithEndpoint(), true);
        given(constructionStore.get()).willReturn(null);
        given(manifestStore.get()).willReturn(manifest(1L)); // genesis seed: empty
        given(rosterStore.getActiveRoster()).willReturn(rosterOf(PUBLISHER_NODE_ID, PEER_NODE_ID));

        subject.handle(handleContext);

        final var captor = ArgumentCaptor.forClass(ClprEndpointManifestConstruction.class);
        verify(constructionStore).put(captor.capture());
        final var opened = captor.getValue();
        assertThat(opened.constructionId()).isEqualTo(2L); // manifest.version() + 1
        assertThat(opened.targetNodeIds()).containsExactly(PUBLISHER_NODE_ID, PEER_NODE_ID);
        assertThat(opened.gatheredPublications()).hasSize(1);
        assertThat(opened.gatheredPublications().getFirst().nodeId()).isEqualTo(PUBLISHER_NODE_ID);
        assertThat(opened.gatheredPublications().getFirst().publicationOrThrow().endpointOrThrow())
                .isEqualTo(ENDPOINT);
    }

    @Test
    @DisplayName("C: does NOT open when the manifest already contains the exact endpoint")
    void handleDoesNotOpenWhenEndpointAlreadyPresent() {
        setupHandleContext(bodyWithEndpoint(), true);
        given(constructionStore.get()).willReturn(null);
        given(manifestStore.get()).willReturn(manifest(3L, ENDPOINT));

        subject.handle(handleContext);

        verify(constructionStore, never()).put(any());
        verify(constructionStore, never()).admitPublication(anyLong(), any());
    }

    @Test
    @DisplayName("C: does NOT open for a publisher outside the active roster")
    void handleDoesNotOpenForNonRosterPublisher() {
        setupHandleContext(bodyWithEndpoint(), true);
        given(constructionStore.get()).willReturn(null);
        given(manifestStore.get()).willReturn(manifest(1L));
        given(rosterStore.getActiveRoster()).willReturn(rosterOf(PEER_NODE_ID)); // publisher 7 not present

        subject.handle(handleContext);

        verify(constructionStore, never()).put(any());
    }

    // ---- helpers ----

    private void setupHandleContext(final TransactionBody body, final boolean enabled) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", enabled ? "true" : "false")
                .withValue("clpr.endpointManifestEnabled", enabled ? "true" : "false")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        lenient().when(handleContext.body()).thenReturn(body);
        lenient().when(handleContext.creatorInfo()).thenReturn(creatorInfo);
        lenient().when(creatorInfo.nodeId()).thenReturn(PUBLISHER_NODE_ID);
        lenient().when(handleContext.consensusNow()).thenReturn(Instant.parse("2026-07-30T12:00:00Z"));
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritableEndpointManifestConstructionStore.class))
                .thenReturn(constructionStore);
        lenient()
                .when(storeFactory.readableStore(ReadableEndpointManifestStore.class))
                .thenReturn(manifestStore);
        lenient().when(storeFactory.readableStore(ReadableRosterStore.class)).thenReturn(rosterStore);
    }

    private static ClprEndpointManifestConstruction constructionTargeting(final long... nodeIds) {
        final var ids = java.util.Arrays.stream(nodeIds).boxed().toList();
        return ClprEndpointManifestConstruction.newBuilder()
                .constructionId(1L)
                .targetNodeIds(ids)
                .gatheredPublications(List.of())
                .build();
    }

    private static ClprEndpointManifest manifest(final long version, final ClprEndpoint... endpoints) {
        return ClprEndpointManifest.newBuilder()
                .version(version)
                .endpoints(List.of(endpoints))
                .build();
    }

    private static Roster rosterOf(final long... nodeIds) {
        final var entries = java.util.Arrays.stream(nodeIds)
                .mapToObj(id -> RosterEntry.newBuilder().nodeId(id).weight(1).build())
                .toList();
        return Roster.newBuilder().rosterEntries(entries).build();
    }

    private static TransactionBody bodyWithEndpoint() {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .clprEndpointPublication(ClprEndpointPublicationTransactionBody.newBuilder()
                        .endpoint(ENDPOINT)
                        .build())
                .build();
    }

    private static TransactionBody bodyWithUnsetOneof() {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .clprEndpointPublication(
                        ClprEndpointPublicationTransactionBody.newBuilder().build())
                .build();
    }
}
