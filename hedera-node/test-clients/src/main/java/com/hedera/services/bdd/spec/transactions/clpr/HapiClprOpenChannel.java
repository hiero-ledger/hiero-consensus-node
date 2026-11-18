// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprOpenChannel;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprOpenChannelTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprOpenChannel} transactions.
 */
public class HapiClprOpenChannel extends HapiTxnOp<HapiClprOpenChannel> {

    private byte[] channelId;
    private byte[] salt;
    private byte[] peerVerifier;
    private Optional<String> verifierContractName = Optional.empty();
    private Optional<ContractID> verifierContractId = Optional.empty();
    private byte[] configProofBytes;
    private byte[] endpointManifestProofBytes;

    public HapiClprOpenChannel() {}

    public HapiClprOpenChannel channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprOpenChannel salt(final byte[] salt) {
        this.salt = salt;
        return this;
    }

    public HapiClprOpenChannel peerVerifier(final byte[] peerVerifier) {
        this.peerVerifier = peerVerifier;
        return this;
    }

    public HapiClprOpenChannel verifierContract(final String contractName) {
        this.verifierContractName = Optional.of(contractName);
        return this;
    }

    public HapiClprOpenChannel verifierContractId(final ContractID contractId) {
        this.verifierContractId = Optional.of(contractId);
        return this;
    }

    public HapiClprOpenChannel configProofBytes(final byte[] configProofBytes) {
        this.configProofBytes = configProofBytes;
        return this;
    }

    /**
     * Sets the {@code endpoint_manifest_proof_bytes} field on the completion body — required
     * by the manifest-aware verifier ABI (spec §4.8). Should be the serialized
     * {@code StateProof} bytes of the peer's finalized {@code ClprEndpointManifest} singleton,
     * as returned by {@code clprGetEndpointManifest}.
     */
    public HapiClprOpenChannel endpointManifestProofBytes(final byte[] endpointManifestProofBytes) {
        this.endpointManifestProofBytes = endpointManifestProofBytes;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprOpenChannel;
    }

    @Override
    protected HapiClprOpenChannel self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprOpenChannelTransactionBody opBody = spec.txns()
                .<ClprOpenChannelTransactionBody, ClprOpenChannelTransactionBody.Builder>body(
                        ClprOpenChannelTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (salt != null) {
                                b.setSalt(ByteString.copyFrom(salt));
                            }
                            if (peerVerifier != null) {
                                b.setPeerVerifier(ByteString.copyFrom(peerVerifier));
                            }
                            verifierContractId.ifPresent(b::setVerifierContract);
                            if (verifierContractName.isPresent()) {
                                b.setVerifierContract(spec.registry().getContractId(verifierContractName.get()));
                            }
                            if (configProofBytes != null) {
                                b.setConfigProofBytes(ByteString.copyFrom(configProofBytes));
                            }
                            if (endpointManifestProofBytes != null) {
                                b.setEndpointManifestProofBytes(ByteString.copyFrom(endpointManifestProofBytes));
                            }
                        });
        return b -> b.setClprOpenChannel(opBody);
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
