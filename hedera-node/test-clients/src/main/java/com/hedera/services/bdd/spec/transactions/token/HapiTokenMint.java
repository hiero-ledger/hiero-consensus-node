// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenFeeScheduleUpdate.lookupInfo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
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
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
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
    private SubType subType;

    private TokenInfo info;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenMint;
    }

    public HapiTokenMint(final String token, final long amount) {
        this.token = token;
        this.amount = amount;
        this.metadata = Collections.emptyList();
        this.subType = figureSubType();
    }

    public HapiTokenMint(final String token, final List<ByteString> metadata) {
        this.token = token;
        this.metadata = metadata;
        this.subType = figureSubType();
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
        this.subType = figureSubType();
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
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        try {
            info = lookupInfo(spec, token, log, loggingOff);
        } catch (final Throwable ignore) {

        }
        return spec.fees()
                .forActivityBasedOp(HederaFunctionality.TokenMint, subType, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();

        long lifetime = 0L;
        if (subType == SubType.TOKEN_NON_FUNGIBLE_UNIQUE) {
            lifetime = info.getExpiry().getSeconds()
                    - txn.getTransactionID().getTransactionValidStart().getSeconds();
        }
        final var tokenMintMeta = TOKEN_OPS_USAGE_UTILS.tokenMintUsageFrom(txn, subType, lifetime);
        final var baseTransactionMeta =
                new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();
        tokenOpsUsage.tokenMintUsage(suFrom(svo), baseTransactionMeta, tokenMintMeta, accumulator, subType);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    private SubType figureSubType() {
        if (metadata.isEmpty()) {
            return SubType.TOKEN_FUNGIBLE_COMMON;
        } else {
            return SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
        }
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
        spec.registry().saveCreationTime(token, recordOfSubmission.getConsensusTimestamp());
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper =
                super.toStringHelper().add("token", token).add("amount", amount).add("metadata", metadata);
        return helper;
    }
}
