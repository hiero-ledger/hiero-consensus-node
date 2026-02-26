// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_NEGATIVE_INFINITY;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * Validates the internal fields of an event: null checks, byte field lengths, transaction byte
 * limits, parent uniqueness, and birth round consistency.
 */
public class DefaultEventFieldValidator implements EventFieldValidator {
    private static final Logger logger = LogManager.getLogger(DefaultEventFieldValidator.class);

    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private final TransactionLimits transactionLimits;

    private final RateLimitedLogger nullFieldLogger;
    private final RateLimitedLogger fieldLengthLogger;
    private final RateLimitedLogger tooManyTransactionBytesLogger;
    private final RateLimitedLogger invalidParentsLogger;
    private final RateLimitedLogger invalidBirthRoundLogger;

    private final LongAccumulator nullFieldAccumulator;
    private final LongAccumulator fieldLengthAccumulator;
    private final LongAccumulator tooManyTransactionBytesAccumulator;
    private final LongAccumulator invalidParentsAccumulator;
    private final LongAccumulator invalidBirthRoundAccumulator;

    /**
     * Constructor.
     *
     * @param metrics           the metrics system
     * @param time              the time source
     * @param transactionLimits transaction size limits for validation
     */
    public DefaultEventFieldValidator(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final TransactionLimits transactionLimits) {

        this.transactionLimits = Objects.requireNonNull(transactionLimits);

        this.nullFieldLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.fieldLengthLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.tooManyTransactionBytesLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.invalidParentsLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);
        this.invalidBirthRoundLogger = new RateLimitedLogger(logger, time, MINIMUM_LOG_PERIOD);

        this.nullFieldAccumulator =
                metrics.getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "eventsWithNullFields")
                        .withDescription("Events that had a null field")
                        .withUnit("events"));
        this.fieldLengthAccumulator =
                metrics.getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "eventsWithInvalidFieldLength")
                        .withDescription("Events with an invalid field length")
                        .withUnit("events"));
        this.tooManyTransactionBytesAccumulator =
                metrics.getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "eventsWithTooManyTransactionBytes")
                        .withDescription("Events that had more transaction bytes than permitted")
                        .withUnit("events"));
        this.invalidParentsAccumulator =
                metrics.getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "eventsWithInvalidParents")
                        .withDescription("Events that have invalid parents")
                        .withUnit("events"));
        this.invalidBirthRoundAccumulator =
                metrics.getOrCreate(new LongAccumulator.Config(PLATFORM_CATEGORY, "eventsWithInvalidBirthRound")
                        .withDescription("Events with an invalid birth round")
                        .withUnit("events"));
    }

    @Override
    public boolean isValid(@NonNull final PlatformEvent event) {
        return areRequiredFieldsNonNull(event)
                && areByteFieldsCorrectLength(event)
                && isTransactionByteCountValid(event)
                && areParentsFromUniqueCreators(event)
                && isEventBirthRoundValid(event);
    }

    private boolean areRequiredFieldsNonNull(@NonNull final PlatformEvent event) {
        final GossipEvent gossipEvent = event.getGossipEvent();
        final EventCore eventCore = gossipEvent.eventCore();
        String nullField = null;
        if (eventCore == null) {
            nullField = "eventCore";
        } else if (eventCore.timeCreated() == null) {
            nullField = "timeCreated";
        } else if (gossipEvent.parents().stream().anyMatch(Objects::isNull)) {
            nullField = "parent";
        } else if (gossipEvent.transactions().stream().anyMatch(DefaultEventFieldValidator::isTransactionNull)) {
            nullField = "transaction";
        }
        if (nullField != null) {
            nullFieldLogger.error(EXCEPTION.getMarker(), "Event has null field '{}' {}", nullField, gossipEvent);
            nullFieldAccumulator.update(1);
            return false;
        }
        return true;
    }

    private static boolean isTransactionNull(@Nullable final Bytes transaction) {
        return transaction == null || transaction.length() == 0;
    }

    private boolean areByteFieldsCorrectLength(@NonNull final PlatformEvent event) {
        final GossipEvent gossipEvent = event.getGossipEvent();
        if (gossipEvent.parents().stream()
                .map(EventDescriptor::hash)
                .anyMatch(hash -> hash.length() != DigestType.SHA_384.digestLength())) {
            fieldLengthLogger.error(
                    EXCEPTION.getMarker(),
                    "Event parent descriptor has a hash that is the wrong length {}",
                    gossipEvent);
            fieldLengthAccumulator.update(1);
            return false;
        }
        return true;
    }

    private boolean isTransactionByteCountValid(@NonNull final PlatformEvent event) {
        long totalTransactionBytes = 0;
        final Iterator<Transaction> iterator = event.transactionIterator();
        while (iterator.hasNext()) {
            totalTransactionBytes += iterator.next().getSize();
        }

        if (totalTransactionBytes > transactionLimits.maxTransactionBytesPerEvent()) {
            tooManyTransactionBytesLogger.error(
                    EXCEPTION.getMarker(),
                    "Event %s has %s transaction bytes, which is more than permitted"
                            .formatted(event, totalTransactionBytes));
            tooManyTransactionBytesAccumulator.update(1);
            return false;
        }
        return true;
    }

    private boolean areParentsFromUniqueCreators(@NonNull final PlatformEvent event) {
        final long numUniqueParentCreators = event.getAllParents().stream()
                .map(EventDescriptorWrapper::creator)
                .distinct()
                .count();

        if (numUniqueParentCreators < event.getAllParents().size()) {
            invalidParentsAccumulator.update(1);
            invalidParentsLogger.error(
                    EXCEPTION.getMarker(),
                    "Event {} has multiple parents from the same creator: {}",
                    event.getDescriptor(),
                    event.getAllParents());
            return false;
        }
        return true;
    }

    private boolean isEventBirthRoundValid(@NonNull final PlatformEvent event) {
        final long eventBirthRound = event.getDescriptor().eventDescriptor().birthRound();

        long maxParentBirthRound = ROUND_NEGATIVE_INFINITY;
        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            maxParentBirthRound = Math.max(maxParentBirthRound, parent.eventDescriptor().birthRound());
        }

        if (eventBirthRound < maxParentBirthRound) {
            invalidBirthRoundLogger.error(
                    EXCEPTION.getMarker(),
                    ("Event %s has an invalid birth round that is less than the max of its parents. Event birth round: "
                                    + "%s, the max of all parent birth rounds is: %s")
                            .formatted(event, eventBirthRound, maxParentBirthRound));
            invalidBirthRoundAccumulator.update(1);
            return false;
        }
        return true;
    }
}
