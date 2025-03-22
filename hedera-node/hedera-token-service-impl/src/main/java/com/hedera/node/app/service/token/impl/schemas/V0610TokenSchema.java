// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.node.app.service.token.impl.handlers.TokenClaimAirdropHandler.asAccountAmount;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.spi.workflows.SystemContext;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class V0610TokenSchema extends Schema {
    public static final String NODE_REWARDS_KEY = "NODE_REWARDS";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(61).patch(0).build();

    public V0610TokenSchema() {
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(NODE_REWARDS_KEY, NodeRewards.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis()) {
            final var nodeRewardsState = ctx.newStates().getSingleton(NODE_REWARDS_KEY);
            nodeRewardsState.put(NodeRewards.DEFAULT);
        }
    }

    /**
     * Dispatches a synthetic node reward crypto transfer.
     * @param systemContext The system context.
     * @param activeNodeAccountIds The list of node account ids.
     * @param payerId The payer account id.
     * @param creditPerNode The credit per node.
     */
    public static void dispatchSynthNodeRewards(
            @NonNull final SystemContext systemContext,
            @NonNull final List<AccountID> activeNodeAccountIds,
            @NonNull final AccountID payerId,
            final long creditPerNode) {
        final long payerDebit = -(creditPerNode * activeNodeAccountIds.size());
        final var amounts = new ArrayList<>(accountAmountsFrom(activeNodeAccountIds, creditPerNode));
        amounts.add(asAccountAmount(payerId, payerDebit));

        systemContext.dispatchAdmin(b -> b.memo("Synthetic node rewards")
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(amounts)
                                .build()))
                .build());
    }

    /**
     * Creates a list of {@link AccountAmount} from a list of {@link AccountID} and an amount.
     * @param nodeAccountIds The list of node account ids.
     * @param amount The amount.
     * @return The list of {@link AccountAmount}.
     */
    private static List<AccountAmount> accountAmountsFrom(
            @NonNull final List<AccountID> nodeAccountIds, final long amount) {
        return nodeAccountIds.stream()
                .map(nodeAccountId -> asAccountAmount(nodeAccountId, amount))
                .toList();
    }
}
