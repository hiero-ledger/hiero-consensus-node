// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.RESERVE;

import com.swirlds.base.time.Time;
import com.swirlds.common.formatting.TextTable;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.consensus.model.utility.StackTrace;

/**
 * Tracks the usage of a signed state over time, storing stack traces that can be used at a later time for debugging.
 */
public class SignedStateHistory {

    /**
     * An operation performed on a signed state.
     */
    public enum SignedStateAction {
        /**
         * When the signed state constructor is called.
         */
        CREATION,
        /**
         * When {@link SignedState#incrementReservationCount(String, long)} is called.
         */
        RESERVE,
        /**
         * When {@link SignedState#decrementReservationCount(String, long)} is called.
         */
        RELEASE,
        /**
         * When a signed state is destroyed.
         */
        DESTROY
    }

    /**
     * A record of an action taking with a signed state.
     *
     * @param action       the action
     * @param reason       the reason for the action, may be null for actions that do not require a reason
     * @param uniqueId     a unique id for the action, may be null for actions that do not require a unique id
     * @param stackTrace   where the action was performed, may be null if stack traces are not enabled
     * @param timestamp    the timestamp of the action
     * @param reservations the reservation count prior to the action
     */
    public record SignedStateActionReport(
            @NonNull SignedStateAction action,
            @Nullable String reason,
            @Nullable Long uniqueId,
            @Nullable StackTrace stackTrace,
            @NonNull Instant timestamp,
            int reservations) {

        /**
         * Generate a report and add it to a string builder.
         *
         * @param sb                   the string builder to add the report to
         * @param releasedReservations the set of unique ids of reservations that have been released
         */
        public void generateReport(@NonNull final StringBuilder sb, @NonNull final Set<Long> releasedReservations) {
            final TextTable table = new TextTable().setBordersEnabled(false).setExtraPadding(1);

            table.addRow(action);
            if (action == RESERVE && !releasedReservations.contains(uniqueId)) {
                table.addToRow("", "*** UNRELEASED ***");
            }

            if (reason != null) {
                table.addRow("   reason", reason);
            }
            table.addRow("   timestamp", timestamp);
            table.addRow("   initial reservations", reservations);
            if (uniqueId != null) {
                table.addRow("   reservation ID", uniqueId);
            }
            table.render(sb);

            if (stackTrace != null) {
                sb.append("\n").append(stackTrace);
            }

            sb.append("\n");
        }
    }

    private final Queue<SignedStateActionReport> actions = new ConcurrentLinkedQueue<>();
    private final Time time;
    private final long round;
    private final boolean stackTracesEnabled;

    /**
     * Create a new object to track the history of a signed state.
     *
     * @param time               used to access wall clock time
     * @param round              the round number of the signed state
     * @param stackTracesEnabled whether stack traces should be recorded
     */
    public SignedStateHistory(@NonNull final Time time, final long round, final boolean stackTracesEnabled) {
        this.time = time;
        this.round = round;
        this.stackTracesEnabled = stackTracesEnabled;
    }

    /**
     * Record an action on a signed state.
     *
     * @param action       the action
     * @param reservations the number of reservations before the action
     * @param reason       the reason for the action, may be null for actions that do not require a reason
     * @param uniqueId     a unique id for the action, may be null for actions that do not require a unique id
     */
    public void recordAction(
            @NonNull final SignedStateAction action,
            int reservations,
            @Nullable final String reason,
            @Nullable final Long uniqueId) {
        actions.add(new SignedStateActionReport(
                action,
                reason,
                uniqueId,
                stackTracesEnabled ? StackTrace.getStackTrace() : null,
                time.now(),
                reservations));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SignedState history for round ").append(round).append(":\n\n");

        final Set<Long> releasedReservations = new HashSet<>();
        for (final SignedStateActionReport report : actions) {
            if (report.action() == SignedStateAction.RELEASE) {
                releasedReservations.add(report.uniqueId());
            }
        }

        for (final SignedStateActionReport report : actions) {
            report.generateReport(sb, releasedReservations);
        }

        return sb.toString();
    }
}
