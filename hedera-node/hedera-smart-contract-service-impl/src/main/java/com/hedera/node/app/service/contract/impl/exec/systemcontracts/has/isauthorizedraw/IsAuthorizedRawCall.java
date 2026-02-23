// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE_TYPE_MISMATCHING_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.SignatureMapUtils.stripRecoveryIdFromEcdsaSignatures;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Function;
import org.hyperledger.besu.evm.frame.MessageFrame;

/** HIP-632 method: `isAuthorizedRaw` */
public class IsAuthorizedRawCall extends AbstractCall {

    private static final int EIP_155_V_MIN_LENGTH = 1;
    private static final int EIP_155_V_MAX_LENGTH = 8; // we limit chainId to fit in a `long`
    private static final int EC_SIGNATURE_WITHOUT_V_LENGTH = 64;
    public static final int EC_SIGNATURE_MIN_LENGTH = EC_SIGNATURE_WITHOUT_V_LENGTH + EIP_155_V_MIN_LENGTH;
    public static final int EC_SIGNATURE_MAX_LENGTH = EC_SIGNATURE_WITHOUT_V_LENGTH + EIP_155_V_MAX_LENGTH;
    public static final int ED_SIGNATURE_LENGTH = 64;

    private final Address address;
    private final byte[] messageHash;
    private final byte[] signature;

    private final CustomGasCalculator customGasCalculator;

    private final SignatureVerifier signatureVerifier;

    public enum SignatureType {
        INVALID,
        EC,
        ED
    }

