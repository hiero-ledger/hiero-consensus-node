// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.crypto;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance.MISSING_OWNER;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoDeleteAllowanceMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.CryptoDeleteAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftRemoveAllowance;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiCryptoDeleteAllowance extends HapiTxnOp<HapiCryptoDeleteAllowance> {
    static final Logger log = LogManager.getLogger(HapiCryptoDeleteAllowance.class);

    private List<NftAllowances> nftAllowances = new ArrayList<>();

    public HapiCryptoDeleteAllowance() {}

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.CryptoDeleteAllowance;
    }

    @Override
    protected HapiCryptoDeleteAllowance self() {
        return this;
    }

    public HapiCryptoDeleteAllowance addNftDeleteAllowance(String owner, String token, List<Long> serials) {
        nftAllowances.add(NftAllowances.from(owner, token, serials));
        return this;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        try {
            FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
                var baseMeta = new BaseTransactionMeta(_txn.getMemoBytes().size(), 0);
                var opMeta = new CryptoDeleteAllowanceMeta(
                        _txn.getCryptoDeleteAllowance(),
                        _txn.getTransactionID().getTransactionValidStart().getSeconds());
                var accumulator = new UsageAccumulator();
                cryptoOpsUsage.cryptoDeleteAllowanceUsage(suFrom(svo), baseMeta, opMeta, accumulator);
                return AdapterUtils.feeDataFrom(accumulator);
            };
            return spec.fees()
                    .forActivityBasedOp(HederaFunctionality.CryptoDeleteAllowance, metricsCalc, txn, numPayerKeys);
        } catch (Throwable ignore) {
            return HapiSuite.ONE_HBAR;
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        List<NftRemoveAllowance> nftallowances = new ArrayList<>();
        calculateAllowances(spec, nftallowances);
        CryptoDeleteAllowanceTransactionBody opBody = spec.txns()
                .<CryptoDeleteAllowanceTransactionBody, CryptoDeleteAllowanceTransactionBody.Builder>body(
                        CryptoDeleteAllowanceTransactionBody.class, b -> b.addAllNftAllowances(nftallowances));
        return b -> b.setCryptoDeleteAllowance(opBody);
    }

    private void calculateAllowances(final HapiSpec spec, final List<NftRemoveAllowance> nftallowances) {
        for (var entry : nftAllowances) {
            final var builder = NftRemoveAllowance.newBuilder()
                    .setTokenId(spec.registry().getTokenID(entry.token()))
                    .addAllSerialNumbers(entry.serials());
            if (entry.owner() != MISSING_OWNER) {
                builder.setOwner(spec.registry().getAccountID(entry.owner()));
            }
            nftallowances.add(builder.build());
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return Arrays.asList(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper().add("nftDeleteAllowances", nftAllowances);
        return helper;
    }

    private record NftAllowances(String owner, String token, List<Long> serials) {
        static NftAllowances from(String owner, String token, List<Long> serials) {
            return new NftAllowances(owner, token, serials);
        }
    }
}
