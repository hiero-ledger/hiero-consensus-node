// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublicationEntry;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestConstructionStore;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestStore;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Reconciler with startup-gated self-publication. Two responsibilities:
 * <ul>
 *   <li>{@link ClprEndpointManifestReconciler#openConstructionIfSelfChanged} — publish this node's own current
 *       endpoint when the manifest doesn't already contain it (full-value membership), returning
 *       whether it is now "settled". Called by HandleWorkflow only until settled — not every round.</li>
 *   <li>{@link ClprEndpointManifestReconciler#reconcile} — drive an in-flight construction to close.
 *       A no-op when no construction is gathering.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ClprEndpointManifestReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    @Mock
    private ClprSubmissions submissions;

    @Mock
    private WritableEndpointManifestStore manifestStore;

    @Mock
    private WritableEndpointManifestConstructionStore constructionStore;

    @Mock
    private ReadableNodeStore nodeStore;

    @Mock
    private ClprCaCertManager caCertManager;

    private ClprEndpointManifestReconciler subject;
    private ClprConfig config;

    @BeforeEach
    void setUp() {
        // mTLS off by default (isMtlsEnabled() == false): self endpoints use the HAPI port + empty cert.
        subject = new ClprEndpointManifestReconciler(submissions, caCertManager);
        config = ClprConfigBuilder.newBuilder().build();
    }

    @Nested
    @DisplayName("openConstructionIfSelfChanged (startup-gated self-publication)")
    class PublishSelfIfChanged {

        @Test
        @DisplayName("endpoint absent + no construction in flight → publishes it and reports NOT settled")
        void publishesWhenAbsentAndNoConstruction() {
            stubNode(1L, "host-1", 50211);

            final var settled =
                    subject.openConstructionIfSelfChanged(1L, manifest(1L, List.of()), null, nodeStore, config, NOW);

            assertThat(settled).isEqualTo(ClprEndpointManifestReconciler.SelfPublishOutcome.PENDING);
            verify(submissions).submitEndpointPublication(selfEndpoint(1L, "host-1", 50211));
        }

        @Test
        @DisplayName("endpoint absent but a construction IS in flight → publishes nothing (it fills that instead)")
        void doesNotPublishWhenConstructionInFlight() {
            stubNode(1L, "host-1", 50211);
            final var inFlight = constructionGathering(List.of(1L, 2L), List.of(), NOW.plusSeconds(600));

            final var settled = subject.openConstructionIfSelfChanged(
                    1L, manifest(1L, List.of()), inFlight, nodeStore, config, NOW);

            assertThat(settled).isEqualTo(ClprEndpointManifestReconciler.SelfPublishOutcome.PENDING);
            verify(submissions, never()).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("self endpoint already in manifest → publishes nothing and reports settled")
        void settledWhenSelfEndpointInManifest() {
            stubNode(1L, "host-1", 50211);

            final var settled = subject.openConstructionIfSelfChanged(
                    1L, manifest(2L, List.of(selfEndpoint(1L, "host-1", 50211))), null, nodeStore, config, NOW);

            assertThat(settled).isEqualTo(ClprEndpointManifestReconciler.SelfPublishOutcome.SETTLED);
            verify(submissions, never()).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("retry backoff: publishes once when re-called within the retry delay")
        void publishesOnceWithinRetryDelay() {
            stubNode(1L, "host-1", 50211);

            // Two rounds with the endpoint still absent and no construction gathering, the second just inside
            // the retry delay → the opening-publication backoff suppresses the duplicate submit.
            subject.openConstructionIfSelfChanged(1L, manifest(1L, List.of()), null, nodeStore, config, NOW);
            subject.openConstructionIfSelfChanged(
                    1L,
                    manifest(1L, List.of()),
                    null,
                    nodeStore,
                    config,
                    NOW.plus(config.manifestSubmissionRetryDelay()).minusMillis(1));

            verify(submissions, times(1)).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("retry backoff: re-publishes once the retry delay elapses and no construction has opened")
        void rePublishesAfterRetryDelay() {
            stubNode(1L, "host-1", 50211);

            // The first submit never reached consensus (endpoint still absent, no construction gathering); once
            // the retry delay elapses a fresh opening publication is issued rather than the node being suppressed.
            subject.openConstructionIfSelfChanged(1L, manifest(1L, List.of()), null, nodeStore, config, NOW);
            subject.openConstructionIfSelfChanged(
                    1L,
                    manifest(1L, List.of()),
                    null,
                    nodeStore,
                    config,
                    NOW.plus(config.manifestSubmissionRetryDelay()).plusSeconds(1));

            verify(submissions, times(2)).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("no address-book service endpoint → nothing published, reports NOT settled (retry)")
        void notSettledWhenSelfEndpointNotBuildable() {
            // nodeStore.get(1) returns null (unstubbed) → buildFor returns null.
            final var settled =
                    subject.openConstructionIfSelfChanged(1L, manifest(1L, List.of()), null, nodeStore, config, NOW);

            assertThat(settled).isEqualTo(ClprEndpointManifestReconciler.SelfPublishOutcome.PENDING);
            verify(submissions, never()).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("settled wrapper re-checks when restored state replaces the manifest")
        void rechecksAfterSettledManifestIsReplaced() {
            stubNode(1L, "host-1", 50211);
            final var settledManifest = manifest(2L, List.of(selfEndpoint(1L, "host-1", 50211)));

            subject.openConstructionIfSelfChangedUntilSettled(1L, settledManifest, null, nodeStore, config, NOW);
            subject.openConstructionIfSelfChangedUntilSettled(
                    1L, settledManifest, null, nodeStore, config, NOW.plusSeconds(1));
            verify(submissions, never()).submitEndpointPublication(any());

            subject.openConstructionIfSelfChangedUntilSettled(
                    1L, manifest(1L, List.of()), null, nodeStore, config, NOW.plusSeconds(2));

            verify(submissions).submitEndpointPublication(selfEndpoint(1L, "host-1", 50211));
        }
    }

    @Nested
    @DisplayName("contributeSelfToConstruction (fill an open construction; makes it an all-hands snapshot)")
    class PublishSelfIntoConstruction {

        @Test
        @DisplayName("open construction targets self and self not yet gathered → publishes self endpoint")
        void publishesIntoOpenConstructionWhenNotGathered() {
            stubNode(1L, "host-1", 50211);
            final var construction = constructionGathering(List.of(1L, 2L), List.of(), NOW.plusSeconds(600));

            subject.contributeSelfToConstruction(1L, construction, nodeStore, config, NOW);

            verify(submissions).submitEndpointPublication(selfEndpoint(1L, "host-1", 50211));
        }

        @Test
        @DisplayName("self already gathered in the construction → publishes nothing")
        void doesNotPublishIntoConstructionWhenAlreadyGathered() {
            final var ep = endpoint("host-1", 50211, new byte[] {});
            final var construction =
                    constructionGathering(List.of(1L, 2L), List.of(gathered(1L, ep)), NOW.plusSeconds(600));

            subject.contributeSelfToConstruction(1L, construction, nodeStore, config, NOW);

            verify(submissions, never()).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("no construction in flight → contributeSelfToConstruction is a no-op")
        void doesNotPublishIntoConstructionWhenNone() {
            subject.contributeSelfToConstruction(1L, null, nodeStore, config, NOW);

            verify(submissions, never()).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("self not a target of the construction → publishes nothing")
        void doesNotPublishIntoConstructionWhenNotTarget() {
            final var construction = constructionGathering(List.of(2L, 3L), List.of(), NOW.plusSeconds(600));

            subject.contributeSelfToConstruction(1L, construction, nodeStore, config, NOW);

            verify(submissions, never()).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("retry backoff: publishes once when re-called within the retry delay")
        void publishesOnceWithinRetryDelay() {
            stubNode(1L, "host-1", 50211);
            final var construction = constructionGathering(List.of(1L, 2L), List.of(), NOW.plusSeconds(600));

            // Same consensus time (and one just inside the retry delay) → the backoff suppresses the second attempt.
            subject.contributeSelfToConstruction(1L, construction, nodeStore, config, NOW);
            subject.contributeSelfToConstruction(
                    1L,
                    construction,
                    nodeStore,
                    config,
                    NOW.plus(config.manifestSubmissionRetryDelay()).minusMillis(1));

            verify(submissions, times(1)).submitEndpointPublication(any());
        }

        @Test
        @DisplayName("retry backoff: re-publishes once the retry delay elapses and the publication has not landed")
        void rePublishesAfterRetryDelay() {
            stubNode(1L, "host-1", 50211);
            final var construction = constructionGathering(List.of(1L, 2L), List.of(), NOW.plusSeconds(600));

            // The first submission never reached consensus (self still absent from gatheredPublications); once the
            // retry delay elapses, a fresh submission is issued rather than the node being permanently suppressed.
            subject.contributeSelfToConstruction(1L, construction, nodeStore, config, NOW);
            subject.contributeSelfToConstruction(
                    1L,
                    construction,
                    nodeStore,
                    config,
                    NOW.plus(config.manifestSubmissionRetryDelay()).plusSeconds(1));

            verify(submissions, times(2)).submitEndpointPublication(any());
        }
    }

    @Nested
    @DisplayName("reconcile (drive construction close; account-id-free, sorted by IP identity)")
    class Reconcile {

        @Test
        @DisplayName("no construction in flight → reconcile is a no-op (no state writes)")
        void reconcileNoOpWhenNoConstruction() {
            given(constructionStore.get()).willReturn(null);

            reconcile();

            verify(manifestStore, never()).put(any());
            verify(constructionStore, never()).put(any());
            verify(constructionStore, never()).clear();
        }

        @Test
        @DisplayName("fast close advances the manifest with gathered endpoints sorted by IP identity")
        void fastCloseAdvancesManifest() {
            final var ep1 = endpoint("10.0.0.1", 50211, new byte[] {1});
            final var ep2 = endpoint("10.0.0.2", 50211, new byte[] {2});
            given(constructionStore.get())
                    .willReturn(constructionGathering(
                            List.of(1L, 2L),
                            List.of(gathered(1L, ep2), gathered(2L, ep1)), // gathered out of order
                            NOW.plusSeconds(600)));
            given(manifestStore.get()).willReturn(manifest(1L, List.of()));

            reconcile();

            final var written = captureWrittenManifest();
            assertThat(written.version()).isEqualTo(2L);
            assertThat(written.endpoints()).containsExactly(ep1, ep2); // 10.0.0.1 before 10.0.0.2
            verify(constructionStore).clear();
        }

        @Test
        @DisplayName("no-op close: gathered endpoints already match the manifest → version unchanged")
        void noOpCloseKeepsVersion() {
            final var ep = endpoint("10.0.0.1", 50211, new byte[] {1});
            given(constructionStore.get())
                    .willReturn(constructionGathering(List.of(1L), List.of(gathered(1L, ep)), NOW.plusSeconds(600)));
            given(manifestStore.get()).willReturn(manifest(5L, List.of(ep)));

            reconcile();

            final var written = captureWrittenManifest();
            assertThat(written.version()).isEqualTo(5L);
            assertThat(written.endpoints()).containsExactly(ep);
            verify(constructionStore).clear();
        }

        @Test
        @DisplayName("grace expired with budget available → extends, does not close")
        void graceExpiredExtends() {
            given(constructionStore.get())
                    .willReturn(constructionGathering(List.of(1L, 2L), List.of(), NOW.minusSeconds(10)));
            given(manifestStore.get()).willReturn(manifest(2L, List.of()));

            reconcile();

            final var captor = ArgumentCaptor.forClass(ClprEndpointManifestConstruction.class);
            verify(constructionStore).put(captor.capture());
            assertThat(captor.getValue().graceExtensionsUsed()).isEqualTo(1);
            verify(manifestStore, never()).put(any());
            verify(constructionStore, never()).clear();
        }

        @Test
        @DisplayName("forced close drops a silent node — no carry-over; only publishers are in the manifest")
        void forcedCloseDropsSilentNode() {
            // Node 2 is silent (never published before the grace window closed). With carry-over removed,
            // it is simply not represented; it re-adds itself when it next self-publishes.
            final var priorForNode2 = endpoint("host-2", 50211, new byte[] {9});
            final var freshForNode1 = endpoint("host-1", 50211, new byte[] {1});
            given(constructionStore.get())
                    .willReturn(construction(
                            List.of(1L, 2L),
                            List.of(gathered(1L, freshForNode1)), // only node 1 published
                            NOW.minusSeconds(10),
                            config.manifestMaxGraceExtensions())); // extensions exhausted → force close
            given(manifestStore.get()).willReturn(manifest(4L, List.of(priorForNode2)));

            reconcile();

            final var written = captureWrittenManifest();
            assertThat(written.endpoints()).containsExactly(freshForNode1); // node 2 dropped, not carried over
            verify(constructionStore).clear();
        }
    }

    @Nested
    @DisplayName("openConstructionOnRosterChange (post-upgrade prune/add by composition)")
    class OpenConstructionOnRosterChange {

        @Test
        @DisplayName("node removed from roster (orphan in manifest) → opens a construction to prune")
        void opensConstructionOnDelete() {
            stubNode(1L, "host-1", 50211); // roster = {1}; node 2 was removed
            given(constructionStore.get()).willReturn(null);
            given(manifestStore.get())
                    .willReturn(manifest(
                            2L,
                            List.of(
                                    endpoint("host-1", 50211, new byte[] {1}),
                                    endpoint("host-2", 50211, new byte[] {2})))); // host-2 orphan

            subject.openConstructionOnRosterChange(
                    NOW, rosterOf(1L), manifestStore, constructionStore, nodeStore, config);

            final var opened = captureOpenedConstruction();
            assertThat(opened.constructionId()).isEqualTo(3L); // version + 1
            assertThat(opened.targetNodeIds()).containsExactly(1L);
        }

        @Test
        @DisplayName("node added to roster (missing from manifest) → opens a construction")
        void opensConstructionOnAdd() {
            stubNode(1L, "host-1", 50211);
            stubNode(2L, "host-2", 50211);
            given(constructionStore.get()).willReturn(null);
            given(manifestStore.get()).willReturn(manifest(4L, List.of(endpoint("host-1", 50211, new byte[] {1}))));

            subject.openConstructionOnRosterChange(
                    NOW, rosterOf(1L, 2L), manifestStore, constructionStore, nodeStore, config);

            final var opened = captureOpenedConstruction();
            assertThat(opened.targetNodeIds()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("shared-IP node removed → detected via IP counts (a set would miss it)")
        void opensConstructionWhenSharedIpNodeRemoved() {
            stubNode(1L, "host-shared", 50211); // roster = {1}; node 2 shared this IP and was removed
            given(constructionStore.get()).willReturn(null);
            // Manifest still holds both nodes' endpoints on the shared IP → counts {host-shared: 2} vs roster
            // {host-shared:
            // 1}.
            given(manifestStore.get())
                    .willReturn(manifest(
                            2L,
                            List.of(
                                    endpoint("host-shared", 50211, new byte[] {1}),
                                    endpoint("host-shared", 50211, new byte[] {2}))));

            subject.openConstructionOnRosterChange(
                    NOW, rosterOf(1L), manifestStore, constructionStore, nodeStore, config);

            final var opened = captureOpenedConstruction();
            assertThat(opened.targetNodeIds()).containsExactly(1L); // node 2 pruned even though it shared node 1's IP
        }

        @Test
        @DisplayName(
                "roster composition unchanged (same IP-set) → opens nothing, leaves any in-flight construction alone")
        void noConstructionWhenCompositionUnchanged() {
            stubNode(1L, "host-1", 50211);
            given(manifestStore.get()).willReturn(manifest(2L, List.of(endpoint("host-1", 50211, new byte[] {1}))));

            subject.openConstructionOnRosterChange(
                    NOW, rosterOf(1L), manifestStore, constructionStore, nodeStore, config);

            verify(constructionStore, never()).put(any());
        }

        @Test
        @DisplayName(
                "in-flight construction + composition changed → replaces it (fresh target set, cleared publications)")
        void inFlightCompositionChangedReplacesConstruction() {
            stubNode(1L, "host-1", 50211); // roster = {1}; node 2 was removed during the upgrade
            // Stale in-flight construction still targets {1,2} and holds node 2's old publication.
            final var stale = constructionGathering(
                    List.of(1L, 2L),
                    List.of(gathered(2L, endpoint("host-2", 50211, new byte[] {2}))),
                    NOW.plusSeconds(600));
            given(constructionStore.get()).willReturn(stale);
            given(manifestStore.get())
                    .willReturn(manifest(
                            2L,
                            List.of(endpoint("host-1", 50211, new byte[] {1}), endpoint("host-2", 50211, new byte[] {2
                            }))));

            subject.openConstructionOnRosterChange(
                    NOW, rosterOf(1L), manifestStore, constructionStore, nodeStore, config);

            final var opened = captureOpenedConstruction();
            assertThat(opened.constructionId()).isEqualTo(stale.constructionId() + 1); // bumped past the replaced one
            assertThat(opened.targetNodeIds()).containsExactly(1L); // node 2 pruned from the target set
            assertThat(opened.gatheredPublications()).isEmpty(); // node 2's stale publication cleared
            assertThat(opened.graceExtensionsUsed()).isZero(); // grace reset
        }
    }

    // ---- helpers ----

    private ClprEndpointManifestConstruction captureOpenedConstruction() {
        final var captor = ArgumentCaptor.forClass(ClprEndpointManifestConstruction.class);
        verify(constructionStore).put(captor.capture());
        return captor.getValue();
    }

    private static Roster rosterOf(final long... nodeIds) {
        final var entries = new java.util.ArrayList<RosterEntry>();
        for (final long id : nodeIds) {
            entries.add(RosterEntry.newBuilder().nodeId(id).weight(1).build());
        }
        return Roster.newBuilder().rosterEntries(entries).build();
    }

    private void reconcile() {
        subject.reconcile(NOW, manifestStore, constructionStore, config);
    }

    private ClprEndpointManifest captureWrittenManifest() {
        final var captor = ArgumentCaptor.forClass(ClprEndpointManifest.class);
        verify(manifestStore).put(captor.capture());
        return captor.getValue();
    }

    /** Stub a full address-book node so {@code ClprEndpointBuilder.buildFor}/{@code ipAddressOf} work. */
    private void stubNode(final long nodeId, final String host, final int port) {
        org.mockito.Mockito.lenient()
                .when(nodeStore.get(nodeId))
                .thenReturn(Node.newBuilder()
                        .nodeId(nodeId)
                        .accountId(AccountID.newBuilder()
                                .accountNum(3000L + nodeId)
                                .build())
                        .serviceEndpoint(ServiceEndpoint.newBuilder()
                                .domainName(host)
                                .port(port)
                                .build())
                        .grpcCertificateHash(Bytes.wrap(new byte[] {7}))
                        .build());
    }

    /** The endpoint {@code buildFor} produces for a node stubbed via {@link #stubNode} with mTLS off. */
    private static ClprEndpoint selfEndpoint(final long nodeId, final String host, final int port) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(host)
                        .port(port)
                        .build())
                .tlsCertificate(Bytes.EMPTY) // mTLS off → empty cert
                .accountId(accountBytes(3000L + nodeId))
                .build();
    }

    private static ClprEndpoint endpoint(final String ip, final int port, final byte[] cert) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(ip)
                        .port(port)
                        .build())
                .tlsCertificate(Bytes.wrap(cert))
                .build();
    }

    private static ClprEndpointPublicationEntry gathered(final long nodeId, final ClprEndpoint endpoint) {
        return ClprEndpointPublicationEntry.newBuilder()
                .nodeId(nodeId)
                .publication(
                        ClprEndpointPublication.newBuilder().endpoint(endpoint).build())
                .build();
    }

    private ClprEndpointManifestConstruction constructionGathering(
            final List<Long> targetNodeIds, final List<ClprEndpointPublicationEntry> gathered, final Instant graceEnd) {
        return construction(targetNodeIds, gathered, graceEnd, 0);
    }

    private ClprEndpointManifestConstruction construction(
            final List<Long> targetNodeIds,
            final List<ClprEndpointPublicationEntry> gathered,
            final Instant graceEnd,
            final int graceExtensionsUsed) {
        return ClprEndpointManifestConstruction.newBuilder()
                .constructionId(2L)
                .targetNodeIds(targetNodeIds)
                .gatheredPublications(gathered)
                .gracePeriodEndTime(timestampFrom(graceEnd))
                .graceExtensionsUsed(graceExtensionsUsed)
                .build();
    }

    private static ClprEndpointManifest manifest(final long version, final List<ClprEndpoint> endpoints) {
        return ClprEndpointManifest.newBuilder()
                .version(version)
                .endpoints(endpoints)
                .build();
    }

    private static Bytes accountBytes(final long accountNum) {
        return AccountID.PROTOBUF.toBytes(
                AccountID.newBuilder().accountNum(accountNum).build());
    }

    private static Timestamp timestampFrom(final Instant instant) {
        return Timestamp.newBuilder()
                .seconds(instant.getEpochSecond())
                .nanos(instant.getNano())
                .build();
    }
}
