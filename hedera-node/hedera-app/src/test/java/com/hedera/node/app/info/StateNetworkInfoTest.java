// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.schemas.V0590EntityIdSchema;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class StateNetworkInfoTest {
    @Mock(strictness = LENIENT)
    private State state;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private ReadableKVState<EntityNumber, Node> nodeState;

    @Mock
    private ReadableSingletonState<EntityCounts> entityCountsState;

    @Mock
    private ReadableStates readableStates;

    private static final long SELF_ID = 1L;
    private final Roster activeRoster = new Roster(List.of(
            RosterEntry.newBuilder().nodeId(SELF_ID).weight(10).build(),
            RosterEntry.newBuilder().nodeId(3L).weight(20).build()));

    private StateNetworkInfo networkInfo;

    @BeforeEach
    public void setUp() {
        when(configProvider.getConfiguration())
                .thenReturn(new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1));
        when(state.getReadableStates(EntityIdService.NAME)).thenReturn(readableStates);
        when(readableStates.<EntityCounts>getSingleton(V0590EntityIdSchema.ENTITY_COUNTS_KEY))
                .thenReturn(entityCountsState);
        when(entityCountsState.get())
                .thenReturn(EntityCounts.newBuilder().numNodes(1L).build());
        when(state.getReadableStates(AddressBookService.NAME)).thenReturn(readableStates);
        when(readableStates.<EntityNumber, Node>get("NODES")).thenReturn(nodeState);
        when(state.getReadableStates(PlatformStateService.NAME)).thenReturn(readableStates);
        networkInfo = new StateNetworkInfo(SELF_ID, state, activeRoster, configProvider, () -> Network.DEFAULT);
    }

    @Test
    public void testLedgerId() {
        assertEquals("00", networkInfo.ledgerId().toHex());
    }

    @Test
    public void testSelfNodeInfo() {
        final var selfNode = networkInfo.selfNodeInfo();
        assertNotNull(selfNode);
        assertEquals(SELF_ID, selfNode.nodeId());
    }

    @Test
    public void testAddressBook() {
        final var addressBook = networkInfo.addressBook();
        assertNotNull(addressBook);
        assertEquals(2, addressBook.size());
    }

    @Test
    public void testNodeInfo() {
        final var nodeInfo = networkInfo.nodeInfo(SELF_ID);
        assertNotNull(nodeInfo);
        assertEquals(SELF_ID, nodeInfo.nodeId());
        assertNull(networkInfo.nodeInfo(999L));
    }

    @Test
    public void testContainsNode() {
        assertTrue(networkInfo.containsNode(SELF_ID));
        assertFalse(networkInfo.containsNode(999L));
    }

    @Test
    public void testUpdateFrom() {
        final var placeholderNode =
                Node.newBuilder().accountId(AccountID.DEFAULT).build();
        when(nodeState.get(any(EntityNumber.class))).thenReturn(placeholderNode);

        networkInfo.updateFrom(state);
        assertEquals(2, networkInfo.addressBook().size());
    }

    @Test
    public void testBuildNodeInfoMapNodeNotFound() {
        when(nodeState.get(any(EntityNumber.class))).thenReturn(null);

        StateNetworkInfo networkInfo =
                new StateNetworkInfo(SELF_ID, state, activeRoster, configProvider, () -> Network.DEFAULT);
        final var nodeInfo = networkInfo.nodeInfo(SELF_ID);

        assertNotNull(nodeInfo);
        assertEquals(SELF_ID + 3, nodeInfo.accountId().accountNum());
    }
}
