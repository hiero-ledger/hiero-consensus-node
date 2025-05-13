// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.services.bdd.spec.PropertySource.asAccountString;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.TokenAssociateUsage;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractInfo;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenAssociate extends HapiTxnOp<HapiTokenAssociate> {
    static final Logger log = LogManager.getLogger(HapiTokenAssociate.class);

    public static final long DEFAULT_FEE = 100_000_000L;

    private String account;
    private List<String> tokens = new ArrayList<>();
    private Optional<ResponseCodeEnum[]> permissibleCostAnswerPrechecks = Optional.empty();
    private String alias = null;
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenAssociateToAccount;
    }

    public HapiTokenAssociate(String account, String... tokens) {
        this(account, ReferenceType.REGISTRY_NAME, tokens);
    }

    public HapiTokenAssociate(String reference, ReferenceType referenceType, String... tokens) {
        this.referenceType = referenceType;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            this.alias = reference;
        } else {
            this.account = reference;
        }
        this.tokens.addAll(List.of(tokens));
    }

    public HapiTokenAssociate(String account, List<String> tokens) {
        this.account = account;
        this.tokens.addAll(tokens);
    }

    @Override
    protected HapiTokenAssociate self() {
        return this;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        try {
            final long expiry = lookupExpiry(spec);
            FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
                var estimate = TokenAssociateUsage.newEstimate(
                                _txn, new TxnUsageEstimator(suFrom(svo), _txn, ESTIMATOR_UTILS))
                        .givenCurrentExpiry(expiry);
                return estimate.get();
            };
            return spec.fees()
                    .forActivityBasedOp(HederaFunctionality.TokenAssociateToAccount, metricsCalc, txn, numPayerKeys);
        } catch (Throwable ignore) {
            return DEFAULT_FEE;
        }
    }

    public HapiTokenAssociate hasCostAnswerPrecheckFrom(ResponseCodeEnum... prechecks) {
        permissibleCostAnswerPrechecks = Optional.of(prechecks);
        return self();
    }

    private long lookupExpiry(HapiSpec spec) throws Throwable {
        if (!spec.registry().hasContractId(account)) {
            HapiGetAccountInfo subOp;
            if (permissibleCostAnswerPrechecks.isPresent()) {
                subOp = getAccountInfo(account)
                        .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks.get())
                        .noLogging();
            } else {
                subOp = getAccountInfo(account).noLogging();
            }
            Optional<Throwable> error = subOp.execFor(spec);
            if (error.isPresent()) {
                if (!loggingOff) {
                    String message = String.format(
                            "Unable to look up current info for %s",
                            HapiPropertySource.asAccountString(spec.registry().getAccountID(account)));
                    log.warn(message, error.get());
                }
                throw error.get();
            }
            return subOp.getResponse()
                    .getCryptoGetInfo()
                    .getAccountInfo()
                    .getExpirationTime()
                    .getSeconds();
        } else {
            HapiGetContractInfo subOp = getContractInfo(account).noLogging();
            Optional<Throwable> error = subOp.execFor(spec);
            if (error.isPresent()) {
                if (!loggingOff) {
                    String message = String.format(
                            "Unable to look up current info for %s",
                            HapiPropertySource.asContractString(spec.registry().getContractId(account)));
                    log.warn(message, error.get());
                }
                throw error.get();
            }
            return subOp.getResponse()
                    .getContractGetInfo()
                    .getContractInfo()
                    .getExpirationTime()
                    .getSeconds();
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        AccountID aId;
        if (account != null && referenceType == ReferenceType.REGISTRY_NAME) {
            aId = TxnUtils.asId(account, spec);
        } else if (account != null) {
            aId = spec.registry().keyAliasIdFor(spec, alias);
            account = asAccountString(aId);
        }
        TokenAssociateTransactionBody opBody = spec.txns()
                .<TokenAssociateTransactionBody, TokenAssociateTransactionBody.Builder>body(
                        TokenAssociateTransactionBody.class, b -> {
                            if (account != null) {
                                b.setAccount(TxnUtils.asId(account, spec));
                            }
                            b.addAllTokens(tokens.stream()
                                    .map(lit -> TxnUtils.asTokenId(lit, spec))
                                    .toList());
                        });
        return b -> b.setTokenAssociate(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getKey(account));
    }

    @Override
    protected void updateStateOf(HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
        var registry = spec.registry();
        tokens.forEach(token -> registry.saveTokenRel(account, token));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper()
                .add("account", account)
                .add("tokens", tokens)
                .add("alias", alias);
        return helper;
    }
}
