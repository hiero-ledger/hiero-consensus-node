// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprSetRemoteLedgerConfig;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.CommonUtils;
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
import org.hiero.hapi.interledger.clpr.protoc.ClprSetRemoteLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerId;

public class HapiSetRemoteLedgerConfig extends HapiTxnOp<HapiSetRemoteLedgerConfig> {
    static final Logger log = LogManager.getLogger(HapiSetRemoteLedgerConfig.class);

    private final String ledgerId;
    private Timestamp timestamp; // active timestamp
    private List<ClprEndpoint> endpoints = new ArrayList<>();

    private boolean advertiseCreation = false;

    public HapiSetRemoteLedgerConfig(final String ledgerId) {
        this.ledgerId = ledgerId;
    }

    public HapiSetRemoteLedgerConfig timestamp(final Timestamp timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public HapiSetRemoteLedgerConfig endpoints(final List<ClprEndpoint> endpoints) {
        this.endpoints = new ArrayList<>(endpoints);
        return this;
    }

    public HapiSetRemoteLedgerConfig advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprSetRemoteLedgerConfig;
    }

    @Override
    protected HapiSetRemoteLedgerConfig self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprSetRemoteLedgerConfigurationTransactionBody opBody = spec.txns()
                .<ClprSetRemoteLedgerConfigurationTransactionBody,
                        ClprSetRemoteLedgerConfigurationTransactionBody.Builder>
                        body(ClprSetRemoteLedgerConfigurationTransactionBody.class, b -> {
                    b.setLedgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .setLedgerId(ClprLedgerId.newBuilder()
                                    .setLedgerId(ByteString.copyFromUtf8(ledgerId))
                                    .build())
                            .setTimestamp(timestamp)
                            .addAllEndpoints(endpoints)
                            .build());
                });
        return b -> b.setClprSetRemoteConfiguration(opBody);
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }

        try {
            final TransactionBody txn = CommonUtils.extractTransactionBody(txnSubmitted);
            spec.registry().saveRemoteLedgerConfig(txn.getClprSetRemoteConfiguration());
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
}
