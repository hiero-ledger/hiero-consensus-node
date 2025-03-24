// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A system transaction with a submitter ID and a software version. The submitter ID is not included with the
 * transaction, it is determined by the event that the transaction is contained within. This is intentional, as it makes
 * it impossible for a transaction to lie and claim to be submitted by a node that did not actually submit it.
 *
 * @param submitterId     the ID of the node that submitted the transaction
 * @param softwareVersion the software version of the event that contained the transaction
 * @param transaction     the transaction
 * @param <T>             the type of transaction
 */
public record ScopedSystemTransaction<T>(
        @NonNull NodeId submitterId, @Nullable SemanticVersion softwareVersion, @NonNull T transaction) {}
