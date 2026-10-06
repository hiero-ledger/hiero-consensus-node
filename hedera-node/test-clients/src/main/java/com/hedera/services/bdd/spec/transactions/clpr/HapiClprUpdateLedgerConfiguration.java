// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprUpdateLedgerConfiguration;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ClprUpdateLedgerConfigurationTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprUpdateLedgerConfiguration} transactions.
 */
public class HapiClprUpdateLedgerConfiguration extends HapiTxnOp<HapiClprUpdateLedgerConfiguration> {

    private Optional<byte[]> serviceAddress = Optional.empty();
    private Optional<ClprThrottles> throttles = Optional.empty();
    private final List<ClprEndpoint> endpoints = new ArrayList<>();
    private Optional<ClprLedgerConfiguration> rawConfiguration = Optional.empty();

    public HapiClprUpdateLedgerConfiguration() {}

    public HapiClprUpdateLedgerConfiguration serviceAddress(final byte[] address) {
        this.serviceAddress = Optional.of(address);
        return this;
    }

    public HapiClprUpdateLedgerConfiguration throttles(final ClprThrottles throttles) {
        this.throttles = Optional.of(throttles);
        return this;
    }

    public HapiClprUpdateLedgerConfiguration seedEndpoint(final ClprEndpoint endpoint) {
        this.endpoints.add(endpoint);
        return this;
    }

    public HapiClprUpdateLedgerConfiguration configuration(final ClprLedgerConfiguration config) {
        this.rawConfiguration = Optional.of(config);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprUpdateLedgerConfiguration;
    }

    @Override
    protected HapiClprUpdateLedgerConfiguration self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprUpdateLedgerConfigurationTransactionBody opBody = spec.txns()
                .<ClprUpdateLedgerConfigurationTransactionBody, ClprUpdateLedgerConfigurationTransactionBody.Builder>
                        body(ClprUpdateLedgerConfigurationTransactionBody.class, b -> {
                    if (rawConfiguration.isPresent()) {
                        b.setConfiguration(rawConfiguration.get());
                    } else {
                        final var configBuilder = ClprLedgerConfiguration.newBuilder();
                        serviceAddress.ifPresent(addr -> configBuilder.setServiceAddress(ByteString.copyFrom(addr)));
                        throttles.ifPresent(configBuilder::setThrottles);
                        endpoints.forEach(configBuilder::addEndpoints);
                        b.setConfiguration(configBuilder.build());
                    }
                });
        return b -> b.setClprUpdateLedgerConfiguration(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }

    /**
     * Creates a default valid CLPR configuration for testing.
     *
     * @return a pre-populated configuration builder
     */
    public static ClprThrottles defaultThrottles() {
        return ClprThrottles.newBuilder()
                .setMaxMessagesPerBundle(100)
                .setMaxMessagePayloadBytes(65536)
                .setMaxGasPerMessage(1_000_000L)
                .setMaxQueueDepth(1000)
                .setMaxSyncBytes(1_048_576L)
                .build();
    }

    /**
     * Creates a valid seed endpoint for testing.
     *
     * @param ip the IP address
     * @param port the port
     * @param tlsCert the TLS certificate bytes
     * @param ecdsaKey the ECDSA signing key bytes
     * @return a populated ClprEndpoint
     */
    public static ClprEndpoint seedEndpoint(
            final String ip, final int port, final byte[] tlsCert, final byte[] ecdsaKey) {
        return ClprEndpoint.newBuilder()
                .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                        .setIpAddress(ip)
                        .setPort(port)
                        .build())
                .setTlsCertificate(ByteString.copyFrom(tlsCert))
                .build();
    }
}
