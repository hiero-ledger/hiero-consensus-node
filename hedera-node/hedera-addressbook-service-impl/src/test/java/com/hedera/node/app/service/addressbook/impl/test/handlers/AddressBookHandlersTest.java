// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.addressbook.impl.handlers.AddressBookHandlers;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeDeleteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeUpdateHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddressBookHandlersTest {
    private NodeCreateHandler nodeCreateHandler;

    private NodeDeleteHandler nodeDeleteHandler;

    private NodeUpdateHandler nodeUpdateHandler;

    private AddressBookHandlers addressBookHandlers;

    @BeforeEach
    public void setUp() {
        nodeCreateHandler = mock(NodeCreateHandler.class);
        nodeDeleteHandler = mock(NodeDeleteHandler.class);
        nodeUpdateHandler = mock(NodeUpdateHandler.class);

        addressBookHandlers = new AddressBookHandlers(nodeCreateHandler, nodeDeleteHandler, nodeUpdateHandler);
    }

    @Test
    void nodeCreateHandlerReturnsCorrectInstance() {
        assertEquals(
                nodeCreateHandler,
                addressBookHandlers.nodeCreateHandler(),
                "nodeCreateHandler does not return correct instance");
    }

    @Test
    void nodeDeleteHandlerReturnsCorrectInstance() {
        assertEquals(
                nodeDeleteHandler,
                addressBookHandlers.nodeDeleteHandler(),
                "nodeDeleteHandler does not return correct instance");
    }

    @Test
    void nodeUpdateHandlerReturnsCorrectInstance() {
        assertEquals(
                nodeUpdateHandler,
                addressBookHandlers.nodeUpdateHandler(),
                "nodeUpdateHandler does not return correct instance");
    }
}
