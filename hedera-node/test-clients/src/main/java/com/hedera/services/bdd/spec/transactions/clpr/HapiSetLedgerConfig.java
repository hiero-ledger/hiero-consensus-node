// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprSetLedgerConfig;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.protoc.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ClprStateProofUtils;

public class HapiSetLedgerConfig extends HapiTxnOp<HapiSetLedgerConfig> {
    static final Logger log = LogManager.getLogger(HapiSetLedgerConfig.class);

    private final String ledgerId;
    private Timestamp timestamp; // active timestamp (protoc)
    private List<ClprEndpoint> endpoints = new ArrayList<>();

    private boolean advertiseCreation = false;

    public HapiSetLedgerConfig(final String ledgerId) {
        this.ledgerId = ledgerId;
    }

    public HapiSetLedgerConfig timestamp(final Timestamp timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public HapiSetLedgerConfig endpoints(final List<ClprEndpoint> endpoints) {
        this.endpoints = new ArrayList<>(endpoints);
        return this;
    }

    public HapiSetLedgerConfig advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprSetLedgerConfig;
    }

    @Override
    protected HapiSetLedgerConfig self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var endpointsToUse = endpoints.isEmpty() ? List.of(defaultEndpoint(spec)) : endpoints;

        // Build PBJ configuration
        final var pbjTimestamp = com.hedera.hapi.node.base.Timestamp.newBuilder()
                .seconds(timestamp.getSeconds())
                .nanos(timestamp.getNanos())
                .build();
        final var pbjConfig = ClprLedgerConfiguration.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap(ledgerId.getBytes()))
                        .build())
                .timestamp(pbjTimestamp)
                .endpoints(endpointsToUse)
                .build();

        // Wrap it in a dev-mode state proof using shared helper, then convert to protoc for the txn
        final var pbjProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(pbjConfig);
        final var stateProof = com.hedera.hapi.block.stream.protoc.StateProof.parseFrom(
                com.hedera.hapi.block.stream.StateProof.PROTOBUF
                        .toBytes(pbjProof)
                        .toByteArray());

        final ClprSetLedgerConfigurationTransactionBody opBody = spec.txns()
                .<ClprSetLedgerConfigurationTransactionBody, ClprSetLedgerConfigurationTransactionBody.Builder>body(
                        ClprSetLedgerConfigurationTransactionBody.class, b -> {
                            b.setLedgerConfigurationProof(stateProof);
                        });
        return b -> b.setClprSetLedgerConfiguration(opBody);
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }

        try {
            final TransactionBody txn = CommonUtils.extractTransactionBody(txnSubmitted);
            spec.registry().saveRemoteLedgerConfig(txn.getClprSetLedgerConfiguration());
        } catch (final Exception impossible) {
            throw new IllegalStateException(impossible);
        }

        if (advertiseCreation) {
            final String banner =
                    "\n\n" + bannerWith(String.format("Created CLPR ledger config with id '%s'.", ledgerId));
            log.info(banner);
        }
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        // TODO
        return 0L;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper().add("ledgerId", ledgerId);
        helper.add("timestamp", timestamp);
        helper.add("endpoints", endpoints.stream().map(ClprEndpoint::toString).collect(toList()));
        return helper;
    }

    private ClprEndpoint defaultEndpoint(final HapiSpec spec) {
        final var node = spec.targetNetworkOrThrow().nodes().getFirst();
        try {
            final var addressBytes =
                    java.net.InetAddress.getByName(node.getHost()).getAddress();
            return ClprEndpoint.newBuilder()
                    .endpoint(com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                            .ipAddressV4(Bytes.wrap(addressBytes))
                            .port(node.getGrpcPort())
                            .build())
                    .nodeAccountId(node.getAccountId())
                    .signingCertificate(Bytes.wrap("test-cert".getBytes()))
                    .build();
        } catch (final java.net.UnknownHostException e) {
            throw new IllegalStateException("Unable to resolve node host for CLPR endpoint", e);
        }
    }
}
