// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.addressbook.NodeIdList;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V068AddressBookSchema extends Schema<SemanticVersion> {
    public static final String ACCOUNT_NODE_REL_KEY = "ACCOUNT_NODE_REL";
    public static final int ACCOUNT_NODE_REL_ID =
            StateKey.KeyOneOfType.ADDRESSBOOKSERVICE_I_ACCOUNT_NODE_REL.protoOrdinal();
    public static final String ACCOUNT_NODE_REL_LABEL = computeLabel(AddressBookService.NAME, ACCOUNT_NODE_REL_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();

    private static final long MAX_RELATIONS = 100L;

    public V068AddressBookSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                ACCOUNT_NODE_REL_ID, ACCOUNT_NODE_REL_KEY, AccountID.PROTOBUF, NodeIdList.PROTOBUF, MAX_RELATIONS));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            final var nodeState = ctx.previousStates().get(NODES_STATE_ID);
            final var relState = ctx.newStates().get(ACCOUNT_NODE_REL_ID);
            final var keyIterator = nodeState.keys();
            while (keyIterator.hasNext()) {
                final var node = (Node) nodeState.get(keyIterator.next());
                relState.put(
                        node.accountId(),
                        NodeIdList.newBuilder().nodeId(node.nodeId()).build());
            }
        }
    }
}
