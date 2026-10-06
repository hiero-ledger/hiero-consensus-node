// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestConstructionStore;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestStore;
import com.hedera.node.app.service.clpr.impl.roster.ClprEndpointBuilder;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.CertificateEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reconciler for the CLPR endpoint manifest construction lifecycle (design doc §4).
 *
 * <p>Called each round from {@code HandleWorkflow}. It does not open constructions on a
 * per-round trigger; opening is driven by a self-publication (in
 * {@code ClprEndpointPublicationHandler}) or by a roster change. Its responsibilities:
 * <ul>
 *   <li>{@link #openConstructionIfSelfChanged} (startup-gated): when this node's own endpoint is absent
 *       from the manifest — which by full-value membership also covers the case where the
 *       endpoint has <em>changed</em> (a differing cert/port/IP is a distinct, and therefore
 *       absent, endpoint) — and no construction is in flight, submit a
 *       {@code ClprEndpointPublicationTransactionBody} via gossip. That publication is what
 *       opens a construction, in {@code ClprEndpointPublicationHandler}.</li>
 *   <li>{@link #contributeSelfToConstruction}: while a construction is gathering, contribute this
 *       node's own endpoint if it has not yet been gathered, so the construction is an all-hands
 *       snapshot.</li>
 *   <li>{@link #openConstructionOnRosterChange}: at the post-upgrade boundary, open a construction
 *       when the roster's endpoint-IP composition changed (node added / removed).</li>
 *   <li>{@link #reconcile}: drive an in-flight construction to close — fast-close once every
 *       target has published, timeout-close after exhausting the grace-extension budget, or
 *       extend grace once and continue. On close, build the candidate manifest (sorted by
 *       endpoint identity — IP if present, else the TLS certificate — for ledger-neutral
 *       determinism), advance-or-no-op vs the active manifest, and delete the construction
 *       singleton.</li>
 * </ul>
 *
 * <p>The companion admission path — routing an incoming publication into the active
 * construction — lives on {@code WritableEndpointManifestConstructionStore.admitPublication}
 * so {@code ClprEndpointPublicationHandler} can call it without depending on this module.
 */
@Singleton
public class ClprEndpointManifestReconciler {

    /**
     * Outcome of {@link #openConstructionIfSelfChanged}. Replaces an opaque boolean so callers read intent
     * directly: {@link #SETTLED} means this node's current endpoint is already in the manifest and
     * no further startup self-publication is needed; {@link #PENDING} means it is not yet in the
     * manifest (a publication was submitted, or is awaited on an in-flight construction) and the
     * caller should keep re-checking on subsequent rounds.
     */
    public enum SelfPublishOutcome {
        SETTLED,
        PENDING
    }

    private static final Logger log = LogManager.getLogger(ClprEndpointManifestReconciler.class);
    // Ledger-neutral, account-id-free ordering: IP if present, else the TLS certificate.
    private static final Comparator<ClprEndpoint> BY_IDENTITY_ASC =
            Comparator.comparing(ClprEndpointBuilder::identityOf, Bytes.SORT_BY_UNSIGNED_VALUE);

    private final ClprSubmissions submissions;
    private final ClprCaCertManager caCertManager;

    /**
     * Node-local retry backoff for {@link #contributeSelfToConstruction}. Because
     * {@code submitEndpointPublication} is a fire-and-forget gossip side-effect, an attempt may never reach consensus
     * (e.g. lost while this node was catching up after a restart). A permanent one-shot per construction would then
     * silently drop this node from the manifest on a single lost submission, so instead we re-publish with backoff:
     * after each attempt wait {@code clpr.manifestSubmissionRetryDelay} before trying again, and stop as soon as this
     * node is observed in the construction's gathered publications (the consensus-visible success signal). The window
     * is reset to "attempt now" whenever no construction is in flight or this node's publication is already gathered,
     * so a freshly opened construction always begins with an immediate attempt. This field is in-memory only (it gates
     * a gossip side-effect, never state); on restart it resets to "attempt now", which is harmless (a re-publish is
     * idempotent — the handler last-write-wins per node id).
     *
     * <p>{@code clpr.manifestSubmissionRetryDelay} must stay well below {@code clpr.manifestGracePeriod}, or the
     * construction closes before any retry fires and the mechanism provides no benefit.
     */
    private Instant nextContributingPublicationAttempt = Instant.EPOCH;

    /**
     * Node-local retry backoff for the opening publication in {@link #openConstructionIfSelfChanged},
     * mirroring {@link #nextContributingPublicationAttempt} but for the startup / absent-endpoint path. While our
     * endpoint is absent and no construction is gathering, an opening publication is submitted at most
     * once per {@code clpr.manifestSubmissionRetryDelay} rather than every round, so the rounds before
     * the first submit lands do not emit a burst of duplicate publications. Still self-healing: the
     * delay is ≪ {@code clpr.manifestGracePeriod} and resets on JVM restart, so a submit lost while this
     * node was catching up is retried after the delay rather than permanently suppressed. A separate
     * field from {@link #nextContributingPublicationAttempt} because {@link #contributeSelfToConstruction} resets
     * that one whenever no construction is in flight — exactly when this path is active — which would
     * otherwise clear this backoff every round. In-memory only (gates a gossip side-effect, never state).
     */
    private Instant nextOpeningPublicationAttempt = Instant.EPOCH;

    /**
     * The last manifest in which this node's current endpoint was observed. While the consensus manifest is
     * unchanged, this avoids rebuilding and checking the local endpoint every round. Unlike a permanent boolean
     * latch, caching the manifest itself lets the reconciler re-check after a reconnect, state restoration, or later
     * manifest update replaces the previously settled value.
     */
    @Nullable
    private ClprEndpointManifest startupSettledManifest;

    @Inject
    public ClprEndpointManifestReconciler(
            @NonNull final ClprSubmissions submissions, @NonNull final ClprCaCertManager caCertManager) {
        this.submissions = requireNonNull(submissions);
        this.caCertManager = requireNonNull(caCertManager);
    }

    /**
     * Per-round tick: drive an in-flight construction to close. The reconciler does
     * <b>not</b> open constructions (that is the publication handler's deterministic job) and does
     * <b>not</b> self-check every round — self-publication is startup-gated and driven separately by
     * {@code HandleWorkflow} via {@link #openConstructionIfSelfChanged}. So when no construction is gathering
     * this method is effectively a single singleton read.
     *
     * @param now the current consensus time
     * @param manifestStore writable view of the finalized manifest singleton
     * @param constructionStore writable view of the in-progress construction singleton
     * @param config CLPR config knobs (grace period, extensions, etc.)
     */
    public void reconcile(
            @NonNull final Instant now,
            @NonNull final WritableEndpointManifestStore manifestStore,
            @NonNull final WritableEndpointManifestConstructionStore constructionStore,
            @NonNull final ClprConfig config) {
        requireNonNull(now);
        requireNonNull(manifestStore);
        requireNonNull(constructionStore);
        requireNonNull(config);

        final var construction = constructionStore.get();
        if (construction != null) {
            // maybeClose writes to the construction store (extended construction on grace-extension,
            // or the finalized manifest + cleared construction on close).
            final var manifest = manifestStore.get();
            maybeClose(construction, now, config, manifest, manifestStore, constructionStore);
        }
    }

    /**
     * Publish this node's own current endpoint if the manifest does not already contain it, and
     * report whether it is now "settled" (present in the manifest). {@code HandleWorkflow} re-invokes
     * this until it returns {@link SelfPublishOutcome#SETTLED}; the wrapper then keeps self-publication quiet while
     * that manifest remains current. Because the mTLS port and CA certificate are loaded at startup and the
     * address-book endpoint is adopted on restart, a node's endpoint can only differ across a (re)start — so this is
     * the one moment it must re-publish.
     *
     * <p>This node's full endpoint is derived from its <em>local</em> config: IP from the address
     * book, port {@code = mtlsPort} when mTLS is on else the HAPI port, cert {@code = } the CA cert
     * (empty in the plaintext fallback). Membership is <b>full-value</b> — a cert- or port-only change
     * keeps the same IP-else-cert identity, so identity membership would miss it. No account_id.
     *
     * <p>The publish is a gossip <b>side-effect</b>, so this may legitimately behave differently per
     * node; the resulting construction is opened deterministically by
     * {@code ClprEndpointPublicationHandler}. An opening publication is sent only when the endpoint is
     * absent AND no construction is currently gathering (an in-flight one is filled by
     * {@link #contributeSelfToConstruction}). Within that window the submit is throttled by
     * {@link #nextOpeningPublicationAttempt}: at most one opening publication per
     * {@code clpr.manifestSubmissionRetryDelay}, so the rounds before the first submit lands do not emit
     * a burst of duplicates. The backoff stays liveness-preserving — it is a short delay
     * (≪ {@code clpr.manifestGracePeriod}) that resets on JVM restart, so a submit lost while this node
     * was catching up is retried after the delay rather than being permanently suppressed.
     *
     * @param construction the current in-flight construction singleton, or {@code null} if none
     * @param now the current consensus time, used to pace the opening-publication backoff
     * @return {@link SelfPublishOutcome#SETTLED} once this node's current endpoint is present in the
     *     manifest; {@link SelfPublishOutcome#PENDING} while it is not yet published/gathered
     */
    public SelfPublishOutcome openConstructionIfSelfChanged(
            final long selfNodeId,
            @NonNull final ClprEndpointManifest manifest,
            @Nullable final ClprEndpointManifestConstruction construction,
            @NonNull final ReadableNodeStore nodeStore,
            @NonNull final ClprConfig config,
            @NonNull final Instant now) {
        requireNonNull(manifest);
        requireNonNull(nodeStore);
        requireNonNull(config);
        requireNonNull(now);
        final var selfEndpoint = ClprEndpointBuilder.buildFor(
                selfNodeId, nodeStore, caCertManager.isMtlsEnabled(), config.mtlsPort(), selfCaCertDer());
        if (selfEndpoint == null) {
            return SelfPublishOutcome.PENDING; // no address-book service endpoint yet — retry next round
        }
        if (manifest.endpoints().contains(selfEndpoint)) {
            return SelfPublishOutcome.SETTLED; // settled: our current endpoint is already published
        }
        // Endpoint absent from the manifest. Open a construction (via an unsolicited publication) only if
        // none is gathering; if one is in flight, contributeSelfToConstruction contributes our endpoint and
        // we re-evaluate once it closes. Throttle the opening submit so a not-yet-landed publication is not
        // re-sent every round (retryDelay ≪ gracePeriod, so a later construction cycle still opens promptly).
        if (construction == null && !now.isBefore(nextOpeningPublicationAttempt)) {
            log.info(
                    "[Clpr] node{} endpoint absent from manifest (v{}) — submitting opening self-publication",
                    selfNodeId,
                    manifest.version());
            submissions.submitEndpointPublication(selfEndpoint);
            nextOpeningPublicationAttempt = now.plus(config.manifestSubmissionRetryDelay());
        }
        return SelfPublishOutcome.PENDING;
    }

    /**
     * Startup-gated wrapper around {@link #openConstructionIfSelfChanged}. No-ops while the same manifest in which
     * this node settled remains current. If the manifest changes, it re-checks the local endpoint so a reconnect or
     * state restoration cannot leave the node permanently suppressed. While unsettled, it re-checks each round
     * (re-publishing at most once per {@code clpr.manifestSubmissionRetryDelay}) until it settles.
     */
    public void openConstructionIfSelfChangedUntilSettled(
            final long selfNodeId,
            @NonNull final ClprEndpointManifest manifest,
            @Nullable final ClprEndpointManifestConstruction construction,
            @NonNull final ReadableNodeStore nodeStore,
            @NonNull final ClprConfig config,
            @NonNull final Instant now) {
        if (manifest.equals(startupSettledManifest)) {
            return;
        }
        startupSettledManifest =
                openConstructionIfSelfChanged(selfNodeId, manifest, construction, nodeStore, config, now)
                                == SelfPublishOutcome.SETTLED
                        ? manifest
                        : null;
    }

    /**
     * Publish this node's current endpoint into an <em>open</em> construction that targets it but does
     * not yet contain its publication. This makes a construction a full all-hands snapshot: when one
     * node's change opens a construction, every other target node contributes its own current endpoint
     * — the node-local {@code mtlsPort} + CA cert that only it can supply — rather than being
     * reconstructed at close by IP-keyed carry-over, which cannot distinguish nodes that share an IP.
     * The non-restarted nodes only gossip a publication here; they do <b>not</b> restart, so their
     * in-memory peer state (e.g. observed-manifest-version) is preserved.
     *
     * <p>Called every round from {@code HandleWorkflow} (not startup-gated). Like {@link
     * #openConstructionIfSelfChanged} the publish is a gossip <b>side-effect</b>; the construction close stays
     * driven by the consensus-gathered publications, so this cannot cause an ISS. Because the submission is
     * fire-and-forget, this re-publishes with backoff — waiting {@code clpr.manifestSubmissionRetryDelay} between
     * attempts and stopping once this node appears in the gathered publications — so a lost submission is retried
     * rather than permanently suppressing this node. A target that never publishes before the window closes is
     * simply dropped from the manifest (there is no carry-over).
     */
    public void contributeSelfToConstruction(
            final long selfNodeId,
            @Nullable final ClprEndpointManifestConstruction construction,
            @NonNull final ReadableNodeStore nodeStore,
            @NonNull final ClprConfig config,
            @NonNull final Instant now) {
        requireNonNull(nodeStore);
        requireNonNull(config);
        requireNonNull(now);
        if (construction == null) {
            resetPublicationRetry();
            return; // nothing gathering
        }
        if (!construction.targetNodeIds().contains(selfNodeId)) {
            return; // not a target of this construction
        }
        for (final var gathered : construction.gatheredPublications()) {
            if (gathered.nodeId() == selfNodeId) {
                resetPublicationRetry();
                return; // our publication reached consensus and is gathered — done for this construction
            }
        }
        if (now.isBefore(nextContributingPublicationAttempt)) {
            return; // submitted recently; wait out the retry backoff before re-attempting
        }
        final var selfEndpoint = ClprEndpointBuilder.buildFor(
                selfNodeId, nodeStore, caCertManager.isMtlsEnabled(), config.mtlsPort(), selfCaCertDer());
        if (selfEndpoint == null) {
            return; // no derivable service endpoint yet — retry next round (no attempt recorded)
        }
        log.info(
                "[Clpr] node{} publishing its endpoint into construction #{}",
                selfNodeId,
                construction.constructionId());
        submissions.submitEndpointPublication(selfEndpoint);
        nextContributingPublicationAttempt = now.plus(config.manifestSubmissionRetryDelay());
    }

    /** Clears the publication-retry backoff so the next open construction starts with an immediate attempt. */
    private void resetPublicationRetry() {
        nextContributingPublicationAttempt = Instant.EPOCH;
    }

    /**
     * Roster-adoption hook — called from {@code HandleWorkflow}'s post-upgrade block (a deterministic
     * round). Opens a construction when the active roster's composition no longer matches the manifest,
     * i.e. a node was added or removed (compared by IP identity). This is the one trigger
     * self-publication cannot provide: a removed node can't self-report, so its stale ("orphan") entry
     * must be pruned here. The opened construction targets the current roster; the close then rebuilds
     * against it — orphans (absent from the target set) drop, added/changed nodes are gathered from
     * their own post-restart publications, and unchanged nodes carry over by IP. A code-only upgrade
     * with an unchanged roster opens nothing.
     *
     * <p>If a construction is already in flight when the composition changed, it is <b>replaced</b> — a stale
     * construction may still target a now-removed node and hold that node's old publication, so we clear the
     * gathered publications and reset the grace period rather than skip. Only a composition change replaces it;
     * an unchanged roster leaves any in-flight construction untouched.
     *
     * <p>Deterministic: runs on the same round on every node over consensus state (roster + manifest +
     * address book); no node-local input.
     */
    public void openConstructionOnRosterChange(
            @NonNull final Instant now,
            @NonNull final Roster activeRoster,
            @NonNull final WritableEndpointManifestStore manifestStore,
            @NonNull final WritableEndpointManifestConstructionStore constructionStore,
            @NonNull final ReadableNodeStore nodeStore,
            @NonNull final ClprConfig config) {
        requireNonNull(now);
        requireNonNull(activeRoster);
        requireNonNull(manifestStore);
        requireNonNull(constructionStore);
        requireNonNull(nodeStore);
        requireNonNull(config);
        final var manifest = manifestStore.get();
        if (!compositionDiffers(manifest, activeRoster, nodeStore)) {
            // Roster composition unchanged — nothing to prune or add. Any in-flight construction already targets
            // the current roster, so leave it alone.
            return;
        }
        // Composition changed (a node was added or removed). (Re)open a construction targeting the CURRENT roster.
        // If one is already in flight it may still target a now-removed node and hold that node's stale publication,
        // so replace it outright rather than returning early — clearing the gathered publications and resetting the
        // grace period so the current roster nodes publish again.
        final var existing = constructionStore.get();
        final long newConstructionId = (existing != null ? existing.constructionId() : manifest.version()) + 1;
        final var targetNodeIds = activeRoster.rosterEntries().stream()
                .map(re -> re.nodeId())
                .sorted()
                .toList();
        final var opened = ClprEndpointManifestConstruction.newBuilder()
                .constructionId(newConstructionId)
                .targetNodeIds(targetNodeIds)
                .gatheredPublications(List.of())
                .gracePeriodEndTime(toTimestamp(now.plus(config.manifestGracePeriod())))
                .graceExtensionsUsed(0)
                .build();
        constructionStore.put(opened);
        log.info(
                "[Clpr] {} construction #{} on roster-composition change (targetNodes={}, manifestEntries={})",
                existing != null ? "replaced in-flight" : "opened",
                opened.constructionId(),
                targetNodeIds.size(),
                manifest.endpoints().size());
    }

    /**
     * True when the active roster's IP <em>multiset</em> differs from the manifest's — a node was added or
     * removed. Uses per-IP counts rather than a set so adding or removing one of several nodes that share an IP
     * (e.g. subprocess nodes on {@code 127.0.0.1}) is still detected; a set would collapse them and let a stale
     * endpoint survive. Content-only changes (cert/port, same IP) do not diff here; those are covered by
     * self-publication. IP is the only endpoint field derivable from the shared address book for every node, so it
     * is the composition key (account-id-free).
     */
    private static boolean compositionDiffers(
            @NonNull final ClprEndpointManifest manifest,
            @NonNull final Roster activeRoster,
            @NonNull final ReadableNodeStore nodeStore) {
        final Map<String, Integer> manifestIpCounts = new HashMap<>();
        for (final var endpoint : manifest.endpoints()) {
            final var svc = endpoint.serviceEndpoint();
            if (svc != null && svc.ipAddress() != null && !svc.ipAddress().isEmpty()) {
                manifestIpCounts.merge(svc.ipAddress(), 1, Integer::sum);
            }
        }
        final Map<String, Integer> rosterIpCounts = new HashMap<>();
        for (final var entry : activeRoster.rosterEntries()) {
            final String ip = ClprEndpointBuilder.ipAddressOf(entry.nodeId(), nodeStore);
            if (ip != null) {
                rosterIpCounts.merge(ip, 1, Integer::sum);
            }
        }
        return !manifestIpCounts.equals(rosterIpCounts);
    }

    /**
     * DER-encoding of this node's ECDSA CA certificate for publication, or {@link Bytes#EMPTY}
     * when mTLS is not configured (the plaintext fallback). Throws {@link IllegalStateException}
     * when mTLS is enabled but the configured certificate cannot be DER-encoded.
     */
    private Bytes selfCaCertDer() {
        if (!caCertManager.isMtlsEnabled()) {
            return Bytes.EMPTY;
        }
        try {
            return Bytes.wrap(caCertManager.caCert().getEncoded());
        } catch (final CertificateEncodingException e) {
            // mTLS is enabled but the configured CA certificate cannot be DER-encoded — a fatal
            // misconfiguration. Fail fast rather than silently publishing an empty tls_certificate.
            throw new IllegalStateException(
                    "CLPR mTLS is enabled but the configured CA certificate cannot be DER-encoded", e);
        }
    }

    /**
     * Evaluate close conditions. Returns {@code true} if the construction was closed
     * (advance or no-op); the construction singleton is deleted in either case.
     * If the grace period expired but extensions are still available, mutates
     * {@code construction} to bump the grace period and returns {@code false} — caller
     * writes back to state.
     */
    private boolean maybeClose(
            @NonNull ClprEndpointManifestConstruction construction,
            @NonNull final Instant now,
            @NonNull final ClprConfig config,
            @NonNull final ClprEndpointManifest currentManifest,
            @NonNull final WritableEndpointManifestStore manifestStore,
            @NonNull final WritableEndpointManifestConstructionStore constructionStore) {
        final boolean fastClose = construction.gatheredPublications().size()
                >= construction.targetNodeIds().size();
        final boolean graceExpired = now.isAfter(toInstant(construction.gracePeriodEndTimeOrThrow()));
        final boolean extensionsExhausted = construction.graceExtensionsUsed() >= config.manifestMaxGraceExtensions();
        if (!fastClose && !graceExpired) {
            return false;
        }
        if (!fastClose && !extensionsExhausted) {
            // Extend one grace period and continue.
            final var extended = construction
                    .copyBuilder()
                    .gracePeriodEndTime(toTimestamp(now.plus(config.manifestGraceExtension())))
                    .graceExtensionsUsed(construction.graceExtensionsUsed() + 1)
                    .build();
            log.warn(
                    "[Clpr] construction #{} grace period expired with {} of {} target nodes "
                            + "published — extending (extensions used: {}/{})",
                    construction.constructionId(),
                    construction.gatheredPublications().size(),
                    construction.targetNodeIds().size(),
                    extended.graceExtensionsUsed(),
                    config.manifestMaxGraceExtensions());
            constructionStore.put(extended);
            return false;
        }
        closeAndFinalize(construction, currentManifest, manifestStore, constructionStore);
        return true;
    }

    private void closeAndFinalize(
            @NonNull final ClprEndpointManifestConstruction construction,
            @NonNull final ClprEndpointManifest currentManifest,
            @NonNull final WritableEndpointManifestStore manifestStore,
            @NonNull final WritableEndpointManifestConstructionStore constructionStore) {
        final var candidate = buildCandidateEndpoints(construction);
        final boolean unchanged = candidate.equals(currentManifest.endpoints());
        final long newVersion = unchanged ? currentManifest.version() : currentManifest.version() + 1;
        final var newManifest = currentManifest
                .copyBuilder()
                .version(newVersion)
                .endpoints(candidate)
                .build();
        manifestStore.put(newManifest);
        constructionStore.clear();
        log.info(
                "[Clpr] closed construction #{}: {} — version {} -> {}, entries={}",
                construction.constructionId(),
                unchanged ? "no-op (endpoints unchanged)" : "advance",
                currentManifest.version(),
                newVersion,
                candidate.size());
    }

    /**
     * Build the candidate endpoint list at close time from <b>only</b> the publications gathered in
     * this construction — there is no carry-over. A target node that did not publish before the
     * construction closed is simply not represented in the new manifest; it re-adds itself the next
     * time it participates (its endpoint won't be in the manifest, so its startup self-publish
     * re-announces it). This keeps the manifest a faithful snapshot of the nodes that actually
     * announced their current endpoint, and it avoids trying to reconstruct a silent node's
     * node-local fields (mtlsPort, CA cert) — which is impossible from shared state and ambiguous when
     * nodes share an IP. Every reachable target node contributes via {@link
     * #contributeSelfToConstruction}, so a construction is a full all-hands snapshot; only a genuinely
     * down node drops out. No-change acks (publications without an endpoint) are ignored. The result
     * is sorted by IP-else-cert identity for ledger-neutral determinism.
     */
    private static List<ClprEndpoint> buildCandidateEndpoints(
            @NonNull final ClprEndpointManifestConstruction construction) {
        final var candidate =
                new ArrayList<ClprEndpoint>(construction.gatheredPublications().size());
        for (final var gathered : construction.gatheredPublications()) {
            final var publication = gathered.publicationOrThrow();
            if (publication.hasEndpoint()) {
                candidate.add(publication.endpointOrThrow());
            }
        }
        candidate.sort(BY_IDENTITY_ASC);
        return candidate;
    }

    private static Timestamp toTimestamp(@NonNull final Instant instant) {
        return Timestamp.newBuilder()
                .seconds(instant.getEpochSecond())
                .nanos(instant.getNano())
                .build();
    }

    private static Instant toInstant(@NonNull final Timestamp ts) {
        return Instant.ofEpochSecond(ts.seconds(), ts.nanos());
    }
}
