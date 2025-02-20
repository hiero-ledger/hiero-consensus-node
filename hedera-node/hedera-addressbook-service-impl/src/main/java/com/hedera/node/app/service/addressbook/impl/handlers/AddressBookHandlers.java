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

    /**
     * Constructor for AddressBookHandlers.
     */
    @Inject
    public AddressBookHandlers(
            @NonNull final NodeCreateHandler nodeCreateHandler,
            @NonNull final NodeDeleteHandler nodeDeleteHandler,
            @NonNull final NodeUpdateHandler nodeUpdateHandler) {
        this.nodeCreateHandler = Objects.requireNonNull(nodeCreateHandler, "nodeCreateHandler must not be null");
        this.nodeDeleteHandler = Objects.requireNonNull(nodeDeleteHandler, "nodeDeleteHandler must not be null");
        this.nodeUpdateHandler = Objects.requireNonNull(nodeUpdateHandler, "nodeUpdateHandler must not be null");
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
}