    public IsAuthorizedRawCall(
            @NonNull final HasCallAttempt attempt,
            final Address address,
            @NonNull final byte[] messageHash,
            @NonNull final byte[] signature,
            @NonNull final CustomGasCalculator customGasCalculator) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), true);
        this.address = requireNonNull(address);
        this.messageHash = requireNonNull(messageHash);
        this.signature = requireNonNull(signature);
        this.customGasCalculator = requireNonNull(customGasCalculator);
        signatureVerifier = requireNonNull(attempt.signatureVerifier());
    }

    @NonNull
    @Override
    public PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame, "frame");

        final var signatureType = signatureTypeFromItsLength(signature);

        // Now we know how much gas this call will cost
        final long gasRequirement =
                switch (signatureType) {
                    case EC -> customGasCalculator.getEcrecPrecompiledContractGasCost();
                    case ED -> customGasCalculator.getEdSignatureVerificationSystemContractGasCost();
                    case INVALID ->
                        Math.min(
                                customGasCalculator.getEcrecPrecompiledContractGasCost(),
                                customGasCalculator.getEdSignatureVerificationSystemContractGasCost());
                };

        // Prepare the short-circuit error status returns
        final Function<ResponseCodeEnum, PricedResult> bail = rce -> reversionWith(rce, frame.getRemainingGas());

        // Must have a valid signature type to continue
        if (signatureType == SignatureType.INVALID)
            return bail.apply(INVALID_TRANSACTION_BODY /* should be: "invalid argument to precompile" */);

        // Fail immediately if user didn't supply sufficient gas
        final long availableGas = frame.getRemainingGas();
        if (availableGas < gasRequirement) return bail.apply(INSUFFICIENT_GAS);

        // Validate parameters according to signature type
        if (!switch (signatureType) {
            case EC -> messageHash.length == 32;
            case ED -> true;
            case INVALID -> throw new IllegalStateException("Unexpected value: " + signatureType);
        }) return bail.apply(INVALID_TRANSACTION_BODY /* should be: "invalid argument to precompile */);

        // Gotta have an account that the given address is an alias for
        final long accountNum = accountNumberForEvmReference(address, nativeOperations());
        if (!isValidAccount(accountNum, signatureType)) return bail.apply(INVALID_ACCOUNT_ID);
        final var account = requireNonNull(enhancement
                .nativeOperations()
                .getAccount(enhancement.nativeOperations().entityIdFactory().newAccountId(accountNum)));

        // If ED, then require a key on the account
        final Optional<Key> key;
        if (signatureType == SignatureType.ED) {
            key = Optional.ofNullable(account.key());
            if (key.isEmpty()) return bail.apply(INVALID_TRANSACTION_BODY /* should be: "account must have key" */);
        } else key = Optional.empty();

        if (key.isPresent()) {
            // Key must be simple (for isAuthorizedRaw)
            final Key ky = key.get();
            final boolean keyIsSimple = !ky.hasKeyList() && !ky.hasThresholdKey();
            if (!keyIsSimple) return bail.apply(INVALID_TRANSACTION_BODY /* should be: "account key must be simple" */);

            // Key must match signature type
            if (!switch (signatureType) {
                case EC -> key.get().hasEcdsaSecp256k1();
                case ED -> key.get().hasEd25519();
                case INVALID -> false;
            }) return bail.apply(INVALID_SIGNATURE_TYPE_MISMATCHING_KEY);
        }

        // Finally: Do the signature validation we came here for!
        final boolean authorized =
                switch (signatureType) {
                    case EC -> validateEcSignature(account);
                    case ED -> validateEdSignature(account, key.get());
                    case INVALID -> false;
                };

        final var result =
                gasOnly(successResult(encodedAuthorizationOutput(authorized), gasRequirement), SUCCESS, true);
        return result;
    }

    /**
     * Validate EVM signature - EC key
     */
    public boolean validateEcSignature(final Account account) {
        requireNonNull(account, "account");

        if (account.key() == null || !account.key().hasEcdsaSecp256k1()) return false;

        var signatureMap = SignatureMap.newBuilder()
                .sigPair(SignaturePair.newBuilder()
                        .pubKeyPrefix(account.key().ecdsaSecp256k1())
                        .ecdsaSecp256k1(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(signature))
                        .build())
                .build();

        signatureMap = stripRecoveryIdFromEcdsaSignatures(signatureMap);
        return signatureVerifier.verifySignature(
                account.key(),
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(messageHash),
                SignatureVerifier.MessageType.KECCAK_256_HASH,
                signatureMap,
                ky -> SignatureVerifier.SimpleKeyStatus.ONLY_IF_CRYPTO_SIG_VALID);
    }

    /** Validate (native Hedera) ED25519 signature */
    public boolean validateEdSignature(@NonNull final Account account, @NonNull final Key key) {
        requireNonNull(account, "account");
        requireNonNull(key, "key");

        if (account.key() == null || !account.key().hasEd25519()) return false;

        final var signatureMap = SignatureMap.newBuilder()
                .sigPair(SignaturePair.newBuilder()
                        .pubKeyPrefix(key.ed25519())
                        .ed25519(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(signature))
                        .build())
                .build();

        return signatureVerifier.verifySignature(
                key,
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(messageHash),
                SignatureVerifier.MessageType.KECCAK_256_HASH,
                signatureMap,
                ky -> SignatureVerifier.SimpleKeyStatus.ONLY_IF_CRYPTO_SIG_VALID);
    }

    /** Encode the _output_ of our system contract: it's a boolean */
    @NonNull
    ByteBuffer encodedAuthorizationOutput(final boolean authorized) {
        return IsAuthorizedRawTranslator.IS_AUTHORIZED_RAW.getOutputs().encode(Tuple.singleton(authorized));
    }

    public boolean isValidAccount(final long accountNum, @NonNull final SignatureType signatureType) {
        requireNonNull(signatureType, "signatureType");

        // If the account num is negative, it is invalid
        if (accountNum < 0) {
            return false;
        }

        // If the signature is for an ecdsa key, the HIP states that the account must have an evm address rather than a
        // long zero address
        if (signatureType == SignatureType.EC) {
            return !isLongZero(address);
        }

        return true;
    }

    @NonNull
    private SignatureType signatureTypeFromItsLength(@NonNull final byte[] signature) {
        final var len = signature.length;

        if (EC_SIGNATURE_MIN_LENGTH <= len && len <= EC_SIGNATURE_MAX_LENGTH) return SignatureType.EC;
        else if (ED_SIGNATURE_LENGTH == len) return SignatureType.ED;

        return SignatureType.INVALID;
    }
}
