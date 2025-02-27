// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Defines the criteria by which to select {@link HederaNode}s from a {@link HederaNetwork}.
 */
public interface NodeSelector extends Predicate<HederaNode> {
    /**
     * Returns true if the given node should be selected.
     *
     * @param node the input argument
     * @return true if the node should be selected.
     */
    @Override
    boolean test(@NonNull HederaNode node);

    /** Gets a {@link NodeSelector} that selects nodes by name in a case-insensitive way, such as Alice or Bob.  */
    static NodeSelector byName(@NonNull final String name) {
        return new NodeSelector() {
            @Override
            public boolean test(@NonNull final HederaNode node) {
                return name.equalsIgnoreCase(node.getName());
            }

            @Override
            public String toString() {
                return "by name '" + name + "'";
            }
        };
    }

    /** Gets a {@link NodeSelector} that selects nodes by operator account ID. Does not work with aliases. */
    static NodeSelector byOperatorAccountId(@NonNull final AccountID operatorAccountID) {
        return new NodeSelector() {
            @Override
            public boolean test(@NonNull final HederaNode node) {
                return operatorAccountID.equals(node.getAccountId());
            }

            @Override
            public String toString() {
                return "by operator account id '0.0." + operatorAccountID.accountNumOrThrow() + "'";
            }
        };
    }

    /** Gets a {@link NodeSelector} that selects nodes by nodeId in a case-insensitive way */
    static NodeSelector byNodeId(final long nodeId) {
        return new NodeSelector() {
            @Override
            public boolean test(@NonNull final HederaNode node) {
                return node.getNodeId() == nodeId;
            }

            @Override
            public String toString() {
                return "by nodeId '" + nodeId + "'";
            }
        };
    }

    /** Gets a {@link NodeSelector} that excludes nodes by nodeId in a case-insensitive way */
    static NodeSelector exceptNodeIds(@NonNull final long... nodeIds) {
        requireNonNull(nodeIds);
        return new NodeSelector() {
            @Override
            public boolean test(@NonNull final HederaNode node) {
                return Arrays.stream(nodeIds).noneMatch(l -> l == node.getNodeId());
            }

            @Override
            public String toString() {
                return "excluding nodeIds '" + Arrays.toString(nodeIds) + "'";
            }
        };
    }

    /** Gets a {@link NodeSelector} that selects all nodes */
    static NodeSelector allNodes() {
        return new NodeSelector() {
            @Override
            public boolean test(@NonNull final HederaNode node) {
                return true;
            }

            @Override
            public String toString() {
                return "including all nodes";
            }
        };
    }
}
