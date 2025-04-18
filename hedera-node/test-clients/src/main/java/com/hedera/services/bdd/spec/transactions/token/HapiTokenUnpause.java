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
import com.hederahashgraph.api.proto.java.TokenUnpauseTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenUnpause extends HapiTxnOp<HapiTokenUnpause> {
    static final Logger log = LogManager.getLogger(HapiTokenUnpause.class);

    private final String token;

    public HapiTokenUnpause(final String token) {
        this.token = token;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenPause;
    }

    @Override
    protected HapiTokenUnpause self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tId = TxnUtils.asTokenId(token, spec);
        final TokenUnpauseTransactionBody opBody = spec.txns()
                .<TokenUnpauseTransactionBody, TokenUnpauseTransactionBody.Builder>body(
                        TokenUnpauseTransactionBody.class, b -> b.setToken(tId));
        return b -> b.setTokenUnpause(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.PAUSE));
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.TokenUnpause, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        final var tokenUnpauseMeta = TOKEN_OPS_USAGE_UTILS.tokenUnpauseUsageFrom();
        final var baseTransactionMeta =
                new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();
        tokenOpsUsage.tokenUnpauseUsage(suFrom(svo), baseTransactionMeta, tokenUnpauseMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("token", token);
    }
}
