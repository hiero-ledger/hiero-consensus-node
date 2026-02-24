// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to hold all the addressbook handlers.
 */
@Singleton
public class AddressBookHandlers {

    private final NodeCreateHandler nodeCreateHandler;

    private final NodeDeleteHandler nodeDeleteHandler;

    private final NodeUpdateHandler nodeUpdateHandler;

    private final RegisteredNodeCreateHandler registeredNodeCreateHandler;
    private final RegisteredNodeUpdateHandler registeredNodeUpdateHandler;
    private final RegisteredNodeDeleteHandler registeredNodeDeleteHandler;

    /**
     * Constructor for AddressBookHandlers.
     */
    @Inject
    public AddressBookHandlers(
            @NonNull final NodeCreateHandler nodeCreateHandler,
            @NonNull final NodeDeleteHandler nodeDeleteHandler,
            @NonNull final NodeUpdateHandler nodeUpdateHandler,
            @NonNull final RegisteredNodeCreateHandler registeredNodeCreateHandler,
            @NonNull final RegisteredNodeUpdateHandler registeredNodeUpdateHandler,
            @NonNull final RegisteredNodeDeleteHandler registeredNodeDeleteHandler) {
        this.nodeCreateHandler = Objects.requireNonNull(nodeCreateHandler, "nodeCreateHandler must not be null");
        this.nodeDeleteHandler = Objects.requireNonNull(nodeDeleteHandler, "nodeDeleteHandler must not be null");
        this.nodeUpdateHandler = Objects.requireNonNull(nodeUpdateHandler, "nodeUpdateHandler must not be null");
        this.registeredNodeCreateHandler =
                Objects.requireNonNull(registeredNodeCreateHandler, "registeredNodeCreateHandler must not be null");
        this.registeredNodeUpdateHandler =
                Objects.requireNonNull(registeredNodeUpdateHandler, "registeredNodeUpdateHandler must not be null");
        this.registeredNodeDeleteHandler =
                Objects.requireNonNull(registeredNodeDeleteHandler, "registeredNodeDeleteHandler must not be null");
    }

    /**
     * Get the nodeCreateHandler.
     *
     * @return the nodeCreateHandler
     */
    public NodeCreateHandler nodeCreateHandler() {
        return nodeCreateHandler;
    }

    /**
     * Get the nodeDeleteHandler.
     *
     * @return the nodeDeleteHandler
     */
    public NodeDeleteHandler nodeDeleteHandler() {
        return nodeDeleteHandler;
    }

    /**
     * Get the nodeUpdateHandler.
     *
     * @return the nodeUpdateHandler
     */
    public NodeUpdateHandler nodeUpdateHandler() {
        return nodeUpdateHandler;
    }

    public RegisteredNodeCreateHandler registeredNodeCreateHandler() {
        return registeredNodeCreateHandler;
    }

    public RegisteredNodeUpdateHandler registeredNodeUpdateHandler() {
        return registeredNodeUpdateHandler;
    }

    public RegisteredNodeDeleteHandler registeredNodeDeleteHandler() {
        return registeredNodeDeleteHandler;
    }
}
