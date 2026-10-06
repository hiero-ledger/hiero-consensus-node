// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenUnpauseTransactionBody;
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
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("token", token);
    }
}
