// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

/**
 * Notified by the registered-node transaction handlers whenever a row in the registered-node state map is added,
 * updated, or removed.
 *
 * <p><b>Threading contract.</b> {@link #onRegisteredNodeChanged()} is invoked synchronously on the consensus
 * handle thread, immediately after the mutating handler persists its change to the savepoint stack. Implementations
 * <b>MUST</b> be O(1) and side-effect free with respect to state: do not read state, do not block, do not perform
 * I/O. The expected usage is to flip a flag and have the actual work happen on a later, safer thread (for example,
 * when the block closes and the savepoint changes have been committed).
 *
 * <p>The handlers do not pass the modified registered-node id. Listeners that need to know which nodes changed
 * should re-scan the relevant state once they observe a committed view.
 */
@FunctionalInterface
public interface RegisteredNodeChangeListener {
    /**
     * Invoked once per successful registered-node mutation (create / update / delete).
     */
    void onRegisteredNodeChanged();
}
