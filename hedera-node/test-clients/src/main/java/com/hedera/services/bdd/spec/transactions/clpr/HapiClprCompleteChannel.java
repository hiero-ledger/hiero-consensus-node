// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprCompleteChannel;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprCompleteChannelTransactionBody;
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
 * HAPI spec operation for {@code ClprCompleteChannel} transactions.
 */
public class HapiClprCompleteChannel extends HapiTxnOp<HapiClprCompleteChannel> {

    private byte[] channelId;
    private byte[] publicKey;
    private byte[] signature;
    private ClprSignatureScheme signatureScheme = ClprSignatureScheme.ED25519;
    private Optional<String> verifierContractName = Optional.empty();
    private Optional<ContractID> verifierContractId = Optional.empty();
    private byte[] configProofBytes;
    private byte[] endpointManifestProofBytes;

    public HapiClprCompleteChannel() {}

    public HapiClprCompleteChannel channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprCompleteChannel publicKey(final byte[] publicKey) {
        this.publicKey = publicKey;
        return this;
    }

    public HapiClprCompleteChannel signature(final byte[] signature) {
        this.signature = signature;
        return this;
    }

    public HapiClprCompleteChannel signatureScheme(final ClprSignatureScheme scheme) {
        this.signatureScheme = scheme;
        return this;
    }

    public HapiClprCompleteChannel verifierContract(final String contractName) {
        this.verifierContractName = Optional.of(contractName);
        return this;
    }

    public HapiClprCompleteChannel verifierContractId(final ContractID contractId) {
        this.verifierContractId = Optional.of(contractId);
        return this;
    }

    public HapiClprCompleteChannel configProofBytes(final byte[] configProofBytes) {
        this.configProofBytes = configProofBytes;
        return this;
    }

    /**
     * Sets the {@code endpoint_manifest_proof_bytes} field on the completion body — required
     * when {@code clpr.endpointManifestEnabled=true} (spec §4.8). Should be the serialized
     * {@code StateProof} bytes of the peer's finalized {@code ClprEndpointManifest} singleton,
     * as returned by {@code clprGetEndpointManifest}.
     */
    public HapiClprCompleteChannel endpointManifestProofBytes(final byte[] endpointManifestProofBytes) {
        this.endpointManifestProofBytes = endpointManifestProofBytes;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprCompleteChannel;
    }

    @Override
    protected HapiClprCompleteChannel self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprCompleteChannelTransactionBody opBody = spec.txns()
                .<ClprCompleteChannelTransactionBody, ClprCompleteChannelTransactionBody.Builder>body(
                        ClprCompleteChannelTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (publicKey != null) {
                                b.setPublicKey(ByteString.copyFrom(publicKey));
                            }
                            if (signature != null) {
                                b.setSignature(ByteString.copyFrom(signature));
                            }
                            b.setSignatureScheme(signatureScheme);
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
        return b -> b.setClprCompleteChannel(opBody);
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
