// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;

/**
 * Accounting shared by a contract execution and the executions nested in it through child dispatches. Each contract
 * creation counts against the creation throttle once, in the first commit that includes it.
 */
@TransactionScope
public class StorageAccountingScope {
    private final HandleContext.SavepointStack stack;
    private final StorageAccountingScope owner;
    // Only used by the owner
    private final Set<ContractID> throttledCreations = new HashSet<>();

    @Inject
    public StorageAccountingScope(@NonNull final HandleContext context) {
        this.stack = requireNonNull(context).savepointStack();
        final var enclosing = stack.findInLineage(StorageAccountingScope.class);
        this.owner = enclosing == null ? this : enclosing;
    }

    /**
     * Returns whether this execution owns its scope, as the outermost execution in it.
     *
     * @return whether this execution owns its scope
     */
    public boolean isOwner() {
        return owner == this;
    }

    /**
     * Returns the given creations that no commit in this scope has throttled yet, or empty if the commit needs no
     * throttle check. Without such creations, only an owner whose scope has throttled nothing checks.
     *
     * @param creations the contracts created in the scope of the committing execution
     * @return the creations to throttle, or empty if the commit needs no throttle check
     */
    public Optional<List<ContractID>> creationsToThrottle(@NonNull final List<ContractID> creations) {
        requireNonNull(creations);
        final var unthrottled = creations.stream()
                .filter(creation -> !owner.throttledCreations.contains(creation))
                .toList();
        final boolean checksWithoutCreations = isOwner() && owner.throttledCreations.isEmpty();
        return unthrottled.isEmpty() && !checksWithoutCreations ? Optional.empty() : Optional.of(unthrottled);
    }

    /**
     * Records that the given creations passed the creation throttle, so that enclosing commits do not throttle them.
     *
     * @param creations the creations that passed the creation throttle
     */
    public void creationsThrottled(@NonNull final List<ContractID> creations) {
        owner.throttledCreations.addAll(requireNonNull(creations));
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
}
