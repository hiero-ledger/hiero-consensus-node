// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.token;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TokenGetNftInfoQuery;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetTokenNftInfo extends HapiQueryOp<HapiGetTokenNftInfo> {
    private static final Logger log = LogManager.getLogger(HapiGetTokenNftInfo.class);
    public static final String MISSING_SPENDER = "missing";

    String token;
    long serialNum;

    public HapiGetTokenNftInfo(String token, long serialNum) {
        this.token = token;
        this.serialNum = serialNum;
    }

    OptionalLong expectedSerialNum = OptionalLong.empty();
    Optional<ByteString> expectedMetadata = Optional.empty();
    Optional<String> expectedTokenID = Optional.empty();
    Optional<String> expectedAccountID = Optional.empty();
    Optional<Boolean> expectedCreationTime = Optional.empty();
    Optional<String> expectedSpenderID = Optional.empty();

    public HapiGetTokenNftInfo hasAccountID(String name) {
        expectedAccountID = Optional.of(name);
        return this;
    }

    public HapiGetTokenNftInfo hasSpenderID(String name) {
        expectedSpenderID = Optional.of(name);
        return this;
    }

    public HapiGetTokenNftInfo hasNoSpender() {
        expectedSpenderID = Optional.of(MISSING_SPENDER);
        return this;
    }

    public HapiGetTokenNftInfo hasTokenID(String token) {
        expectedTokenID = Optional.of(token);
        return this;
    }

    public HapiGetTokenNftInfo hasSerialNum(long serialNum) {
        expectedSerialNum = OptionalLong.of(serialNum);
        return this;
    }

    public HapiGetTokenNftInfo hasMetadata(ByteString metadata) {
        expectedMetadata = Optional.of(metadata);
        return this;
    }

    public HapiGetTokenNftInfo hasValidCreationTime() {
        expectedCreationTime = Optional.of(true);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenGetNftInfo;
    }

    @Override
    protected HapiGetTokenNftInfo self() {
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        var actualInfo = response.getTokenGetNftInfo().getNft();

        if (expectedSerialNum.isPresent()) {
            assertEquals(expectedSerialNum.getAsLong(), actualInfo.getNftID().getSerialNumber(), "Wrong serial num!");
        }

        if (expectedAccountID.isPresent()) {
            var id = TxnUtils.asId(expectedAccountID.get(), spec);
            assertEquals(id, actualInfo.getAccountID(), "Wrong account ID account!");
        }

        if (expectedSpenderID.isPresent()) {
            if (expectedSpenderID.get().equals(MISSING_SPENDER)) {
                Assertions.assertEquals(0, actualInfo.getSpenderId().getAccountNum(), "Wrong account ID account!");
            } else {
                var id = TxnUtils.asId(expectedSpenderID.get(), spec);
                Assertions.assertEquals(id, actualInfo.getSpenderId(), "Wrong spender ID account!");
            }
        }

        expectedMetadata.ifPresent(bytes -> assertEquals(bytes, actualInfo.getMetadata(), "Wrong metadata!"));

        assertFor(
                actualInfo.getCreationTime(),
                expectedCreationTime,
                (n, r) -> r.getCreationTime(token),
                "Wrong creation time (seconds)!",
                spec.registry());

        var registry = spec.registry();

        assertFor(
                actualInfo.getNftID().getTokenID(),
                expectedTokenID,
                (n, r) -> r.getTokenID(n),
                "Wrong token id!",
                registry);

        expectedLedgerId.ifPresent(id -> assertEquals(id, actualInfo.getLedgerId()));
    }

    private <T, R> void assertFor(
            R actual,
            Optional<T> possible,
            BiFunction<T, HapiSpecRegistry, R> expectedFn,
            String error,
            HapiSpecRegistry registry) {
        if (possible.isPresent()) {
            var expected = expectedFn.apply(possible.get(), registry);
            assertEquals(expected, actual, error);
        }
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            String message = String.format(
                    "Info for '%s': %s", token, response.getTokenGetNftInfo().getNft());
            log.info(message);
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
        return getTokenNftInfoQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getTokenNftInfoQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asTokenId(token, spec);
        TokenGetNftInfoQuery getTokenNftQuery = TokenGetNftInfoQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setNftID(NftID.newBuilder()
                        .setTokenID(id)
                        .setSerialNumber(serialNum)
                        .build())
                .build();
        return Query.newBuilder().setTokenGetNftInfo(getTokenNftQuery).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }
}
