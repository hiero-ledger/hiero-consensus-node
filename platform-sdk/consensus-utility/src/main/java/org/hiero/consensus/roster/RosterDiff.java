// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A utility class to compare two Roster instances and identify differences.
 * It reports which roster entries have been added, deleted, or modified.
 */
public class RosterDiff {

    /**
     * Compares an old Roster with a new Roster and returns the differences.
     *
     * @param oldRoster The original Roster instance. Can be null.
     * @param newRoster The updated Roster instance. Can be null.
     * @return A RosterDiffReport object detailing the changes.
     */
    @NonNull
    public static RosterDiffReport report(@Nullable final Roster oldRoster, @Nullable final Roster newRoster) {
        // Handle null cases for entire rosters
        if (oldRoster == null && newRoster == null) {
            return new RosterDiffReport(List.of(), List.of(), List.of());
        }
        if (oldRoster == null) {
            return new RosterDiffReport(newRoster.rosterEntries(), List.of(), List.of());
        }
        if (newRoster == null) {
            return new RosterDiffReport(List.of(), oldRoster.rosterEntries(), List.of());
        }

        final Map<Long, RosterEntry> oldEntriesById =
                oldRoster.rosterEntries().stream().collect(Collectors.toMap(RosterEntry::nodeId, Function.identity()));

        final Map<Long, RosterEntry> newEntriesById =
                newRoster.rosterEntries().stream().collect(Collectors.toMap(RosterEntry::nodeId, Function.identity()));

        // Entries that are in the old roster but not in the new one.
        final List<RosterEntry> deletedEntries = oldEntriesById.keySet().stream()
                .filter(nodeId -> !newEntriesById.containsKey(nodeId))
                .map(oldEntriesById::get)
                .collect(Collectors.toList());

        // Entries that are in the new roster but not in the old one.
        final List<RosterEntry> addedEntries = newEntriesById.keySet().stream()
                .filter(nodeId -> !oldEntriesById.containsKey(nodeId))
                .map(newEntriesById::get)
                .collect(Collectors.toList());

        // Entries that exist in both rosters but have different content.
        final List<Pair<RosterEntry, RosterEntry>> modifiedEntries = newEntriesById.values().stream()
                .map(newEntry -> {
                    final RosterEntry oldEntry = oldEntriesById.get(newEntry.nodeId());
                    // An entry is modified if it exists in both but is not equal.
                    if (oldEntry != null && !newEntry.equals(oldEntry)) {
                        return Pair.of(oldEntry, newEntry);
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new RosterDiffReport(addedEntries, deletedEntries, modifiedEntries);
    }

    /**
     * A data class to hold the results of a comparison between two Roster objects.
     * It also provides a formatted string representation of the differences.
     */
    public record RosterDiffReport(
            List<RosterEntry> added, List<RosterEntry> deleted, List<Pair<RosterEntry, RosterEntry>> modified) {

        public static final String PARENT_FIELD_TAB = "  - ";
        public static final String CHILD_FIELD_TAB = "    -- ";

        /**
         * Constructs a new RosterComparisonResult.
         *
         * @param added    List of RosterEntry objects that were added.
         * @param deleted  List of RosterEntry objects that were deleted.
         * @param modified List of RosterEntry pair objects that were modified.
         */
        public RosterDiffReport {
            Objects.requireNonNull(added);
            Objects.requireNonNull(deleted);
            Objects.requireNonNull(modified);
        }

        public boolean hasChanges() {
            return !added.isEmpty() || !deleted.isEmpty() || !modified.isEmpty();
        }

        /**
         * Generates string report of the roster differences.
         *
         * @return A formatted string detailing the changes.
         */
        @NonNull
        public String print() {
            if (!hasChanges()) {
                return "No differences found between the rosters.";
            }

            final StringBuilder report = new StringBuilder("Roster Comparison Result:\n");
            report.append("=========================\n");

            if (!added.isEmpty()) {
                report.append("\n--- ADDED ---\n");
                added.stream().map(RosterEntry.JSON::toJSON).forEach(entry -> report.append(entry)
                        .append("\n"));
            }

            if (!deleted.isEmpty()) {
                report.append("\n--- DELETED ---\n");
                report.append("Node IDs:")
                        .append(deleted.stream().map(RosterEntry::nodeId).toList())
                        .append("\n");
            }

            if (!modified.isEmpty()) {
                report.append("\n--- MODIFIED ---\n");
                modified.forEach(mod -> {
                    final RosterEntry oldE = mod.left();
                    final RosterEntry newE = mod.right();
                    report.append("Node ID: ").append(newE.nodeId()).append("\n");

                    // Compare each property and report the change if different.
                    reportDiffForWeight(oldE, newE, report);
                    reportDiffForCaCertificate(oldE, newE, report);
                    reportDiffForServiceEndpoint(oldE, newE, report);
                });
            }

            return report.toString();
        }

        private static void reportDiffForServiceEndpoint(
                final RosterEntry oldE, final RosterEntry newE, final StringBuilder report) {
            if (!oldE.gossipEndpoint().equals(newE.gossipEndpoint())) {
                report.append(PARENT_FIELD_TAB);
                report.append("gossipEndpoint: \n");
                if (oldE.gossipEndpoint().size() != newE.gossipEndpoint().size()) {
                    report.append(String.format(
                            "%ssize: %d -> %d",
                            CHILD_FIELD_TAB,
                            oldE.gossipEndpoint().size(),
                            newE.gossipEndpoint().size()));
                    if (newE.gossipEndpoint().size() > oldE.gossipEndpoint().size()) {
                        report.append(". Only first entry will be used.");
                    }
                    report.append("\n");
                }
                final ServiceEndpoint oldEndpoint = oldE.gossipEndpoint().getFirst();
                final ServiceEndpoint newEndpoint = newE.gossipEndpoint().getFirst();
                reportDiffForIpAddress(report, oldEndpoint, newEndpoint);
                reportDiffForPort(report, oldEndpoint, newEndpoint);
                reportDiffForDomainName(report, oldEndpoint, newEndpoint);
            }
        }

        private static void reportDiffForCaCertificate(
                final RosterEntry oldE, final RosterEntry newE, final StringBuilder report) {
            reportDiff(
                    oldE,
                    newE,
                    report,
                    RosterEntry::gossipCaCertificate,
                    Bytes::toBase64,
                    PARENT_FIELD_TAB,
                    "gossipCaCertificate");
        }

        private static void reportDiffForWeight(
                final RosterEntry oldE, final RosterEntry newE, final StringBuilder report) {
            reportDiff(oldE, newE, report, RosterEntry::weight, Object::toString, PARENT_FIELD_TAB, "weight");
        }

        private static <T, S> void reportDiff(
                final T oldE,
                final T newE,
                final StringBuilder report,
                final Function<T, S> extract,
                final Function<S, String> toString,
                final String tab,
                final String name) {
            final S oldValue = extract.apply(oldE);
            final S neValue = extract.apply(newE);
            if (!oldValue.equals(neValue)) {
                report.append(String.format(
                        "%s%s: '%s' -> '%s'%n", tab, name, toString.apply(oldValue), toString.apply(neValue)));
            }
        }

        private static void reportDiffForDomainName(
                final StringBuilder report, final ServiceEndpoint oldEndpoint, final ServiceEndpoint newEndpoint) {
            reportDiff(
                    oldEndpoint,
                    newEndpoint,
                    report,
                    ServiceEndpoint::domainName,
                    Object::toString,
                    CHILD_FIELD_TAB,
                    "domainName");
        }

        private static void reportDiffForPort(
                final StringBuilder report, final ServiceEndpoint oldEndpoint, final ServiceEndpoint newEndpoint) {
            reportDiff(
                    oldEndpoint, newEndpoint, report, ServiceEndpoint::port, Object::toString, CHILD_FIELD_TAB, "port");
        }

        private static void reportDiffForIpAddress(
                final StringBuilder report, final ServiceEndpoint oldEndpoint, final ServiceEndpoint newEndpoint) {
            reportDiff(
                    oldEndpoint,
                    newEndpoint,
                    report,
                    ServiceEndpoint::ipAddressV4,
                    v -> HapiUtils.asReadableIp(v) + " (" + v.toBase64() + ")",
                    CHILD_FIELD_TAB,
                    "ipAddressV4");
        }
    }
}
