// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.util;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
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
