// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.utils;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterEntryNotFoundException;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Verifies event signatures by resolving the creator's certificate from the roster and delegating
 * to a cached {@link BytesSignatureVerifier}.
 *
 * <p>This class is thread-safe. The verifier caching strategy (shared {@link ConcurrentHashMap} vs
 * per-thread {@link ThreadLocal}) is auto-selected based on
 * {@link BytesSignatureVerifier#isThreadSafe()}.
 */
public class EventSignatureChecker {
    private static final Logger logger = LogManager.getLogger(EventSignatureChecker.class);
    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private final ConcurrentHashMap<VerifierKey, Bytes> publicKeyCache = new ConcurrentHashMap<>();
    private final Function<Bytes, BytesSignatureVerifier> verifierFactory;
    private volatile RosterHistory rosterHistory;
    private final RateLimitedLogger rateLimitedLogger;

    // Caching: one of these two is used, chosen lazily on the first resolve() call.
    private volatile Boolean verifierIsThreadSafe;
    private final ConcurrentHashMap<Bytes, BytesSignatureVerifier> sharedVerifierCache = new ConcurrentHashMap<>();
    private final ThreadLocal<HashMap<Bytes, BytesSignatureVerifier>> threadLocalVerifierCache =
            ThreadLocal.withInitial(HashMap::new);

    private record VerifierKey(NodeId nodeId, long birthRound) {}

    /**
     * Constructor.
     *
     * @param time            the time source
     * @param verifierFactory creates a new {@link BytesSignatureVerifier} from certificate bytes
     * @param rosterHistory   the complete roster history
     */
    public EventSignatureChecker(
            @NonNull final Time time,
            @NonNull final Function<Bytes, BytesSignatureVerifier> verifierFactory,
            @NonNull final RosterHistory rosterHistory) {
        this.verifierFactory = Objects.requireNonNull(verifierFactory);
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
        this.rateLimitedLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
    }

    /**
     * Determine whether a given event has a valid signature.
     *
     * @param event the event to validate
     * @return true if the event has a valid signature, otherwise false
     */
    public boolean isSignatureValid(@NonNull final PlatformEvent event) {
        final VerifierKey key = new VerifierKey(event.getCreatorId(), event.getBirthRound());
        final Bytes certBytes = publicKeyCache.computeIfAbsent(key, this::resolveCertificateBytes);
        if (certBytes == null) {
            return false;
        }

        final BytesSignatureVerifier verifier = resolveVerifier(certBytes);
        return verifier.verify(event.getHash().getBytes(), event.getSignature());
    }

    /**
     * Update the roster history used for certificate resolution.
     *
     * @param rosterHistory the new roster history
     */
    public void setRosterHistory(@NonNull final RosterHistory rosterHistory) {
        this.rosterHistory = Objects.requireNonNull(rosterHistory);
    }

    /**
     * Evict cached public keys for birth rounds below the given ancient threshold.
     *
     * @param ancientThreshold the ancient threshold round
     */
    public void evictAncient(final long ancientThreshold) {
        publicKeyCache.keySet().removeIf(key -> key.birthRound() < ancientThreshold);
    }

    /**
     * Resolve (or retrieve from cache) a verifier for the given certificate bytes.
     * On the first call, the factory is probed to determine if verifiers are thread-safe.
     * Thread-safe verifiers are stored in a shared cache; non-thread-safe verifiers get
     * a per-thread cache.
     */
    @NonNull
    private BytesSignatureVerifier resolveVerifier(@NonNull final Bytes certBytes) {
        if (verifierIsThreadSafe == null) {
            synchronized (this) {
                if (verifierIsThreadSafe == null) {
                    final BytesSignatureVerifier probe = verifierFactory.apply(certBytes);
                    verifierIsThreadSafe = probe.isThreadSafe();
                    if (verifierIsThreadSafe) {
                        sharedVerifierCache.put(certBytes, probe);
                    } else {
                        threadLocalVerifierCache.get().put(certBytes, probe);
                    }
                    return probe;
                }
            }
        }

        if (verifierIsThreadSafe) {
            return sharedVerifierCache.computeIfAbsent(certBytes, verifierFactory);
        } else {
            return threadLocalVerifierCache.get().computeIfAbsent(certBytes, verifierFactory);
        }
    }

    /**
     * Resolve the certificate bytes for a given node from the roster.
     */
    @Nullable
    private Bytes resolveCertificateBytes(@NonNull final VerifierKey key) {
        final Roster roster = rosterHistory.getRosterForRound(key.birthRound());
        if (roster == null) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(),
                    "Cannot validate events for birth round {} without a roster",
                    key.birthRound());
            return null;
        }
        final RosterEntry rosterEntry;
        try {
            rosterEntry = RosterUtils.getRosterEntry(roster, key.nodeId().id());
        } catch (RosterEntryNotFoundException e) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(), "Node {} doesn't exist in applicable roster", key.nodeId());
            return null;
        }

        final Bytes certBytes = rosterEntry.gossipCaCertificate();
        if (certBytes.length() == 0) {
            rateLimitedLogger.error(
                    EXCEPTION.getMarker(), "Cannot find publicKey for creator with ID: {}", key.nodeId());
            return null;
        }

        return certBytes;
    }
}