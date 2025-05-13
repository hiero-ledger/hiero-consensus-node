// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.services.bdd.spec.PropertySource.asAccountString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenFreezeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class HapiTokenFreeze extends HapiTxnOp<HapiTokenFreeze> {
    private final String token;
    private String account;
    private String alias = null;
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenFreezeAccount;
    }

    public HapiTokenFreeze(final String token, final String account) {
        this(token, account, ReferenceType.REGISTRY_NAME);
    }

    public HapiTokenFreeze(final String token, final String reference, final ReferenceType referenceType) {
        this.token = token;
        this.referenceType = referenceType;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            this.alias = reference;
        } else {
            this.account = reference;
        }
    }

    @Override
    protected HapiTokenFreeze self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(HederaFunctionality.TokenFreezeAccount, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        final var tokenFreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenFreezeUsageFrom();
        final var baseTransactionMeta =
                new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();
        tokenOpsUsage.tokenFreezeUsage(suFrom(svo), baseTransactionMeta, tokenFreezeMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        AccountID aId;
        if (referenceType == ReferenceType.REGISTRY_NAME) {
            aId = TxnUtils.asId(account, spec);
        } else {
            aId = spec.registry().keyAliasIdFor(spec, alias);
            account = asAccountString(aId);
        }
        final var tId = TxnUtils.asTokenId(token, spec);
        final TokenFreezeAccountTransactionBody opBody = spec.txns()
                .<TokenFreezeAccountTransactionBody, TokenFreezeAccountTransactionBody.Builder>body(
                        TokenFreezeAccountTransactionBody.class, b -> {
                            b.setAccount(aId);
                            b.setToken(tId);
                        });
        return b -> b.setTokenFreeze(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.FREEZE));
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {}

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper()
                .add("token", token)
                .add("account", account)
                .add("alias", alias);
        return helper;
    }
}
