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
import com.hederahashgraph.api.proto.java.TokenGetAccountNftInfosQuery;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.junit.jupiter.api.Assertions;

public class HapiGetAccountNftInfos extends HapiQueryOp<HapiGetAccountNftInfos> {
    private static final Logger log = LogManager.getLogger(HapiGetAccountNftInfos.class);

    private String account;
    private long start;
    private long end;

    public HapiGetAccountNftInfos(String account, long start, long end) {
        this.account = account;
        this.start = start;
        this.end = end;
    }

    private Optional<List<HapiTokenNftInfo>> expectedNfts = Optional.empty();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenGetNftInfo;
    }

    @Override
    protected HapiGetAccountNftInfos self() {
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        var actualInfo = response.getTokenGetAccountNftInfos().getNftsList();
        var expectedInfo = new ArrayList<TokenNftInfo>();
        expectedNfts.ifPresent(nfts -> {
            for (HapiTokenNftInfo nftInfo : nfts) {
                var expectedNftElement = TokenNftInfo.newBuilder();
                var expectedNftId = NftID.newBuilder();

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
            StringBuilder information = new StringBuilder("Nft information for '" + account + "': \n");
            List<TokenNftInfo> nfts = response.getTokenGetAccountNftInfos().getNftsList();
            information.append(Strings.join(nfts, '\n'));
            log.info(information.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getAccountNftInfosQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getAccountNftInfosQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asId(account, spec);
        TokenGetAccountNftInfosQuery getAccountNftInfosQuery = TokenGetAccountNftInfosQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setAccountID(id)
                .setStart(start)
                .setEnd(end)
                .build();
        return Query.newBuilder()
                .setTokenGetAccountNftInfos(getAccountNftInfosQuery)
                .build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    public HapiGetAccountNftInfos hasNfts(HapiTokenNftInfo... nfts) {
        this.expectedNfts = Optional.of(Arrays.asList(nfts));
        return this;
    }
}
