// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.clpr;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.protoc.ClprGetLedgerConfigurationQuery;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprEndpoint;

public class HapiGetLedgerConfig extends HapiQueryOp<HapiGetLedgerConfig> {
    private static final Logger log = LogManager.getLogger(HapiGetLedgerConfig.class);

    private final String ledgerId;
    private Optional<Long> expectedTs = Optional.empty();
    private List<ClprEndpoint> expectedEndpoints = new ArrayList<>();

    public HapiGetLedgerConfig(String ledgerId) {
        this.ledgerId = ledgerId;
    }

    public HapiGetLedgerConfig hasTimestamp(final long expectedTs) {
        this.expectedTs = Optional.of(expectedTs);
        return this;
    }

    public HapiGetLedgerConfig hasEndpoints(@NonNull final List<ClprEndpoint> expectedEndpoints) {
        this.expectedEndpoints = expectedEndpoints;
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) {
        final var info = response.getClprLedgerConfiguration().getLedgerConfiguration();
        assertEquals(expectedEndpoints.size(), info.getEndpointsCount(), "Wrong number of endpoints!");
        for (int i = 0; i < expectedEndpoints.size(); i++) {
            final var expected = expectedEndpoints.get(i);
            final var actual = info.getEndpoints(i);
            assertEquals(expected, actual);
        }
        expectedTs.ifPresent(exp -> assertEquals(exp, info.getTimestamp().getSeconds(), "Bad timestamp!"));
    }

    @Override
    protected boolean needsPayment() {
        // TODO
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getClprGetLedgerConfigQuery(payment, responseType == ResponseType.COST_ANSWER);
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            String message = String.format(
                    "Info: %s", response.getClprLedgerConfiguration().getLedgerConfiguration());
            log.info(message);
        }
    }

    private Query getClprGetLedgerConfigQuery(@NonNull final Transaction payment, final boolean costOnly) {
        final ClprGetLedgerConfigurationQuery clprLedgerConfig =
                org.hiero.hapi.interledger.clpr.protoc.ClprGetLedgerConfigurationQuery.newBuilder()
                        .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                        .setLedgerId(org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerId.newBuilder()
                                .setLedgerId(ByteString.copyFromUtf8(ledgerId))
                                .build())
                        .build();
        return Query.newBuilder()
                .setGetClprLedgerConfiguration(clprLedgerConfig)
                .build();
    }

    @Override
    protected HapiGetLedgerConfig self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("ledgerId", ledgerId);
    }
}
