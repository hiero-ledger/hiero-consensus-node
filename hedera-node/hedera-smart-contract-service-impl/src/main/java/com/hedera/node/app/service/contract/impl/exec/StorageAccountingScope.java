// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Scope shared by a contract execution and the executions nested in it through child dispatches. Its owner, the
 * outermost execution, is the only one that checks the creation throttle for a commit without creations.
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
     * Returns whether this execution owns its scope, as the outermost execution in it.
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
