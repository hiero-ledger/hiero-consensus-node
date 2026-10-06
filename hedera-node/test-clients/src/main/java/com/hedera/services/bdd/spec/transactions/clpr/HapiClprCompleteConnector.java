// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprCompleteConnector;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprCompleteConnectorTransactionBody;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprCompleteConnector} (Phase 2: Reveal) transactions.
 */
public class HapiClprCompleteConnector extends HapiTxnOp<HapiClprCompleteConnector> {

    private byte[] connectorId;
    private byte[] publicKey;
    private byte[] signature;
    private ClprSignatureScheme signatureScheme = ClprSignatureScheme.ED25519;
    private byte[] salt;
    private byte[] channelId;
    private Optional<String> connectorContractName = Optional.empty();
    private Optional<ContractID> connectorContractId = Optional.empty();
    private Optional<Key> adminKeyValue = Optional.empty();
    private Optional<String> adminKeyName = Optional.empty();
    private long lockedStake;

    public HapiClprCompleteConnector() {}

    public HapiClprCompleteConnector connectorId(final byte[] connectorId) {
        this.connectorId = connectorId;
        return this;
    }

    public HapiClprCompleteConnector publicKey(final byte[] publicKey) {
        this.publicKey = publicKey;
        return this;
    }

    public HapiClprCompleteConnector signature(final byte[] signature) {
        this.signature = signature;
        return this;
    }

    public HapiClprCompleteConnector signatureScheme(final ClprSignatureScheme scheme) {
        this.signatureScheme = scheme;
        return this;
    }

    public HapiClprCompleteConnector salt(final byte[] salt) {
        this.salt = salt;
        return this;
    }

    public HapiClprCompleteConnector channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprCompleteConnector connectorContract(final String contractName) {
        this.connectorContractName = Optional.of(contractName);
        return this;
    }

    public HapiClprCompleteConnector connectorContractId(final ContractID contractId) {
        this.connectorContractId = Optional.of(contractId);
        return this;
    }

    public HapiClprCompleteConnector adminKey(final Key key) {
        this.adminKeyValue = Optional.of(key);
        return this;
    }

    public HapiClprCompleteConnector adminKeyName(final String keyName) {
        this.adminKeyName = Optional.of(keyName);
        return this;
    }

    public HapiClprCompleteConnector lockedStake(final long lockedStake) {
        this.lockedStake = lockedStake;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprCompleteConnector;
    }

    @Override
    protected HapiClprCompleteConnector self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprCompleteConnectorTransactionBody opBody = spec.txns()
                .<ClprCompleteConnectorTransactionBody, ClprCompleteConnectorTransactionBody.Builder>body(
                        ClprCompleteConnectorTransactionBody.class, b -> {
                            if (connectorId != null) b.setConnectorId(ByteString.copyFrom(connectorId));
                            if (publicKey != null) b.setPublicKey(ByteString.copyFrom(publicKey));
                            if (signature != null) b.setSignature(ByteString.copyFrom(signature));
                            b.setSignatureScheme(signatureScheme);
                            if (salt != null) b.setSalt(ByteString.copyFrom(salt));
                            if (channelId != null) b.setChannelId(ByteString.copyFrom(channelId));
                            connectorContractId.ifPresent(b::setConnectorContract);
                            if (connectorContractName.isPresent()) {
                                b.setConnectorContract(spec.registry().getContractId(connectorContractName.get()));
                            }
                            adminKeyValue.ifPresent(b::setAdminKey);
                            if (adminKeyName.isPresent()) {
                                b.setAdminKey(spec.registry().getKey(adminKeyName.get()));
                            }
                            b.setLockedStake(lockedStake);
                        });
        return b -> b.setClprCompleteConnector(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
