// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage;
import com.hedera.node.app.hapi.fees.usage.token.meta.ExtantFeeScheduleContext;
import com.hedera.node.app.hapi.fees.usage.token.meta.FeeScheduleUpdateMeta;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenFeeScheduleUpdate extends HapiTxnOp<HapiTokenFeeScheduleUpdate> {
    private static final TokenOpsUsage TOKEN_OPS_USAGE = new TokenOpsUsage();

    static final Logger log = LogManager.getLogger(HapiTokenFeeScheduleUpdate.class);

    private final String token;

    private final List<Function<HapiSpec, CustomFee>> feeScheduleSuppliers = new ArrayList<>();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenUpdate;
    }

    public HapiTokenFeeScheduleUpdate(final String token) {
        this.token = token;
    }

    public HapiTokenFeeScheduleUpdate withCustom(final Function<HapiSpec, CustomFee> supplier) {
        feeScheduleSuppliers.add(supplier);
        return this;
    }

    @Override
    protected HapiTokenFeeScheduleUpdate self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        try {
            final TokenInfo info = lookupInfo(spec, token, log, loggingOff);
            final FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> usageEstimate(_txn, svo, info);
            return spec.fees().forActivityBasedOp(TokenFeeScheduleUpdate, metricsCalc, txn, numPayerKeys);
        } catch (final Throwable ignore) {
            return ONE_HBAR;
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var id = TxnUtils.asTokenId(token, spec);
        final var opBody = spec.txns()
                .<TokenFeeScheduleUpdateTransactionBody, TokenFeeScheduleUpdateTransactionBody.Builder>body(
                        TokenFeeScheduleUpdateTransactionBody.class, b -> {
                            b.setTokenId(id);
                            if (!feeScheduleSuppliers.isEmpty()) {
                                for (final var supplier : feeScheduleSuppliers) {
                                    b.addCustomFees(supplier.apply(spec));
                                }
                            }
                        });
        return b -> b.setTokenFeeScheduleUpdate(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final List<Function<HapiSpec, Key>> signers = new ArrayList<>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        signers.add(spec -> {
            final var registry = spec.registry();
            return registry.hasRoleKey(token, KeyRole.FEE_SCHEDULE)
                    ? registry.getRoleKey(token, KeyRole.FEE_SCHEDULE)
                    : Key.getDefaultInstance();
        });
        return signers;
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        /* No-op */
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("token", token);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo, final TokenInfo info) {
        final var op = txn.getTokenFeeScheduleUpdate();
        final var baseMeta = new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final var effectiveNow =
                txn.getTransactionID().getTransactionValidStart().getSeconds();
        final var bytesToReprNew = TOKEN_OPS_USAGE.bytesNeededToRepr(op.getCustomFeesList());
        final var bytesToReprOld = TOKEN_OPS_USAGE.bytesNeededToRepr(info.getCustomFeesList());

        final var ctx = new ExtantFeeScheduleContext(info.getExpiry().getSeconds(), bytesToReprOld);
        final var accumulator = new UsageAccumulator();
        final var opMeta = new FeeScheduleUpdateMeta(effectiveNow, bytesToReprNew);
        TOKEN_OPS_USAGE.feeScheduleUpdateUsage(suFrom(svo), baseMeta, opMeta, ctx, accumulator);

        return AdapterUtils.feeDataFrom(accumulator);
    }

    static TokenInfo lookupInfo(
            final HapiSpec spec, final String token, final Logger scopedLog, final boolean loggingOff)
            throws Throwable {
        final HapiGetTokenInfo subOp = getTokenInfo(token).nodePayment(ONE_HBAR).noLogging();
        final Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            if (!loggingOff) {
                String message = String.format(
                        "Unable to look up current info for %s",
                        HapiPropertySource.asTokenString(spec.registry().getTokenID(token)));
                scopedLog.warn(message, error.get());
            }
            throw error.get();
        }
        return subOp.getResponse().getTokenGetInfo().getTokenInfo();
    }
}
