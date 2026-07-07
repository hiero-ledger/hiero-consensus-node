// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenFeeScheduleUpdate extends HapiTxnOp<HapiTokenFeeScheduleUpdate> {
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
}
