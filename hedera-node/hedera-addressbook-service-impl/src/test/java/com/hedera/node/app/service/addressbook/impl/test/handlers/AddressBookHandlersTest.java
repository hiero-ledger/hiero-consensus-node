// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.addressbook.impl.handlers.AddressBookHandlers;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeDeleteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeUpdateHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeDeleteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeUpdateHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddressBookHandlersTest {
    private NodeCreateHandler nodeCreateHandler;

    private NodeDeleteHandler nodeDeleteHandler;

    private NodeUpdateHandler nodeUpdateHandler;

    private RegisteredNodeCreateHandler registeredNodeCreateHandler;

    private RegisteredNodeUpdateHandler registeredNodeUpdateHandler;

    private RegisteredNodeDeleteHandler registeredNodeDeleteHandler;

    private AddressBookHandlers addressBookHandlers;

    @BeforeEach
    public void setUp() {
        nodeCreateHandler = mock(NodeCreateHandler.class);
        nodeDeleteHandler = mock(NodeDeleteHandler.class);
        nodeUpdateHandler = mock(NodeUpdateHandler.class);
        registeredNodeCreateHandler = mock(RegisteredNodeCreateHandler.class);
        registeredNodeUpdateHandler = mock(RegisteredNodeUpdateHandler.class);
        registeredNodeDeleteHandler = mock(RegisteredNodeDeleteHandler.class);

        addressBookHandlers = new AddressBookHandlers(
                nodeCreateHandler,
                nodeDeleteHandler,
                nodeUpdateHandler,
                registeredNodeCreateHandler,
                registeredNodeUpdateHandler,
                registeredNodeDeleteHandler);
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

    @Test
    void registeredNodeCreateHandlerReturnsCorrectInstance() {
        assertEquals(
                registeredNodeCreateHandler,
                addressBookHandlers.registeredNodeCreateHandler(),
                "registeredNodeCreateHandler does not return correct instance");
    }

    @Test
    void registeredNodeUpdateHandlerReturnsCorrectInstance() {
        assertEquals(
                registeredNodeUpdateHandler,
                addressBookHandlers.registeredNodeUpdateHandler(),
                "registeredNodeUpdateHandler does not return correct instance");
    }

    @Test
    void registeredNodeDeleteHandlerReturnsCorrectInstance() {
        assertEquals(
                registeredNodeDeleteHandler,
                addressBookHandlers.registeredNodeDeleteHandler(),
                "registeredNodeDeleteHandler does not return correct instance");
    }
}
