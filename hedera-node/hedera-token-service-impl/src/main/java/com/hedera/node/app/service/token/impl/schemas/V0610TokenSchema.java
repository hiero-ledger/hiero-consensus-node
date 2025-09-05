// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.node.app.service.token.impl.handlers.TokenClaimAirdropHandler.asAccountAmount;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.token.TokenService;
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
    public static final int NODE_REWARDS_STATE_ID = SingletonType.TOKENSERVICE_I_NODE_REWARDS.protoOrdinal();
    public static final String NODE_REWARDS_STATE_LABEL = computeLabel(TokenService.NAME, NODE_REWARDS_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(61).patch(0).build();

    public V0610TokenSchema() {
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(NODE_REWARDS_STATE_ID, NODE_REWARDS_KEY, NodeRewards.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var previousStates = ctx.previousStates();
        if (ctx.isGenesis() || !previousStates.contains(NODE_REWARDS_STATE_ID)) {
            final var nodeRewardsState = ctx.newStates().getSingleton(NODE_REWARDS_STATE_ID);
            nodeRewardsState.put(NodeRewards.DEFAULT);
        }
    }

    /**
     * Dispatches a synthetic node reward crypto transfer for active nodes.
     *
     * @param systemContext        The system context.
     * @param activeNodeAccountIds The list of node account ids.
     * @param payerId              The payer account id.
     * @param activeNodeCredit     The credit per active node.
     */
    public static void dispatchSynthNodeRewards(
            @NonNull final SystemContext systemContext,
            @NonNull final List<AccountID> activeNodeAccountIds,
            @NonNull final AccountID payerId,
            final long activeNodeCredit) {
        dispatchSynthNodeRewards(systemContext, activeNodeAccountIds, payerId, activeNodeCredit, List.of(), 0L);
    }

    /**
     * Dispatches a synthetic node reward crypto transfer for active and inactive nodes.
     *
     * @param systemContext          The system context.
     * @param activeNodeAccountIds   The list of node account ids.
     * @param payerId                The payer account id.
     * @param activeNodeCredit       The credit per active node.
     * @param inactiveNodeAccountIds The list of inactive node account ids.
     * @param inactiveNodeCredit     The credit for inactive nodes, which will be the minimum node reward.
     */
    public static void dispatchSynthNodeRewards(
            @NonNull final SystemContext systemContext,
            @NonNull final List<AccountID> activeNodeAccountIds,
            @NonNull final AccountID payerId,
            final long activeNodeCredit,
            @NonNull final List<AccountID> inactiveNodeAccountIds,
            final long inactiveNodeCredit) {
        if (activeNodeCredit <= 0L && inactiveNodeCredit <= 0L) {
            return;
        }
        final long payerDebit = -((activeNodeCredit * activeNodeAccountIds.size())
                + (inactiveNodeCredit * inactiveNodeAccountIds.size()));
        final var amounts = new ArrayList<AccountAmount>();
        if (activeNodeCredit > 0L) {
            amounts.addAll(accountAmountsFrom(activeNodeAccountIds, activeNodeCredit));
        }
        if (inactiveNodeCredit > 0L) {
            amounts.addAll(accountAmountsFrom(inactiveNodeAccountIds, inactiveNodeCredit));
        }
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
     *
     * @param nodeAccountIds The list of node account ids.
     * @param amount         The amount.
     * @return The list of {@link AccountAmount}.
     */
    private static List<AccountAmount> accountAmountsFrom(
            @NonNull final List<AccountID> nodeAccountIds, final long amount) {
        return nodeAccountIds.stream()
                .map(nodeAccountId -> asAccountAmount(nodeAccountId, amount))
                .toList();
    }
}
