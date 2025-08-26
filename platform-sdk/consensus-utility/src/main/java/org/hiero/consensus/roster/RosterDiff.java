// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
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
     * @return A RosterComparisonResult object detailing the changes.
     */
    @NonNull
    public static RosterDiffResult report(@Nullable final Roster oldRoster, @Nullable final Roster newRoster) {
        // Handle null cases for entire rosters
        if (oldRoster == null && newRoster == null) {
            return new RosterDiffResult(List.of(), List.of(), List.of());
        }
        if (oldRoster == null) {
            return new RosterDiffResult(newRoster.rosterEntries(), List.of(), List.of());
        }
        if (newRoster == null) {
            return new RosterDiffResult(List.of(), oldRoster.rosterEntries(), List.of());
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

        return new RosterDiffResult(addedEntries, deletedEntries, modifiedEntries);
    }

    /**
     * A data class to hold the results of a comparison between two Roster objects.
     * It also provides a formatted string representation of the differences.
     */
    public record RosterDiffResult(
            List<RosterEntry> added, List<RosterEntry> deleted, List<Pair<RosterEntry, RosterEntry>> modified) {

        /**
         * Constructs a new RosterComparisonResult.
         *
         * @param added    List of RosterEntry objects that were added.
         * @param deleted  List of RosterEntry objects that were deleted.
         * @param modified List of RosterEntry pair objects that were modified.
         */
        public RosterDiffResult {
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
                    RosterEntry oldE = mod.left();
                    RosterEntry newE = mod.right();
                    report.append("Node ID: ").append(newE.nodeId()).append("\n");

                    // Compare each property and report the change if different.
                    if (oldE.weight() != newE.weight()) {
                        report.append(String.format("  - weight: %d -> %d\n", oldE.weight(), newE.weight()));
                    }
                    if (!oldE.gossipCaCertificate().equals(newE.gossipCaCertificate())) {
                        report.append("  - gossipCaCertificate: has changed\n");
                    }
                    if (!oldE.gossipEndpoint().equals(newE.gossipEndpoint())) {
                        report.append("  - gossipEndpoint: \n");
                        if (oldE.gossipEndpoint().size()
                                != newE.gossipEndpoint().size()) {
                            report.append(String.format(
                                    "    -- size: %d -> %d",
                                    oldE.gossipEndpoint().size(),
                                    newE.gossipEndpoint().size()));
                            if (newE.gossipEndpoint().size()
                                    > oldE.gossipEndpoint().size()) {
                                report.append(". Only first entry will be used.");
                            }
                            report.append("\n");
                        }
                        var oldEndpoint = oldE.gossipEndpoint().getFirst();
                        var newEndpoint = newE.gossipEndpoint().getFirst();
                        if (!oldEndpoint.ipAddressV4().equals(newEndpoint.ipAddressV4())) {
                            report.append(String.format(
                                    "    -- ipAddressV4: %s -> %s\n",
                                    asReadableIp(oldEndpoint.ipAddressV4()), asReadableIp(newEndpoint.ipAddressV4())));
                        }
                        if (oldEndpoint.port() != newEndpoint.port()) {
                            report.append(
                                    String.format("    -- port: %s -> %s\n", oldEndpoint.port(), newEndpoint.port()));
                        }
                        if (!oldEndpoint.domainName().equals(newEndpoint.domainName())) {
                            report.append(String.format(
                                    "    -- domainName: %s -> %s\n",
                                    oldEndpoint.domainName(), newEndpoint.domainName()));
                        }
                    }
                });
            }

            return report.toString();
        }
    }

    /**
     * Converts the given {@link Bytes} instance to a readable IPv4 address string.
     * @param ipV4Addr the {@link Bytes} instance to convert
     * @return the readable IPv4 address string
     */
    static String asReadableIp(@NonNull final Bytes ipV4Addr) {
        requireNonNull(ipV4Addr);
        final var bytes = ipV4Addr.toByteArray();
        return (0xff & bytes[0]) + "." + (0xff & bytes[1]) + "." + (0xff & bytes[2]) + "." + (0xff & bytes[3]);
    }
}
