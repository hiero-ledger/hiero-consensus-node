// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the schema for the account-to-node relation.
 * Introduces a new state map and provides migration logic to populate the map with current node relations.
 */
public class V068AddressBookSchema extends Schema<SemanticVersion> {
    public static final String ACCOUNT_NODE_REL_STATE_KEY = "ACCOUNT_NODE_REL";
    public static final int ACCOUNT_NODE_REL_STATE_ID =
            StateKey.KeyOneOfType.ADDRESSBOOKSERVICE_I_ACCOUNT_NODE_REL.protoOrdinal();
    public static final String ACCOUNT_NODE_REL_STATE_LABEL =
            computeLabel(AddressBookService.NAME, ACCOUNT_NODE_REL_STATE_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();

    private static final long MAX_RELATIONS = 100L;

    /**
     * Constructs a new schema instance for version 0.68.0,
     * using the semantic version comparator for version management.
     */
    public V068AddressBookSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                ACCOUNT_NODE_REL_STATE_ID,
                ACCOUNT_NODE_REL_STATE_KEY,
                AccountID.PROTOBUF,
                NodeId.PROTOBUF,
                MAX_RELATIONS));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            final var nodeState = ctx.previousStates().get(NODES_STATE_ID);
            final var relState = ctx.newStates().get(ACCOUNT_NODE_REL_STATE_ID);

            // With Virtual MegaMap, nodeState.keys() is over **every leaf in state**.
            // Since we can't use it to iterate nodes directly, we iterate through potential
            // node IDs (0-99) to populate the relation map, which covers our production node count.
            for (int i = 0; i < 100; i++) {
                final var nodeId = EntityNumber.newBuilder().number(i).build();
                final var node = (Node) nodeState.get(nodeId);
                if (node != null && node.hasAccountId()) {
                    relState.put(
                            node.accountId(),
                            NodeId.newBuilder().id(node.nodeId()).build());
                }
            }
        }
    }
}
