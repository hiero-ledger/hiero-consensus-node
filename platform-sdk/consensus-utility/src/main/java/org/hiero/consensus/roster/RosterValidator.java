// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A Roster validator.
 */
public final class RosterValidator {
    private RosterValidator() {}

    /**
     * Check if a given Roster object is valid and usable by the platform,
     * or throw an InvalidRosterException.
     *
     * @param roster a roster to validate
     */
    public static void validate(@NonNull final Roster roster) {
        if (roster == null) {
            throw new InvalidRosterException("roster is null");
        }

        final List<RosterEntry> rosterEntries = roster.rosterEntries();

        if (rosterEntries.isEmpty()) {
            throw new InvalidRosterException("roster is empty");
        }

        if (rosterEntries.stream().noneMatch(re -> re.weight() > 0)) {
            throw new InvalidRosterException("roster weight is zero or negative");
        }

        final Set<Long> seenNodeIds = new HashSet<>();
        long lastSeenNodeId = 0;
        for (final RosterEntry re : rosterEntries) {
            if (seenNodeIds.contains(re.nodeId())) {
                throw new InvalidRosterException("duplicate node id: " + re.nodeId());
            }
            seenNodeIds.add(re.nodeId());

            if (re.nodeId() < lastSeenNodeId) {
                throw new InvalidRosterException("RosterEntries sort order is invalid. Found node id: " + re.nodeId()
                        + " following " + lastSeenNodeId);
            }
            lastSeenNodeId = re.nodeId();

            if (re.weight() < 0) {
                throw new InvalidRosterException("weight is negative for node id: " + re.nodeId());
            }

            // May want to also check if the bytes represent a valid certificate.
            if (re.gossipCaCertificate().length() == 0) {
                throw new InvalidRosterException("gossipCaCertificate is empty for NodeId " + re.nodeId());
            }

            if (re.gossipEndpoint().isEmpty()) {
                throw new InvalidRosterException("gossipEndpoint is empty for NodeId " + re.nodeId());
            }

            for (final ServiceEndpoint se : re.gossipEndpoint()) {
                if (se.port() == 0) {
                    throw new InvalidRosterException(
                            "gossipPort is zero for NodeId " + re.nodeId() + " and ServiceEndpoint " + se);
                }

                if (!(se.domainName().isEmpty() ^ se.ipAddressV4().length() == 0)) {
                    throw new InvalidRosterException(
                            "ServiceEndpoint must specify either a domainName or an ipAddressV4, but not both. For NodeId "
                                    + re.nodeId() + " found ServiceEndpoint " + se);
                }

                if (se.ipAddressV4().length() != 0 && se.ipAddressV4().length() != 4) {
                    throw new InvalidRosterException("ServiceEndpoint ipAddressV4 must have a length of 4 bytes, found "
                            + se.ipAddressV4().length() + " bytes for nodeId " + re.nodeId());
                }
            }
        }
    }
}
