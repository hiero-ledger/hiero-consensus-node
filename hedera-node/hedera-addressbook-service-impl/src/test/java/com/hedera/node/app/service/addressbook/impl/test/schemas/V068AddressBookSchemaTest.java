// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_LABEL;
import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema;
import com.hedera.node.app.services.MigrationContextImpl;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V068AddressBookSchemaTest {
    private WritableStates newStates;
    private AccountID accountId;
    private Node node;

    private ReadableStates oldStates;
    private Configuration config;
    private SemanticVersion previousVersion;

    @Mock
    private StartupNetworks startupNetworks;

    @BeforeEach
    void before() {
        // initialize and populate the node state (old state)
        final var nodeEntityNumber = EntityNumber.newBuilder().number(1).build();
        accountId = AccountID.newBuilder().accountNum(1009).build();
        node = Node.newBuilder().accountId(accountId).build();
        final var nodeState = MapReadableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL)
                .value(nodeEntityNumber, node)
                .build();
        oldStates = MapReadableStates.builder().state(nodeState).build();
        // initialize empty account-node state (new state)
        final var accountNodeRelState = MapWritableKVState.<AccountID, ProtoLong>builder(
                        ACCOUNT_NODE_REL_STATE_ID, ACCOUNT_NODE_REL_STATE_LABEL)
                .build();
        newStates = MapWritableStates.builder().state(accountNodeRelState).build();
        // config and previous version
        config = HederaTestConfigBuilder.createConfig();
        previousVersion =
                SemanticVersion.newBuilder().major(0).minor(67).patch(0).build();
    }

    @Test
    void populateAccountNodeRelationMap() {
        // migrate account node rel map
        final var schema = new V068AddressBookSchema();
        final var migrationContext = new MigrationContextImpl(
                oldStates, newStates, config, config, previousVersion, 100L, new HashMap<>(), startupNetworks);
        schema.migrate(migrationContext);

        // assert that the map is updated
        final var accountNodeRelations = newStates.<AccountID, NodeId>get(ACCOUNT_NODE_REL_STATE_ID);
        assertThat(accountNodeRelations.isModified()).isTrue();
        // assert values are correct
        final var nodeIdInState = accountNodeRelations.get(accountId);
        assertThat(nodeIdInState.id()).isEqualTo(node.nodeId());
    }
}
