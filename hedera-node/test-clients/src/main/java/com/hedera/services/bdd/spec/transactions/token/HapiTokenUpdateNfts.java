// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
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
import com.hederahashgraph.api.proto.java.TokenUpdateNftsTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenUpdateNfts extends HapiTxnOp<HapiTokenUpdateNfts> {
    static final Logger log = LogManager.getLogger(HapiTokenUpdateNfts.class);
    private Optional<List<Long>> serialNumbers;
    private Optional<String> metadataKey = Optional.empty();
    private String token;
    private final SubType subType;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenUpdateNfts;
    }

    public HapiTokenUpdateNfts(final String token, final String metadata, final List<Long> serialNumbers) {
        this.token = token;
        this.metadata = Optional.of(metadata);
        this.serialNumbers = Optional.of(serialNumbers);
        this.subType = SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
    }

    public HapiTokenUpdateNfts metadata(String metadata) {
        this.metadata = Optional.of(metadata);
        return this;
    }

    public HapiTokenUpdateNfts serialNumbers(List<Long> serialNumbers) {
        this.serialNumbers = Optional.of(serialNumbers);
        return this;
    }

    @Override
    protected HapiTokenUpdateNfts self() {
        return this;
    }

    public HapiTokenUpdateNfts metadataKey(String name) {
        metadataKey = Optional.of(name);
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        var txnId = TxnUtils.asTokenId(token, spec);
        final TokenUpdateNftsTransactionBody opBody = spec.txns()
                .<TokenUpdateNftsTransactionBody, TokenUpdateNftsTransactionBody.Builder>body(
                        TokenUpdateNftsTransactionBody.class, b -> {
                            b.setToken(txnId);
                            var metadataValue = BytesValue.newBuilder()
                                    .setValue(ByteString.copyFrom(
                                            metadata.orElseThrow().getBytes()))
                                    .build();
                            metadata.ifPresent(s -> b.setMetadata(metadataValue));
                            b.addAllSerialNumbers(serialNumbers.orElse(Collections.emptyList()));
                        });
        return b -> b.setTokenUpdateNfts(opBody);
    }

    @Override
    protected void updateStateOf(HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
        var registry = spec.registry();
        registry.saveMetadata(token, metadata.orElse(""));
        metadata.ifPresent(m -> registry.saveMetadata(token, m));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("token", token);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        final var serials = txn.getTokenUpdateNfts().getSerialNumbersList().size();
        accumulator.addBpt(serials);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.TokenUpdateNfts, subType, this::usageEstimate, txn, numPayerKeys);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final List<Function<HapiSpec, Key>> signers = new ArrayList<>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        if (metadataKey.isPresent()) {
            signers.add(spec -> spec.registry().getRoleKey(token, KeyRole.METADATA));
        }
        return signers;
    }
}
