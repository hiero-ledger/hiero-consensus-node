// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;

/**
 * Scope shared by a contract execution and the executions nested in it through child dispatches. Its owner, the
 * outermost execution, is the only one that checks the creation throttle for a commit without creations. Until the
 * owner commits, the creations of a nested execution can still be rolled back, so the creation throttle capacity they
 * claimed is given back if they are.
 */
@TransactionScope
public class StorageAccountingScope {
    private final HandleContext.SavepointStack stack;
    // The scope of the owner, if this execution is nested in it
    @Nullable
    private final StorageAccountingScope ownerScope;

    private boolean ownerCommitted = false;

    @Inject
    public StorageAccountingScope(@NonNull final HandleContext context) {
        this.stack = requireNonNull(context).savepointStack();
        this.ownerScope = stack.findInLineage(StorageAccountingScope.class);
    }

    /**
     * Returns whether this execution owns its scope, as the outermost execution in it.
     *
     * @return whether this execution owns its scope
     */
    public boolean isOwner() {
        return ownerScope == null;
    }

    /**
     * If this execution owns its scope, opens the scope to executions nested in it.
     */
    public void open() {
        if (isOwner()) {
            stack.attach(StorageAccountingScope.class, this);
        }
    }

    /**
     * If this execution owns its scope, closes the scope.
     */
    public void close() {
        if (isOwner()) {
            stack.detach(StorageAccountingScope.class);
        }
    }

    /**
     * If this execution owns its scope, records that it has committed. From then on, the creations of the executions
     * nested in it count against the creation throttle like its own.
     */
    public void markCommitted() {
        if (isOwner()) {
            ownerCommitted = true;
        }
    }

    /**
     * Returns whether the owner of this scope has committed.
     *
     * @return whether the owner of this scope has committed
     */
    public boolean ownerHasCommitted() {
        return ownerScope == null ? ownerCommitted : ownerScope.ownerHasCommitted();
    }
}
