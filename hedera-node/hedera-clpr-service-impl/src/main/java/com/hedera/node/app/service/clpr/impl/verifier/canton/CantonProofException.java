// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Verification failure raised by the Canton proof verifier. Each factory corresponds to one custom
 * {@code error} of the reference Solidity verifier; every one maps to
 * {@link ResponseCodeEnum#CLPR_BUNDLE_VERIFICATION_FAILED}.
 *
 * <p>Like {@link com.hedera.node.app.service.clpr.impl.verifier.ProofException}, this is thrown internally
 * and must be translated by CLPR handlers to their public failure surface. New factories are added here as
 * later verifier components need them.
 */
public final class CantonProofException extends RuntimeException {
    private static final String VERIFIER = "CantonVerifier";

    private final String errorName;

    public CantonProofException(@NonNull final String errorName) {
        super(VERIFIER + ": " + Objects.requireNonNull(errorName, "errorName"));
        this.errorName = errorName;
    }

    public CantonProofException(@NonNull final String errorName, @NonNull final String detail) {
        super(VERIFIER + ": " + Objects.requireNonNull(errorName, "errorName") + ": "
                + Objects.requireNonNull(detail, "detail"));
        this.errorName = errorName;
    }

    /** The Solidity error name this failure corresponds to, e.g. {@code RootHashMismatch}. */
    @NonNull
    public String errorName() {
        return errorName;
    }

    /** The response code this failure maps to. */
    @NonNull
    public ResponseCodeEnum responseCode() {
        return ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
    }

    /** Solidity error {@code ApproverMissing}. */
    @NonNull
    public static CantonProofException approverMissing() {
        return new CantonProofException("ApproverMissing");
    }

    /** Solidity error {@code ApproverMissing} with detail. */
    @NonNull
    public static CantonProofException approverMissing(@NonNull final String detail) {
        return new CantonProofException("ApproverMissing", detail);
    }

    /** Solidity error {@code AuthorizationChainNotSupported}. */
    @NonNull
    public static CantonProofException authorizationChainNotSupported() {
        return new CantonProofException("AuthorizationChainNotSupported");
    }

    /** Solidity error {@code AuthorizationChainNotSupported} with detail. */
    @NonNull
    public static CantonProofException authorizationChainNotSupported(@NonNull final String detail) {
        return new CantonProofException("AuthorizationChainNotSupported", detail);
    }

    /** Solidity error {@code CantonProtoMalformed}. */
    @NonNull
    public static CantonProofException cantonProtoMalformed() {
        return new CantonProofException("CantonProtoMalformed");
    }

    /** Solidity error {@code CantonProtoMalformed} with detail. */
    @NonNull
    public static CantonProofException cantonProtoMalformed(@NonNull final String detail) {
        return new CantonProofException("CantonProtoMalformed", detail);
    }

    /** Solidity error {@code CantonProtoMissingField}. */
    @NonNull
    public static CantonProofException cantonProtoMissingField() {
        return new CantonProofException("CantonProtoMissingField");
    }

    /** Solidity error {@code CantonProtoMissingField} with detail. */
    @NonNull
    public static CantonProofException cantonProtoMissingField(@NonNull final String detail) {
        return new CantonProofException("CantonProtoMissingField", detail);
    }

    /** Solidity error {@code InvalidChainId}. */
    @NonNull
    public static CantonProofException invalidChainId() {
        return new CantonProofException("InvalidChainId");
    }

    /** Solidity error {@code InvalidChainId} with detail. */
    @NonNull
    public static CantonProofException invalidChainId(@NonNull final String detail) {
        return new CantonProofException("InvalidChainId", detail);
    }

    /** Solidity error {@code InvalidConfig}. */
    @NonNull
    public static CantonProofException invalidConfig() {
        return new CantonProofException("InvalidConfig");
    }

    /** Solidity error {@code InvalidConfig} with detail. */
    @NonNull
    public static CantonProofException invalidConfig(@NonNull final String detail) {
        return new CantonProofException("InvalidConfig", detail);
    }

    /** Solidity error {@code InvalidLsuAnnouncement}. */
    @NonNull
    public static CantonProofException invalidLsuAnnouncement() {
        return new CantonProofException("InvalidLsuAnnouncement");
    }

    /** Solidity error {@code InvalidLsuAnnouncement} with detail. */
    @NonNull
    public static CantonProofException invalidLsuAnnouncement(@NonNull final String detail) {
        return new CantonProofException("InvalidLsuAnnouncement", detail);
    }

    /** Solidity error {@code InvalidMediatorSet}. */
    @NonNull
    public static CantonProofException invalidMediatorSet() {
        return new CantonProofException("InvalidMediatorSet");
    }

    /** Solidity error {@code InvalidMediatorSet} with detail. */
    @NonNull
    public static CantonProofException invalidMediatorSet(@NonNull final String detail) {
        return new CantonProofException("InvalidMediatorSet", detail);
    }

    /** Solidity error {@code InvalidMediatorSignature}. */
    @NonNull
    public static CantonProofException invalidMediatorSignature() {
        return new CantonProofException("InvalidMediatorSignature");
    }

    /** Solidity error {@code InvalidMediatorSignature} with detail. */
    @NonNull
    public static CantonProofException invalidMediatorSignature(@NonNull final String detail) {
        return new CantonProofException("InvalidMediatorSignature", detail);
    }

    /** Solidity error {@code InvalidPayloadShape}. */
    @NonNull
    public static CantonProofException invalidPayloadShape() {
        return new CantonProofException("InvalidPayloadShape");
    }

    /** Solidity error {@code InvalidPayloadShape} with detail. */
    @NonNull
    public static CantonProofException invalidPayloadShape(@NonNull final String detail) {
        return new CantonProofException("InvalidPayloadShape", detail);
    }

    /** Solidity error {@code InvalidServiceAddress}. */
    @NonNull
    public static CantonProofException invalidServiceAddress() {
        return new CantonProofException("InvalidServiceAddress");
    }

    /** Solidity error {@code InvalidServiceAddress} with detail. */
    @NonNull
    public static CantonProofException invalidServiceAddress(@NonNull final String detail) {
        return new CantonProofException("InvalidServiceAddress", detail);
    }

    /** Solidity error {@code InvalidSet}. */
    @NonNull
    public static CantonProofException invalidSet() {
        return new CantonProofException("InvalidSet");
    }

    /** Solidity error {@code InvalidSet} with detail. */
    @NonNull
    public static CantonProofException invalidSet(@NonNull final String detail) {
        return new CantonProofException("InvalidSet", detail);
    }

    /** Solidity error {@code InvalidSignerKey}. */
    @NonNull
    public static CantonProofException invalidSignerKey() {
        return new CantonProofException("InvalidSignerKey");
    }

    /** Solidity error {@code InvalidSignerKey} with detail. */
    @NonNull
    public static CantonProofException invalidSignerKey(@NonNull final String detail) {
        return new CantonProofException("InvalidSignerKey", detail);
    }

    /** Solidity error {@code InvalidSynchronizerId}. */
    @NonNull
    public static CantonProofException invalidSynchronizerId() {
        return new CantonProofException("InvalidSynchronizerId");
    }

    /** Solidity error {@code InvalidSynchronizerId} with detail. */
    @NonNull
    public static CantonProofException invalidSynchronizerId(@NonNull final String detail) {
        return new CantonProofException("InvalidSynchronizerId", detail);
    }

    /** Solidity error {@code InvalidTopologyChange}. */
    @NonNull
    public static CantonProofException invalidTopologyChange() {
        return new CantonProofException("InvalidTopologyChange");
    }

    /** Solidity error {@code InvalidTopologyChange} with detail. */
    @NonNull
    public static CantonProofException invalidTopologyChange(@NonNull final String detail) {
        return new CantonProofException("InvalidTopologyChange", detail);
    }

    /** Solidity error {@code InvalidTopologySignature}. */
    @NonNull
    public static CantonProofException invalidTopologySignature() {
        return new CantonProofException("InvalidTopologySignature");
    }

    /** Solidity error {@code InvalidTopologySignature} with detail. */
    @NonNull
    public static CantonProofException invalidTopologySignature(@NonNull final String detail) {
        return new CantonProofException("InvalidTopologySignature", detail);
    }

    /** Solidity error {@code InvalidTrustAnchor}. */
    @NonNull
    public static CantonProofException invalidTrustAnchor() {
        return new CantonProofException("InvalidTrustAnchor");
    }

    /** Solidity error {@code InvalidTrustAnchor} with detail. */
    @NonNull
    public static CantonProofException invalidTrustAnchor(@NonNull final String detail) {
        return new CantonProofException("InvalidTrustAnchor", detail);
    }

    /** Solidity error {@code ManifestMismatch}. */
    @NonNull
    public static CantonProofException manifestMismatch() {
        return new CantonProofException("ManifestMismatch");
    }

    /** Solidity error {@code ManifestMismatch} with detail. */
    @NonNull
    public static CantonProofException manifestMismatch(@NonNull final String detail) {
        return new CantonProofException("ManifestMismatch", detail);
    }

    /** Solidity error {@code MediatorQuorumNotMet}. */
    @NonNull
    public static CantonProofException mediatorQuorumNotMet() {
        return new CantonProofException("MediatorQuorumNotMet");
    }

    /** Solidity error {@code MediatorQuorumNotMet} with detail. */
    @NonNull
    public static CantonProofException mediatorQuorumNotMet(@NonNull final String detail) {
        return new CantonProofException("MediatorQuorumNotMet", detail);
    }

    /** Solidity error {@code MetadataMismatch}. */
    @NonNull
    public static CantonProofException metadataMismatch() {
        return new CantonProofException("MetadataMismatch");
    }

    /** Solidity error {@code MetadataMismatch} with detail. */
    @NonNull
    public static CantonProofException metadataMismatch(@NonNull final String detail) {
        return new CantonProofException("MetadataMismatch", detail);
    }

    /** Solidity error {@code NotApproved}. */
    @NonNull
    public static CantonProofException notApproved() {
        return new CantonProofException("NotApproved");
    }

    /** Solidity error {@code NotApproved} with detail. */
    @NonNull
    public static CantonProofException notApproved(@NonNull final String detail) {
        return new CantonProofException("NotApproved", detail);
    }

    /** Solidity error {@code ReplyTargetMismatch}. */
    @NonNull
    public static CantonProofException replyTargetMismatch() {
        return new CantonProofException("ReplyTargetMismatch");
    }

    /** Solidity error {@code ReplyTargetMismatch} with detail. */
    @NonNull
    public static CantonProofException replyTargetMismatch(@NonNull final String detail) {
        return new CantonProofException("ReplyTargetMismatch", detail);
    }

    /** Solidity error {@code RolledBack}. */
    @NonNull
    public static CantonProofException rolledBack() {
        return new CantonProofException("RolledBack");
    }

    /** Solidity error {@code RolledBack} with detail. */
    @NonNull
    public static CantonProofException rolledBack(@NonNull final String detail) {
        return new CantonProofException("RolledBack", detail);
    }

    /** Solidity error {@code RootHashMismatch}. */
    @NonNull
    public static CantonProofException rootHashMismatch() {
        return new CantonProofException("RootHashMismatch");
    }

    /** Solidity error {@code RootHashMismatch} with detail. */
    @NonNull
    public static CantonProofException rootHashMismatch(@NonNull final String detail) {
        return new CantonProofException("RootHashMismatch", detail);
    }

    /** Solidity error {@code RotationsNotSupported}. */
    @NonNull
    public static CantonProofException rotationsNotSupported() {
        return new CantonProofException("RotationsNotSupported");
    }

    /** Solidity error {@code RotationsNotSupported} with detail. */
    @NonNull
    public static CantonProofException rotationsNotSupported(@NonNull final String detail) {
        return new CantonProofException("RotationsNotSupported", detail);
    }

    /** Solidity error {@code SenderMismatch}. */
    @NonNull
    public static CantonProofException senderMismatch() {
        return new CantonProofException("SenderMismatch");
    }

    /** Solidity error {@code SenderMismatch} with detail. */
    @NonNull
    public static CantonProofException senderMismatch(@NonNull final String detail) {
        return new CantonProofException("SenderMismatch", detail);
    }

    /** Solidity error {@code SerialMismatch}. */
    @NonNull
    public static CantonProofException serialMismatch() {
        return new CantonProofException("SerialMismatch");
    }

    /** Solidity error {@code SerialMismatch} with detail. */
    @NonNull
    public static CantonProofException serialMismatch(@NonNull final String detail) {
        return new CantonProofException("SerialMismatch", detail);
    }

    /** Solidity error {@code ServiceCallFailed}. */
    @NonNull
    public static CantonProofException serviceCallFailed() {
        return new CantonProofException("ServiceCallFailed");
    }

    /** Solidity error {@code ServiceCallFailed} with detail. */
    @NonNull
    public static CantonProofException serviceCallFailed(@NonNull final String detail) {
        return new CantonProofException("ServiceCallFailed", detail);
    }

    /** Solidity error {@code SessionKeysNotSupported}. */
    @NonNull
    public static CantonProofException sessionKeysNotSupported() {
        return new CantonProofException("SessionKeysNotSupported");
    }

    /** Solidity error {@code SessionKeysNotSupported} with detail. */
    @NonNull
    public static CantonProofException sessionKeysNotSupported(@NonNull final String detail) {
        return new CantonProofException("SessionKeysNotSupported", detail);
    }

    /** Solidity error {@code StaleVerdict}. */
    @NonNull
    public static CantonProofException staleVerdict() {
        return new CantonProofException("StaleVerdict");
    }

    /** Solidity error {@code StaleVerdict} with detail. */
    @NonNull
    public static CantonProofException staleVerdict(@NonNull final String detail) {
        return new CantonProofException("StaleVerdict", detail);
    }

    /** Solidity error {@code SynchronizerMismatch}. */
    @NonNull
    public static CantonProofException synchronizerMismatch() {
        return new CantonProofException("SynchronizerMismatch");
    }

    /** Solidity error {@code SynchronizerMismatch} with detail. */
    @NonNull
    public static CantonProofException synchronizerMismatch(@NonNull final String detail) {
        return new CantonProofException("SynchronizerMismatch", detail);
    }

    /** Solidity error {@code TopologyUnauthorized}. */
    @NonNull
    public static CantonProofException topologyUnauthorized() {
        return new CantonProofException("TopologyUnauthorized");
    }

    /** Solidity error {@code TopologyUnauthorized} with detail. */
    @NonNull
    public static CantonProofException topologyUnauthorized(@NonNull final String detail) {
        return new CantonProofException("TopologyUnauthorized", detail);
    }

    /** Solidity error {@code TrustSetMismatch}. */
    @NonNull
    public static CantonProofException trustSetMismatch() {
        return new CantonProofException("TrustSetMismatch");
    }

    /** Solidity error {@code TrustSetMismatch} with detail. */
    @NonNull
    public static CantonProofException trustSetMismatch(@NonNull final String detail) {
        return new CantonProofException("TrustSetMismatch", detail);
    }

    /** Solidity error {@code UnsupportedLeafVersion}. */
    @NonNull
    public static CantonProofException unsupportedLeafVersion() {
        return new CantonProofException("UnsupportedLeafVersion");
    }

    /** Solidity error {@code UnsupportedLeafVersion} with detail. */
    @NonNull
    public static CantonProofException unsupportedLeafVersion(@NonNull final String detail) {
        return new CantonProofException("UnsupportedLeafVersion", detail);
    }

    /** Solidity error {@code WrongAction}. */
    @NonNull
    public static CantonProofException wrongAction() {
        return new CantonProofException("WrongAction");
    }

    /** Solidity error {@code WrongAction} with detail. */
    @NonNull
    public static CantonProofException wrongAction(@NonNull final String detail) {
        return new CantonProofException("WrongAction", detail);
    }

    /** Solidity error {@code WrongContract}. */
    @NonNull
    public static CantonProofException wrongContract() {
        return new CantonProofException("WrongContract");
    }

    /** Solidity error {@code WrongContract} with detail. */
    @NonNull
    public static CantonProofException wrongContract(@NonNull final String detail) {
        return new CantonProofException("WrongContract", detail);
    }

    /** Solidity error {@code WrongMessageType}. */
    @NonNull
    public static CantonProofException wrongMessageType() {
        return new CantonProofException("WrongMessageType");
    }

    /** Solidity error {@code WrongMessageType} with detail. */
    @NonNull
    public static CantonProofException wrongMessageType(@NonNull final String detail) {
        return new CantonProofException("WrongMessageType", detail);
    }

    /** Solidity error {@code WrongPeerChain}. */
    @NonNull
    public static CantonProofException wrongPeerChain() {
        return new CantonProofException("WrongPeerChain");
    }

    /** Solidity error {@code WrongPeerChain} with detail. */
    @NonNull
    public static CantonProofException wrongPeerChain(@NonNull final String detail) {
        return new CantonProofException("WrongPeerChain", detail);
    }

    /** Solidity error {@code WrongViewType}. */
    @NonNull
    public static CantonProofException wrongViewType() {
        return new CantonProofException("WrongViewType");
    }

    /** Solidity error {@code WrongViewType} with detail. */
    @NonNull
    public static CantonProofException wrongViewType(@NonNull final String detail) {
        return new CantonProofException("WrongViewType", detail);
    }
}
