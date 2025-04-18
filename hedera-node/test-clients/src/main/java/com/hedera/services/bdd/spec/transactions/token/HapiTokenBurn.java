// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenBurnTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class HapiTokenBurn extends HapiTxnOp<HapiTokenBurn> {
    private long amount;
    private final String token;
    private final List<Long> serialNumbers;
    private final SubType subType;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenBurn;
    }

    public HapiTokenBurn(final String token, final long amount) {
        this.token = token;
        this.amount = amount;
        this.serialNumbers = new ArrayList<>();
        this.subType = SubType.TOKEN_FUNGIBLE_COMMON;
    }

    public HapiTokenBurn(final String token, final List<Long> serialNumbers) {
        this.token = token;
        this.serialNumbers = serialNumbers;
        this.subType = SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
    }

    public HapiTokenBurn(final String token, final List<Long> serialNumbers, final long amount) {
        this.token = token;
        this.amount = amount;
        this.serialNumbers = serialNumbers;
        this.subType = SubType.TOKEN_FUNGIBLE_COMMON;
    }

    @Override
    protected HapiTokenBurn self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(HederaFunctionality.TokenBurn, subType, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        final var tokenBurnMeta = TOKEN_OPS_USAGE_UTILS.tokenBurnUsageFrom(txn, subType);
        final var baseTransactionMeta =
                new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();
        tokenOpsUsage.tokenBurnUsage(suFrom(svo), baseTransactionMeta, tokenBurnMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tId = TxnUtils.asTokenId(token, spec);
        final TokenBurnTransactionBody opBody = spec.txns()
                .<TokenBurnTransactionBody, TokenBurnTransactionBody.Builder>body(TokenBurnTransactionBody.class, b -> {
                    b.setToken(tId);
                    b.setAmount(amount);
                    b.addAllSerialNumbers(serialNumbers);
                });
        return b -> b.setTokenBurn(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.SUPPLY));
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {}

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper =
                super.toStringHelper().add("token", token).add("amount", amount).add("serialNumbers", serialNumbers);
        return helper;
    }
}
