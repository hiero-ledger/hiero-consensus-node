// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static org.hiero.consensus.model.status.PlatformStatus.*;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A record of an attempt to look up the status of a Hedera node.
 *
 * @param source the source of the status lookup attempt
 * @param status if non-null, the status of the node
 * @param failureReason if non-null, the reason the status lookup failed
 */
public record StatusLookupAttempt(
        @NonNull Source source, @Nullable PlatformStatus status, @Nullable String failureReason) {

    /**
     * The source of the status lookup attempt.
     */
    public enum Source {
        PROMETHEUS,
        APPLICATION_LOG
    }

    /**
     * Creates a new Prometheus-based {@link StatusLookupAttempt} from the given status and failure reason.
     *
     * @param status the status of the node
     * @param failureReason the reason the status lookup failed
     * @return a new {@link StatusLookupAttempt}
     */
    public static StatusLookupAttempt newPrometheusAttempt(@Nullable String status, @Nullable String failureReason) {
        return new StatusLookupAttempt(Source.PROMETHEUS, status == null ? null : valueOf(status), failureReason);
    }

    /**
     * Creates a new log-based {@link StatusLookupAttempt} from the given status and failure reason.
     *
     * @param status the status of the node
     * @param failureReason the reason the status lookup failed
     * @return a new {@link StatusLookupAttempt}
     */
    public static StatusLookupAttempt newLogAttempt(@Nullable String status, @Nullable String failureReason) {
        return new StatusLookupAttempt(Source.APPLICATION_LOG, status == null ? null : valueOf(status), failureReason);
    }
}
