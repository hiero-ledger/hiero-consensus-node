// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenPauseTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenPause extends HapiTxnOp<HapiTokenPause> {
    static final Logger log = LogManager.getLogger(HapiTokenPause.class);

    private final String token;

    public HapiTokenPause(final String token) {
        this.token = token;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenPause;
    }

    @Override
    protected HapiTokenPause self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tId = TxnUtils.asTokenId(token, spec);
        final TokenPauseTransactionBody opBody = spec.txns()
                .<TokenPauseTransactionBody, TokenPauseTransactionBody.Builder>body(
                        TokenPauseTransactionBody.class, b -> b.setToken(tId));
        return b -> b.setTokenPause(opBody);
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
