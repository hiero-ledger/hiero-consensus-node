// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.services.bdd.spec.PropertySource.asAccountString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.TokenDissociateUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenDissociateTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenDissociate extends HapiTxnOp<HapiTokenDissociate> {
    static final Logger log = LogManager.getLogger(HapiTokenDissociate.class);

    private String account;
    private final List<String> tokens = new ArrayList<>();
    private String alias = null;
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenDissociateFromAccount;
    }

    public HapiTokenDissociate(final String account, final String... tokens) {
        this(account, ReferenceType.REGISTRY_NAME, tokens);
    }

    public HapiTokenDissociate(final String reference, ReferenceType referenceType, final String... tokens) {
        this.referenceType = referenceType;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            this.alias = reference;
        } else {
            this.account = reference;
        }
        this.tokens.addAll(List.of(tokens));
    }

    @Override
    protected HapiTokenDissociate self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.TokenDissociateFromAccount, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        return TokenDissociateUsage.newEstimate(txn, new TxnUsageEstimator(suFrom(svo), txn, ESTIMATOR_UTILS))
                .get();
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
        final TokenDissociateTransactionBody opBody = spec.txns()
                .<TokenDissociateTransactionBody, TokenDissociateTransactionBody.Builder>body(
                        TokenDissociateTransactionBody.class, b -> {
                            b.setAccount(aId);
                            b.addAllTokens(tokens.stream()
                                    .map(lit -> TxnUtils.asTokenId(lit, spec))
                                    .collect(toList()));
                        });
        return b -> b.setTokenDissociate(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getKey(account));
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {}

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper()
                .add("account", account)
                .add("tokens", tokens)
                .add("alias", alias);
        return helper;
    }
}
