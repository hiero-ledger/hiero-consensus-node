// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.TokenService.NAME;
import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewPendingAirdropOp extends UtilOp {

    private final String tokenName;
    private final String senderName;
    private final String receiverName;
    private final Consumer<AccountPendingAirdrop> observer;

    public ViewPendingAirdropOp(
            @NonNull final String tokenName,
            @NonNull final String senderName,
            @NonNull final String receiverName,
            @NonNull final Consumer<AccountPendingAirdrop> observer) {
        this.tokenName = requireNonNull(tokenName);
        this.receiverName = requireNonNull(receiverName);
        this.senderName = requireNonNull(senderName);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(NAME);
        final ReadableKVState<PendingAirdropId, AccountPendingAirdrop> readableAirdropState =
                readableStates.get(AIRDROPS_STATE_ID);
        final var pendingAirdropId = PendingAirdropId.newBuilder()
                .receiverId(toPbj(TxnUtils.asId(receiverName, spec)))
                .senderId(toPbj(TxnUtils.asId(senderName, spec)))
                .fungibleTokenType(toPbj(TxnUtils.asTokenId(tokenName, spec)))
                .build();
        final var accountPendingAirdrop = readableAirdropState.get(pendingAirdropId);
        observer.accept(accountPendingAirdrop);
        return false;
    }
}
