// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class to generate a config.txt file from a Roster.
 */
public class ConfigTxtGenerator {

    private ConfigTxtGenerator() {}

    /**
     * Generates the contents of a config.txt file for the given network name and roster.
     *
     * @param networkName the name of the network
     * @param roster the roster of nodes
     * @return the contents of the config.txt file
     */
    public static String configTxtForRoster(@NonNull final String networkName, @NonNull final Roster roster) {
        final var sb = new StringBuilder();
        sb.append("swirld, ")
                .append(networkName)
                .append("\n")
                .append("\n# This next line is, hopefully, ignored.\n")
                .append("app, HederaNode.jar\n\n#The following nodes make up this network\n");
        for (final RosterEntry entry : roster.rosterEntries()) {
            final ServiceEndpoint endpoint = entry.gossipEndpoint().getFirst();
            final AccountID accountId = AccountID.newBuilder()
                    .accountNum(AddressBookUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM + entry.nodeId())
                    .build();
            final var fqAccId =
                    String.format("%d.%d.%d", accountId.shardNum(), accountId.realmNum(), accountId.accountNum());
            sb.append("address, ")
                    .append(entry.nodeId()) // node-id
                    .append(", node-")
                    // For now only use the node id as its nickname
                    .append(entry.nodeId()) // nickname
                    .append(", node-")
                    .append(entry.nodeId()) // self-name
                    .append(", ")
                    .append(entry.weight())
                    .append(", ")
                    .append(endpoint.domainName())
                    .append(", ")
                    .append(endpoint.port())
                    .append(", ")
                    .append(endpoint.domainName())
                    .append(", ")
                    .append(endpoint.port())
                    .append(", ")
                    .append(fqAccId)
                    .append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }
}
