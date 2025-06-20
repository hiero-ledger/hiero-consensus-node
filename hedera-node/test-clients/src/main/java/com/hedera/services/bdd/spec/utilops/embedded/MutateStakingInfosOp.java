// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * Allows the test author to mutate the node staking info.
 */
public class MutateStakingInfosOp extends UtilOp {
    private final String node;
    private final Consumer<StakingNodeInfo.Builder> mutation;

    public MutateStakingInfosOp(@NonNull final String node, @NonNull final Consumer<StakingNodeInfo.Builder> mutation) {
        this.node = requireNonNull(node);
        this.mutation = requireNonNull(mutation);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        final var nodes = spec.embeddedStakingInfosOrThrow();
        final var targetId = toPbj(TxnUtils.asNodeId(node, spec));
        final var node = requireNonNull(nodes.get(targetId));
        final var builder = node.copyBuilder();
        mutation.accept(builder);
        nodes.put(targetId, builder.build());
        spec.commitEmbeddedState();
        return false;
    }
}
