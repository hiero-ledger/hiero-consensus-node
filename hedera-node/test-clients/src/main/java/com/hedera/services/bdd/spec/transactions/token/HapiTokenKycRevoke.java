// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenRevokeKycTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenKycRevoke extends HapiTxnOp<HapiTokenKycRevoke> {
    static final Logger log = LogManager.getLogger(HapiTokenKycRevoke.class);

    private final String token;
    private String account;
    private String alias = null;
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenRevokeKycFromAccount;
    }

    public HapiTokenKycRevoke(final String token, final String account) {
        this(token, account, ReferenceType.REGISTRY_NAME);
    }

    public HapiTokenKycRevoke(final String token, final String reference, ReferenceType referenceType) {
        this.token = token;
        this.referenceType = referenceType;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            this.alias = reference;
        } else {
            this.account = reference;
        }
    }

    @Override
    protected HapiTokenKycRevoke self() {
        return this;
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
        final TokenRevokeKycTransactionBody opBody = spec.txns()
                .<TokenRevokeKycTransactionBody, TokenRevokeKycTransactionBody.Builder>body(
                        TokenRevokeKycTransactionBody.class, b -> {
                            b.setAccount(aId);
                            b.setToken(tId);
                        });
        return b -> b.setTokenRevokeKyc(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.KYC));
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
