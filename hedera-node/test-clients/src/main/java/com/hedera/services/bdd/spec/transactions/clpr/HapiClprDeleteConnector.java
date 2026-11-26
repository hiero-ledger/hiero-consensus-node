// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprDeleteConnector;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ClprDeleteConnectorTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprDeleteConnector} transactions.
 */
public class HapiClprDeleteConnector extends HapiTxnOp<HapiClprDeleteConnector> {

    private byte[] channelId;
    private byte[] connectorId;
    private Optional<String> adminKeyName = Optional.empty();
    private Optional<String> stakeRecipientName = Optional.empty();
    private Optional<AccountID> stakeRecipientId = Optional.empty();

    public HapiClprDeleteConnector() {}

    public HapiClprDeleteConnector channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprDeleteConnector connectorId(final byte[] connectorId) {
        this.connectorId = connectorId;
        return this;
    }

    // Keep for test-suite backward compatibility — maps old call to no-op
    public HapiClprDeleteConnector sourceConnectorAddress(final byte[] ignored) {
        return this;
    }

    public HapiClprDeleteConnector adminKey(final String keyName) {
        this.adminKeyName = Optional.of(keyName);
        return this;
    }

    public HapiClprDeleteConnector stakeRecipient(final String accountName) {
        this.stakeRecipientName = Optional.of(accountName);
        return this;
    }

    public HapiClprDeleteConnector stakeRecipient(final AccountID accountId) {
        this.stakeRecipientId = Optional.of(accountId);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprDeleteConnector;
    }

    @Override
    protected HapiClprDeleteConnector self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprDeleteConnectorTransactionBody opBody = spec.txns()
                .<ClprDeleteConnectorTransactionBody, ClprDeleteConnectorTransactionBody.Builder>body(
                        ClprDeleteConnectorTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (connectorId != null) {
                                b.setConnectorId(ByteString.copyFrom(connectorId));
                            }
                            stakeRecipientName.ifPresent(
                                    name -> b.setStakeRecipient(spec.registry().getAccountID(name)));
                            stakeRecipientId.ifPresent(b::setStakeRecipient);
                        });
        return b -> b.setClprDeleteConnector(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final var signers = new ArrayList<Function<HapiSpec, Key>>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        adminKeyName.ifPresent(name -> signers.add(spec -> spec.registry().getKey(name)));
        stakeRecipientName.ifPresent(name -> signers.add(spec -> spec.registry().getKey(name)));
        return signers;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
