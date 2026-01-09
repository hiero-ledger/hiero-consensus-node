// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Removes the selected node or nodes specified by the {@link NodeSelector} and refreshes the
 * {@link SubProcessNetwork} roster.
 */
public class RemoveNodeOp extends UtilOp {
    private final NodeSelector selector;
    private final boolean refreshOverrides;

    public RemoveNodeOp(@NonNull final NodeSelector selector) {
        this(selector, true);
    }

    public RemoveNodeOp(@NonNull final NodeSelector selector, final boolean refreshOverrides) {
        this.selector = Objects.requireNonNull(selector);
        this.refreshOverrides = refreshOverrides;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalStateException("Can only remove nodes from a SubProcessNetwork");
        }
        if (refreshOverrides) {
            subProcessNetwork.removeNode(selector);
        } else {
            subProcessNetwork.removeNodeWithoutOverrides(selector);
        }
        return false;
    }
}
