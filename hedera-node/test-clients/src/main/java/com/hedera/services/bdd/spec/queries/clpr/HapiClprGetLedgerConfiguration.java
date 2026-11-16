// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.clpr;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hederahashgraph.api.proto.java.ClprGetLedgerConfigurationQuery;
import com.hederahashgraph.api.proto.java.ClprGetLedgerConfigurationResponse;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HAPI spec operation for {@code ClprGetLedgerConfiguration} free queries.
 *
 * <p>Usage:
 * <pre>
 *   clprGetLedgerConfiguration()
 *       .chainId("hiero:testnet")
 *       .protocolVersion(1)
 *       .maxMessagesPerBundle(100)
 * </pre>
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class HapiClprGetLedgerConfiguration extends HapiQueryOp<HapiClprGetLedgerConfiguration> {
    private static final Logger log = LogManager.getLogger(HapiClprGetLedgerConfiguration.class);

    private static final Logger LOG = LogManager.getLogger(HapiClprGetLedgerConfiguration.class);
    private Consumer<ClprLedgerConfiguration> observer = config -> {};
    private Consumer<ByteString> proofObserver = proof -> {};

    private Optional<String> expectedChainId = Optional.empty();
    private Optional<Integer> expectedProtocolVersion = Optional.empty();
    private Optional<Integer> expectedMaxMessagesPerBundle = Optional.empty();
    private Optional<Integer> expectedMaxQueueDepth = Optional.empty();
    private Optional<Long> expectedMaxGasPerMessage = Optional.empty();
    private Optional<Consumer<ClprLedgerConfiguration>> configObserver = Optional.empty();

    public HapiClprGetLedgerConfiguration() {}

    public HapiClprGetLedgerConfiguration chainId(final String chainId) {
        expectedChainId = Optional.of(chainId);
        return this;
    }

    public HapiClprGetLedgerConfiguration protocolVersion(final int version) {
        expectedProtocolVersion = Optional.of(version);
        return this;
    }

    public HapiClprGetLedgerConfiguration exposingTo(final Consumer<ClprLedgerConfiguration> observer) {
        this.observer = observer;
        return this;
    }
    /**
     * Registers an observer for the serialized {@code configuration_state_proof} bytes
     * returned by the peer ledger. The observer is invoked with the raw bytes when the
     * answer-only response arrives. Empty bytes are passed if the peer hasn't yet
     * produced a signed block snapshot.
     */
    public HapiClprGetLedgerConfiguration exposingProofTo(final Consumer<ByteString> proofObserver) {
        this.proofObserver = proofObserver;
        return this;
    }

    public HapiClprGetLedgerConfiguration maxMessagesPerBundle(final int max) {
        expectedMaxMessagesPerBundle = Optional.of(max);
        return this;
    }

    public HapiClprGetLedgerConfiguration maxQueueDepth(final int depth) {
        expectedMaxQueueDepth = Optional.of(depth);
        return this;
    }

    public HapiClprGetLedgerConfiguration maxGasPerMessage(final long gas) {
        expectedMaxGasPerMessage = Optional.of(gas);
        return this;
    }

    /** Expose the full configuration to a consumer for ad-hoc assertions. */
    public HapiClprGetLedgerConfiguration exposingConfigTo(final Consumer<ClprLedgerConfiguration> observer) {
        configObserver = Optional.of(observer);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ClprGetLedgerConfiguration;
    }

    @Override
    protected HapiClprGetLedgerConfiguration self() {
        return this;
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        final var inner = ClprGetLedgerConfigurationQuery.newBuilder()
                .setHeader(responseType == ResponseType.COST_ANSWER ? answerCostHeader(payment) : answerHeader(payment))
                .build();
        return Query.newBuilder().setClprGetLedgerConfiguration(inner).build();
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final var inner = response.getClprGetLedgerConfiguration();
        if (inner.hasConfiguration()) {
            observer.accept(inner.getConfiguration());
        }
        proofObserver.accept(inner.getConfigurationStateProof());
        configObserver.ifPresent(
                obs -> obs.accept(clprGetLedgerConfigurationResponse().getConfiguration()));
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(final HapiSpec spec) {
        final var config = clprGetLedgerConfigurationResponse().getConfiguration();
        assertNotNull(config, "Configuration must not be null");

        expectedChainId.ifPresent(expected -> assertEquals(expected, config.getChainId(), "Wrong chain_id"));
        expectedProtocolVersion.ifPresent(
                expected -> assertEquals(expected, config.getProtocolVersion(), "Wrong protocol_version"));

        if (config.hasThrottles()) {
            final var throttles = config.getThrottles();
            expectedMaxMessagesPerBundle.ifPresent(expected ->
                    assertEquals(expected, throttles.getMaxMessagesPerBundle(), "Wrong max_messages_per_bundle"));
            expectedMaxQueueDepth.ifPresent(
                    expected -> assertEquals(expected, throttles.getMaxQueueDepth(), "Wrong max_queue_depth"));
            expectedMaxGasPerMessage.ifPresent(
                    expected -> assertEquals(expected, throttles.getMaxGasPerMessage(), "Wrong max_gas_per_message"));
        } else {
            assertTrue(
                    expectedMaxMessagesPerBundle.isEmpty()
                            && expectedMaxQueueDepth.isEmpty()
                            && expectedMaxGasPerMessage.isEmpty(),
                    "Response has no throttles but throttle assertions were specified");
        }
    }

    private ClprGetLedgerConfigurationResponse clprGetLedgerConfigurationResponse() {
        return response.getClprGetLedgerConfiguration();
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("query", "ClprGetLedgerConfiguration");
    }
}
