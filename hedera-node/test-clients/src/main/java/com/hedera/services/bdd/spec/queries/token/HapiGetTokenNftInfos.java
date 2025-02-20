// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.token;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TokenGetNftInfosQuery;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetTokenNftInfos extends HapiQueryOp<HapiGetTokenNftInfos> {

    private static final Logger log = LogManager.getLogger(HapiGetTokenNftInfos.class);

    private String token;

    private long start;

    private long end;

    private Optional<List<HapiTokenNftInfo>> expectedNfts = Optional.empty();

    public HapiGetTokenNftInfos(String token, long start, long end) {
        this.token = token;
        this.start = start;
        this.end = end;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenGetNftInfos;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        var actualInfo = response.getTokenGetNftInfos().getNftsList();
        var expectedInfo = new ArrayList<TokenNftInfo>();
        expectedNfts.ifPresent(nfts -> {
            for (HapiTokenNftInfo nftInfo : nfts) {
                var expectedNftId = NftID.newBuilder();
                var expectedNftElement = TokenNftInfo.newBuilder();

                nftInfo.getExpectedTokenID().ifPresent(e -> {
                    expectedNftId.setTokenID(TxnUtils.asTokenId(e, spec));
                    expectedNftElement.setCreationTime(spec.registry().getCreationTime(e));
                });
                nftInfo.getExpectedSerialNum().ifPresent(expectedNftId::setSerialNumber);

                expectedNftElement.setNftID(expectedNftId.build());
                nftInfo.getExpectedAccountID().ifPresent(e -> expectedNftElement.setAccountID(TxnUtils.asId(e, spec)));
                nftInfo.getExpectedMetadata().ifPresent(expectedNftElement::setMetadata);

                nftInfo.getExpectedLedgerID().ifPresent(id -> expectedNftElement.setLedgerId(rationalize(id)));

                var completedNft = expectedNftElement.build();
                expectedInfo.add(completedNft);
            }
            Assertions.assertEquals(actualInfo, expectedInfo);
        });
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            String message = String.format("NftInfo for '%s': ", token);
            log.info(message);
            response.getTokenGetNftInfos().getNftsList().forEach(nft -> log.info(nft.toString()));
        }
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected HapiGetTokenNftInfos self() {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getTokenNftInfosQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getTokenNftInfosQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asTokenId(token, spec);
        TokenGetNftInfosQuery getTokenNftInfosQuery = TokenGetNftInfosQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setTokenID(id)
                .setStart(start)
                .setEnd(end)
                .build();
        return Query.newBuilder().setTokenGetNftInfos(getTokenNftInfosQuery).build();
    }
}
