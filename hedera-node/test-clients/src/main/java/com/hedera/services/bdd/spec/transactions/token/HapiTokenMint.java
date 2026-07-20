// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenMint extends HapiTxnOp<HapiTokenMint> {
    static final Logger log = LogManager.getLogger(HapiTokenMint.class);

    private long amount;
    private final String token;
    private boolean rememberingNothing = false;
    private final List<ByteString> metadata;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenMint;
    }

    public HapiTokenMint(final String token, final long amount) {
        this.token = token;
        this.amount = amount;
        this.metadata = Collections.emptyList();
    }

    public HapiTokenMint(final String token, final List<ByteString> metadata) {
        this.token = token;
        this.metadata = metadata;
    }

    public HapiTokenMint(final String token, final List<ByteString> metadata, final String txNamePrefix) {
        this.token = token;
        this.metadata = metadata;
        this.amount = 0;
    }

    public HapiTokenMint(final String token, final List<ByteString> metadata, final long amount) {
        this.token = token;
        this.metadata = metadata;
        this.amount = amount;
    }

    public HapiTokenMint rememberingNothing() {
        rememberingNothing = true;
        return this;
    }

    @Override
    protected HapiTokenMint self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tId = TxnUtils.asTokenId(token, spec);
        final TokenMintTransactionBody opBody = spec.txns()
                .<TokenMintTransactionBody, TokenMintTransactionBody.Builder>body(TokenMintTransactionBody.class, b -> {
                    b.setToken(tId);
                    b.setAmount(amount);
                    b.addAllMetadata(metadata);
                });
        return b -> b.setTokenMint(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getRoleKey(token, KeyRole.SUPPLY));
    }

    @Override
    public void updateStateOf(final HapiSpec spec) throws Throwable {
        if (rememberingNothing || actualStatus != SUCCESS) {
            return;
        }
        lookupSubmissionRecord(spec);
        // For child/inner transactions, use parent consensus timestamp
        // since this is the timestamp saved in the state.
        final var creationTime = recordOfSubmission.hasParentConsensusTimestamp()
                ? recordOfSubmission.getParentConsensusTimestamp()
                : recordOfSubmission.getConsensusTimestamp();
        spec.registry().saveCreationTime(token, creationTime);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper =
                super.toStringHelper().add("token", token).add("amount", amount).add("metadata", metadata);
        return helper;
    }
}
