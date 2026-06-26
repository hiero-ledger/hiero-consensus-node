// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
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

    public HapiTokenAssociate hasCostAnswerPrecheckFrom(ResponseCodeEnum... prechecks) {
        permissibleCostAnswerPrechecks = Optional.of(prechecks);
        return self();
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
