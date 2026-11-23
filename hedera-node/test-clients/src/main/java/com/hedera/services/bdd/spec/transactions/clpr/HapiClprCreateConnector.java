// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprCreateConnector;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprCreateConnectorTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.security.PrivateKey;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.base.utility.CommonUtils;

/**
 * HAPI spec operation for {@code ClprCreateConnector} transactions.
 */
public class HapiClprCreateConnector extends HapiTxnOp<HapiClprCreateConnector> {

    private byte[] connectorId;
    private Key identityKey;
    private PrivateKey signer;
    private byte[] salt;
    private byte[] channelId;
    private Optional<String> connectorContractName = Optional.empty();
    private Optional<ContractID> connectorContractId = Optional.empty();
    private Optional<Key> adminKeyValue = Optional.empty();
    private Optional<String> adminKeyName = Optional.empty();
    private long lockedStake;

    public HapiClprCreateConnector() {}

    public HapiClprCreateConnector connectorId(final byte[] connectorId) {
        this.connectorId = connectorId;
        return this;
    }

    public HapiClprCreateConnector ed25519Identity(final byte[] seed) {
        final var privateKey = Ed25519Utils.keyFrom(seed);
        identityKey = Key.newBuilder()
                .setEd25519(ByteString.copyFrom(privateKey.getAbyte()))
                .build();
        signer = privateKey;
        return this;
    }

    public HapiClprCreateConnector secp256k1Identity(final byte[] scalar) {
        final var privateKey = Secp256k1Utils.readECKeyFrom(scalar);
        identityKey = Key.newBuilder()
                .setECDSASecp256K1(ByteString.copyFrom(Secp256k1Utils.extractEcdsaPublicKey(privateKey)))
                .build();
        signer = privateKey;
        return this;
    }

    public Key identityKey() {
        return identityKey;
    }

    public HapiClprCreateConnector salt(final byte[] salt) {
        this.salt = salt;
        return this;
    }

    public HapiClprCreateConnector channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprCreateConnector connectorContract(final String contractName) {
        this.connectorContractName = Optional.of(contractName);
        return this;
    }

    public HapiClprCreateConnector connectorContractId(final ContractID contractId) {
        this.connectorContractId = Optional.of(contractId);
        return this;
    }

    public HapiClprCreateConnector adminKey(final Key key) {
        this.adminKeyValue = Optional.of(key);
        return this;
    }

    public HapiClprCreateConnector adminKeyName(final String keyName) {
        this.adminKeyName = Optional.of(keyName);
        return this;
    }

    public HapiClprCreateConnector lockedStake(final long lockedStake) {
        this.lockedStake = lockedStake;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprCreateConnector;
    }

    @Override
    protected HapiClprCreateConnector self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprCreateConnectorTransactionBody opBody = spec.txns()
                .<ClprCreateConnectorTransactionBody, ClprCreateConnectorTransactionBody.Builder>body(
                        ClprCreateConnectorTransactionBody.class, b -> {
                            if (connectorId != null) b.setConnectorId(ByteString.copyFrom(connectorId));
                            if (identityKey != null) b.setIdentityKey(identityKey);
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
        return b -> b.setClprCreateConnector(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        if (identityKey == null) {
            return super.defaultSigners();
        }
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> {
            final var ed25519 = identityKey.hasEd25519();
            final var publicKey = ed25519 ? identityKey.getEd25519() : identityKey.getECDSASecp256K1();
            final var hex = CommonUtils.hex(publicKey.toByteArray());
            final var name = "clprConnector-" + hex;
            spec.registry().saveKey(name, identityKey);
            spec.keys().incorporate(name, hex, signer, ed25519 ? SigControl.ED25519_ON : SigControl.SECP256K1_ON);
            return identityKey;
        });
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
