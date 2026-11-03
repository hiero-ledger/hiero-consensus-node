// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Storage accounting shared by a contract execution and the executions nested in it through child dispatches. The
 * nested executions commit into the savepoints of the dispatches enclosing them, so only the owner (the outermost
 * execution) charges rent, persists storage and applies the creation throttle for the whole scope.
 */
@TransactionScope
public class StorageAccountingScope {
    private final HandleContext.SavepointStack stack;
    private final boolean owner;

    @Inject
    public StorageAccountingScope(@NonNull final HandleContext context) {
        this.stack = requireNonNull(context).savepointStack();
        this.owner = stack.findInLineage(StorageAccountingScope.class) == null;
    }

    /**
     * Returns whether this execution owns its scope, and so finalizes the storage effects of the whole scope.
     *
     * @return whether this execution owns its scope
     */
    public boolean isOwner() {
        return owner;
    }

    /**
     * If this execution owns its scope, opens the scope to executions nested in it.
     */
    public void open() {
        if (owner) {
            stack.attach(StorageAccountingScope.class, this);
        }
    }

    /**
     * If this execution owns its scope, closes the scope.
     */
    public void close() {
        if (owner) {
            stack.detach(StorageAccountingScope.class);
        }
    }
}
