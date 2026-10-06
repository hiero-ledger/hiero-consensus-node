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
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenWipeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenWipe extends HapiTxnOp<HapiTokenWipe> {
    static final Logger log = LogManager.getLogger(HapiTokenWipe.class);

    private String account;
    private final String token;
    private long amount;
    private final List<Long> serialNumbers;
    private final SubType subType;
    private String alias = null;
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenAccountWipe;
    }

    public HapiTokenWipe(final String token, final String account, final long amount) {
        this(token, account, amount, ReferenceType.REGISTRY_NAME);
    }

    public HapiTokenWipe(
            final String token, final String reference, final long amount, final ReferenceType referenceType) {
        this.token = token;
        this.referenceType = referenceType;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            this.alias = reference;
        } else {
            this.account = reference;
        }
        this.amount = amount;
        this.serialNumbers = new ArrayList<>();
        this.subType = SubType.TOKEN_FUNGIBLE_COMMON;
    }

    public HapiTokenWipe(final String token, final String reference, final List<Long> serialNumbers) {
        this(token, reference, serialNumbers, ReferenceType.REGISTRY_NAME);
    }

    public HapiTokenWipe(
            final String token,
            final String reference,
            final List<Long> serialNumbers,
            final ReferenceType referenceType) {
        this.token = token;
        this.referenceType = referenceType;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            this.alias = reference;
        } else {
            this.account = reference;
        }
        this.serialNumbers = serialNumbers;
        this.subType = SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
    }

    @Override
    protected HapiTokenWipe self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tId = TxnUtils.asTokenId(token, spec);
        AccountID aId;
        if (referenceType == ReferenceType.REGISTRY_NAME) {
            aId = TxnUtils.asId(account, spec);
        } else {
            aId = spec.registry().keyAliasIdFor(spec, alias);
            account = asAccountString(aId);
        }
        final TokenWipeAccountTransactionBody opBody = spec.txns()
                .<TokenWipeAccountTransactionBody, TokenWipeAccountTransactionBody.Builder>body(
                        TokenWipeAccountTransactionBody.class, b -> {
                            b.setToken(tId);
                            b.setAccount(aId);
                            b.setAmount(amount);
                            b.addAllSerialNumbers(serialNumbers);
                        });
        return b -> b.setTokenWipe(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.WIPE));
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        /* no-op. */
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper()
                .add("token", token)
                .add("account", account)
                .add("amount", amount)
                .add("serialNumbers", serialNumbers)
                .add("alias", alias);
    }
}
