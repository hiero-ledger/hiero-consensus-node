// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.TokenDeleteUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenDelete extends HapiTxnOp<HapiTokenDelete> {
    static final Logger log = LogManager.getLogger(HapiTokenDelete.class);

    private boolean shouldPurge = false;
    private final String token;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenDelete;
    }

    public HapiTokenDelete(final String token) {
        this.token = token;
    }

    public HapiTokenDelete purging() {
        shouldPurge = true;
        return this;
    }

    @Override
    protected HapiTokenDelete self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.TokenDelete, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        return TokenDeleteUsage.newEstimate(txn, new TxnUsageEstimator(suFrom(svo), txn, ESTIMATOR_UTILS))
                .get();
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tId = TxnUtils.asTokenId(token, spec);
        final TokenDeleteTransactionBody opBody = spec.txns()
                .<TokenDeleteTransactionBody, TokenDeleteTransactionBody.Builder>body(
                        TokenDeleteTransactionBody.class, b -> b.setToken(tId));
        return b -> b.setTokenDeletion(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.ADMIN));
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (!shouldPurge || actualStatus != SUCCESS) {
            return;
        }
        final var registry = spec.registry();
        registry.forgetName(token);
        registry.forgetSymbol(token);
        registry.forgetTokenId(token);
        registry.forgetTreasury(token);
        forgetRoleKeys(registry);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper().add("token", token);
        return helper;
    }

    private void forgetRoleKeys(HapiSpecRegistry registry) {
        for (KeyRole role : KeyRole.values()) {
            if (registry.hasRoleKey(token, role)) {
                registry.forgetRoleKey(token, role);
            }
        }
    }
}
