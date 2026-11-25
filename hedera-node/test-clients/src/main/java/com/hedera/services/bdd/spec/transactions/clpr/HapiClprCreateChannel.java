// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprCreateChannel;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprCreateChannelTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprCreateChannel} transactions.
 */
public class HapiClprCreateChannel extends HapiTxnOp<HapiClprCreateChannel> {

    private byte[] channelId;
    private byte[] salt;
    private byte[] peerVerifier;
    private Optional<String> verifierContractName = Optional.empty();
    private Optional<ContractID> verifierContractId = Optional.empty();
    private byte[] configProofBytes;
    private byte[] endpointManifestProofBytes;
    private byte[] trustAnchor;
    private Timestamp localConfigTimestamp;
    private byte[] localTrustAnchorHash;

    public HapiClprCreateChannel() {}

    public HapiClprCreateChannel channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprCreateChannel salt(final byte[] salt) {
        this.salt = salt;
        return this;
    }

    public HapiClprCreateChannel peerVerifier(final byte[] peerVerifier) {
        this.peerVerifier = peerVerifier;
        return this;
    }

    public HapiClprCreateChannel verifierContract(final String contractName) {
        this.verifierContractName = Optional.of(contractName);
        return this;
    }

    public HapiClprCreateChannel verifierContractId(final ContractID contractId) {
        this.verifierContractId = Optional.of(contractId);
        return this;
    }

    public HapiClprCreateChannel configProofBytes(final byte[] configProofBytes) {
        this.configProofBytes = configProofBytes;
        return this;
    }

    /**
     * Sets the {@code endpoint_manifest_proof_bytes} field on the completion body — required
     * by the manifest-aware verifier ABI (spec §4.8). Should be the serialized
     * {@code StateProof} bytes of the peer's finalized {@code ClprEndpointManifest} singleton,
     * as returned by {@code clprGetEndpointManifest}.
     */
    public HapiClprCreateChannel endpointManifestProofBytes(final byte[] endpointManifestProofBytes) {
        this.endpointManifestProofBytes = endpointManifestProofBytes;
        return this;
    }

    /** Sets the peer's initial trust anchor, which must equal the one in the proven peer configuration. */
    public HapiClprCreateChannel trustAnchor(final byte[] trustAnchor) {
        this.trustAnchor = trustAnchor;
        return this;
    }

    /** Sets the local configuration timestamp the peer holds; the ledger uses its current one when unset. */
    public HapiClprCreateChannel localConfigTimestamp(final Timestamp localConfigTimestamp) {
        this.localConfigTimestamp = localConfigTimestamp;
        return this;
    }

    /** Sets the optional hash of the trust anchor the peer holds for the local ledger. */
    public HapiClprCreateChannel localTrustAnchorHash(final byte[] localTrustAnchorHash) {
        this.localTrustAnchorHash = localTrustAnchorHash;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprCreateChannel;
    }

    @Override
    protected HapiClprCreateChannel self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprCreateChannelTransactionBody opBody = spec.txns()
                .<ClprCreateChannelTransactionBody, ClprCreateChannelTransactionBody.Builder>body(
                        ClprCreateChannelTransactionBody.class, b -> {
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
                            if (trustAnchor != null) {
                                b.setTrustAnchor(ByteString.copyFrom(trustAnchor));
                            }
                            if (localConfigTimestamp != null) {
                                b.setLocalConfigTimestamp(localConfigTimestamp);
                            }
                            if (localTrustAnchorHash != null) {
                                b.setLocalTrustAnchorHash(ByteString.copyFrom(localTrustAnchorHash));
                            }
                        });
        return b -> b.setClprCreateChannel(opBody);
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
