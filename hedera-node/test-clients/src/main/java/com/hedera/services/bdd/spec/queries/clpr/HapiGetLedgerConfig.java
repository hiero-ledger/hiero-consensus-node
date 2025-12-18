// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.clpr;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
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
    private boolean assertEndpoints = false;

    public HapiGetLedgerConfig(String ledgerId) {
        this.ledgerId = ledgerId;
    }

    public HapiGetLedgerConfig hasTimestamp(final long expectedTs) {
        this.expectedTs = Optional.of(expectedTs);
        return this;
    }

    public HapiGetLedgerConfig hasEndpoints(@NonNull final List<ClprEndpoint> expectedEndpoints) {
        this.expectedEndpoints = expectedEndpoints;
        this.assertEndpoints = true;
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) {
        try {
            final var protocProof = response.getClprLedgerConfiguration().getLedgerConfigurationProof();
            // Convert protoc StateProof to PBJ StateProof via bytes
            final var pbjProof = com.hedera.hapi.block.stream.StateProof.PROTOBUF.parse(
                    com.hedera.pbj.runtime.io.buffer.Bytes.wrap(protocProof.toByteArray())
                            .toReadableSequentialData());
            final var pbjConfig = org.hiero.interledger.clpr.ClprStateProofUtils.extractConfiguration(pbjProof);

            // Convert PBJ config back to protoc config for comparison
            final var configBytes =
                    org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.toBytes(pbjConfig);
            final var info = org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerConfiguration.parseFrom(
                    configBytes.toByteArray());
            if (assertEndpoints) {
                assertEquals(expectedEndpoints.size(), info.getEndpointsCount(), "Wrong number of endpoints!");
                for (int i = 0; i < expectedEndpoints.size(); i++) {
                    final var expected = expectedEndpoints.get(i);
                    final var actual = info.getEndpoints(i);
                    assertEquals(expected, actual);
                }
            }
            expectedTs.ifPresent(exp -> assertEquals(exp, info.getTimestamp().getSeconds(), "Bad timestamp!"));
        } catch (com.hedera.pbj.runtime.ParseException | com.google.protobuf.InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse ledger configuration", e);
        }
    }

    @Override
    protected boolean needsPayment() {
        // TODO
        return true;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ClprGetLedgerConfig;
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
            try {
                final var protocProof = response.getClprLedgerConfiguration().getLedgerConfigurationProof();
                // Convert protoc StateProof to PBJ StateProof via bytes
                final var pbjProof = com.hedera.hapi.block.stream.StateProof.PROTOBUF.parse(
                        com.hedera.pbj.runtime.io.buffer.Bytes.wrap(protocProof.toByteArray())
                                .toReadableSequentialData());
                final var pbjConfig = org.hiero.interledger.clpr.ClprStateProofUtils.extractConfiguration(pbjProof);
                log.info("Info: {}", pbjConfig);
            } catch (com.hedera.pbj.runtime.ParseException e) {
                throw new RuntimeException("Failed to parse state proof", e);
            }
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
