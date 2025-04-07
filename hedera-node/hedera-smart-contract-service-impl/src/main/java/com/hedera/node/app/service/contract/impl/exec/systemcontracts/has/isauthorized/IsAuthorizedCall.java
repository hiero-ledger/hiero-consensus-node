// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static com.hedera.pbj.runtime.io.buffer.Bytes.wrap;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class IsAuthorizedCall extends AbstractCall {

    private final Address address;
    private final byte[] message;
    private final byte[] signatureBlob;

    private final CustomGasCalculator customGasCalculator;

    private final SignatureVerifier signatureVerifier;

    public IsAuthorizedCall(
            @NonNull final HasCallAttempt attempt,
            final Address address,
            @NonNull final byte[] message,
            @NonNull final byte[] signatureBlob,
            @NonNull CustomGasCalculator gasCalculator) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), true);
        this.address = requireNonNull(address, "address");
        this.message = requireNonNull(message, "message");
        this.signatureBlob = requireNonNull(signatureBlob, "signatureBlob");
        this.customGasCalculator = requireNonNull(gasCalculator, "gasCalculator");
        this.signatureVerifier = requireNonNull(attempt.signatureVerifier());
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {

        final Function<ResponseCodeEnum, PricedResult> bail = rce -> encodedOutput(rce, false, frame.getRemainingGas());

        final long accountNum = accountNumberForEvmReference(address, nativeOperations());
        if (!isValidAccount(accountNum)) return bail.apply(INVALID_ACCOUNT_ID);
        final var account = requireNonNull(enhancement
                .nativeOperations()
                .getAccount(enhancement.nativeOperations().entityIdFactory().newAccountId(accountNum)));

        // Q: Do we get a key for hollow accounts and auto-created accounts?
        final var key = account.key();
        if (key == null) return bail.apply(INVALID_TRANSACTION_BODY);

        SignatureMap sigMap;
        try {
            sigMap = requireNonNull(SignatureMap.PROTOBUF.parse(wrap(signatureBlob)));
        } catch (@NonNull final ParseException | NullPointerException ex) {
            return bail.apply(INVALID_TRANSACTION_BODY);
        }
        sigMap = fixEcSignaturesInMap(sigMap);

        final var keyCounts = signatureVerifier.countSimpleKeys(key);
        long gasRequirement = keyCounts.numEcdsaKeys() * customGasCalculator.getEcrecPrecompiledContractGasCost()
                + keyCounts.numEddsaKeys() * customGasCalculator.getEdSignatureVerificationSystemContractGasCost();

        final var authorized = verifyMessage(
                key, wrap(message), MessageType.RAW, sigMap, ky -> SimpleKeyStatus.ONLY_IF_CRYPTO_SIG_VALID);

        final var result = encodedOutput(SUCCESS, authorized, gasRequirement);
        return result;
    }

    protected boolean verifyMessage(
            @NonNull final Key key,
            @NonNull final Bytes message,
            @NonNull final MessageType msgType,
            @NonNull final SignatureMap signatureMap,
            @NonNull final Function<Key, SimpleKeyStatus> keyHandlingHook) {
        return signatureVerifier.verifySignature(key, message, msgType, signatureMap, keyHandlingHook);
    }

    @NonNull
    protected PricedResult encodedOutput(
            final ResponseCodeEnum rce, final boolean authorized, final long gasRequirement) {
        final long code = rce.protoOrdinal();
        final var output = IsAuthorizedTranslator.IS_AUTHORIZED.getOutputs().encode(Tuple.of(code, authorized));
        final var result = gasOnly(successResult(output, gasRequirement), SUCCESS, true);
        return result;
    }

    /**
     * The Ethereum world uses 65+ byte EC signatures, our cryptography library uses 64 byte EC signatures.  The
     * difference is the addition of an extra "parity" field at the end of the 64 byte signature (used so that
     * `ECRECOVER` can recover the public key (== Ethereum address) from the signature.  And, the chain id can
     * be encoded in that field (per EIP-155) and if the chain id is large enough (like Hedera mainnet/testnet
     * chain ids) that last field can be more than one byte.
     *
     * This method is a shim for that mismatch. It strips the extra bytes off any 65+ byte EC signatures it finds.
     *
     * @param sigMap Signature map from user - possibly contains 65+ byte EC signatures
     * @return Signature map with only 64 byte EC signatures (and all else unchanged)
     */
    public @NonNull SignatureMap fixEcSignaturesInMap(@NonNull final SignatureMap sigMap) {
        final List<SignaturePair> newPairs = new ArrayList<>();
        for (var spair : sigMap.sigPair()) {
            if (spair.hasEcdsaSecp256k1()) {
                final var ecSig = requireNonNull(spair.ecdsaSecp256k1());
                if (ecSig.length() > 64) {
                    spair = new SignaturePair(
                            spair.pubKeyPrefix(), new OneOf<>(SignatureOneOfType.ECDSA_SECP256K1, ecSig.slice(0, 64)));
                }
            }
            newPairs.add(spair);
        }
        return new SignatureMap(newPairs);
    }

    boolean isValidAccount(final long accountNum) {
        // invalid if accountNum is negative
        if (accountNum < 0) return false;
        return true;
    }
}
