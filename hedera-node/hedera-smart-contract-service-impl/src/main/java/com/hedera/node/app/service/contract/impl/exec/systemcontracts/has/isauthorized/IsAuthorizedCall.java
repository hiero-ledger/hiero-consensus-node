// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static com.hedera.node.app.service.contract.impl.utils.SignatureMapUtils.stripRecoveryIdFromEcdsaSignatures;
import static com.hedera.pbj.runtime.io.buffer.Bytes.wrap;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
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
            @NonNull final CustomGasCalculator gasCalculator) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), true);
        this.address = requireNonNull(address, "address");
        this.message = requireNonNull(message, "message");
        this.signatureBlob = requireNonNull(signatureBlob, "signatureBlob");
        customGasCalculator = requireNonNull(gasCalculator, "gasCalculator");
        signatureVerifier = requireNonNull(attempt.signatureVerifier());
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
        sigMap = stripRecoveryIdFromEcdsaSignatures(sigMap);

        final var keyCounts = signatureVerifier.countSimpleKeys(key);
        final long gasRequirement = keyCounts.numEcdsaKeys() * customGasCalculator.getEcrecPrecompiledContractGasCost()
                + keyCounts.numEddsaKeys() * customGasCalculator.getEdSignatureVerificationSystemContractGasCost();

        final var authorized = verifyMessage(
                key, wrap(message), MessageType.RAW, sigMap, ky -> SimpleKeyStatus.ONLY_IF_CRYPTO_SIG_VALID);

        return encodedOutput(SUCCESS, authorized, gasRequirement);
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
        return gasOnly(successResult(output, gasRequirement), SUCCESS, true);
    }

    boolean isValidAccount(final long accountNum) {
        // invalid if accountNum is negative
        return accountNum >= 0;
    }
}
