// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.util;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoCreateMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.UtilPrngTransactionBody;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiUtilPrng extends HapiTxnOp<HapiUtilPrng> {
    static final Logger log = LogManager.getLogger(HapiUtilPrng.class);

    private Optional<Integer> range = Optional.empty();

    public HapiUtilPrng() {}

    public HapiUtilPrng(final int range) {
        this.range = Optional.of(range);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.UtilPrng;
    }

    public HapiUtilPrng range(final int range) {
        this.range = Optional.of(range);
        return this;
    }

    @Override
    protected HapiUtilPrng self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.UtilPrng, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final var baseMeta = new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
        final var accumulator = new UsageAccumulator();
        cryptoOpsUsage.cryptoCreateUsage(suFrom(svo), baseMeta, opMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final UtilPrngTransactionBody opBody = spec.txns()
                .<UtilPrngTransactionBody, UtilPrngTransactionBody.Builder>body(UtilPrngTransactionBody.class, b -> {
                    if (range.isPresent()) {
                        b.setRange(range.get());
                    }
                });
        return b -> b.setUtilPrng(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return Arrays.asList(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("range", range);
    }
}
