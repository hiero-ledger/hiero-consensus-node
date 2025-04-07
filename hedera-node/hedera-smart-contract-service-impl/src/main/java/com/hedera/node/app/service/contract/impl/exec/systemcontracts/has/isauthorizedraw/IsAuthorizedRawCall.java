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
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.precompile.ECRECPrecompiledContract;

/** HIP-632 method: `isAuthorizedRaw` */
public class IsAuthorizedRawCall extends AbstractCall {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private static final int EIP_155_V_MIN_LENGTH = 1;
    private static final int EIP_155_V_MAX_LENGTH = 8; // we limit chainId to fit in a `long`
    private static final int EC_SIGNATURE_WITHOUT_V_LENGTH = 64;
    public static final int EC_SIGNATURE_MIN_LENGTH = EC_SIGNATURE_WITHOUT_V_LENGTH + EIP_155_V_MIN_LENGTH;
    public static final int EC_SIGNATURE_MAX_LENGTH = EC_SIGNATURE_WITHOUT_V_LENGTH + EIP_155_V_MAX_LENGTH;
    public static final int ED_SIGNATURE_LENGTH = 64;

    private final Address address;
    private final byte[] messageHash;
    private final byte[] signature;

    private static final GasCalculator noCalculationGasCalculator = new CustomGasCalculator();

    // Besu's ECRECOVER precompile class will be used _directly_ (no need to execute bytecodes):
    private static final ECRECPrecompiledContract ecPrecompile =
            new ECRECPrecompiledContract(noCalculationGasCalculator);

    private final CustomGasCalculator customGasCalculator;

    public enum SignatureType {
        INVALID,
        EC,
        ED
    }

    // From Ethereum yellow paper (for reference only):
    private static BigInteger secp256k1n =
            new BigInteger("115792089237316195423570985008687907852837564279074904382605163141518161494337");

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
        requireNonNull(attempt.signatureVerifier());
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
                    case INVALID -> Math.min(
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

        // Key must be simple (for isAuthorizedRaw)
        if (key.isPresent()) {
            final Key ky = key.get();
            final boolean keyIsSimple = !ky.hasKeyList() && !ky.hasThresholdKey();
            if (!keyIsSimple) return bail.apply(INVALID_TRANSACTION_BODY /* should be: "account key must be simple" */);
        }

        // Key must match signature type
        if (key.isPresent()) {
            if (!switch (signatureType) {
                case EC -> key.get().hasEcdsa384();
                case ED -> key.get().hasEd25519();
                case INVALID -> false;
            }) return bail.apply(INVALID_SIGNATURE_TYPE_MISMATCHING_KEY);
        }

        // Finally: Do the signature validation we came here for!
        final boolean authorized =
                switch (signatureType) {
                    case EC -> validateEcSignature(address, frame);
                    case ED -> validateEdSignature(account, key.get());
                    case INVALID -> false;
                };

        final var result =
                gasOnly(successResult(encodedAuthorizationOutput(authorized), gasRequirement), SUCCESS, true);
        return result;
    }

    /** Validate EVM signature - EC key - via the ECRECOVER precompile */
    public boolean validateEcSignature(@NonNull final Address address, @NonNull final MessageFrame frame) {
        requireNonNull(address, "address");
        requireNonNull(frame, "frame");

        // Call the ECRECOVER precompile directly (meaning, not by executing EVM bytecode, just by
        // using the class provided by Besu).

        final var input = formatEcrecoverInput(messageHash, signature);
        if (input.isEmpty()) return false;
        final var ecrecoverResult = ecPrecompile.computePrecompile(Bytes.wrap(input.get()), frame);

        // ECRECOVER's output always has status SUCCESS but the result Bytes are either empty or the recovered address.
        if (ecrecoverResult.getOutput().isEmpty()) return false;
        final var recoveredAddress =
                ecrecoverResult.getOutput().slice(12).toArray(); // LAST 20 bytes are where the EVM address is

        // ECRECOVER produced an address:  Must match our account's alias address
        final var givenAddress = explicitFromHeadlong(address);
        final boolean addressesMatch = 0 == Arrays.compare(recoveredAddress, givenAddress);

        return addressesMatch;
    }

    /** Validate (native Hedera) ED25519 signature */
    public boolean validateEdSignature(@NonNull final Account account, @NonNull final Key key) {
        requireNonNull(account, "account");
        requireNonNull(key, "key");

        // Use of `com.swirlds.common.crypto.CryptographyHolder` straight from the Platform is deprecated:
        // FUTURE: Get the `Cryptography` engine from the services app via the context (needs to be invented)

        return CRYPTOGRAPHY.verifySync(
                messageHash, signature, key.ed25519OrThrow().toByteArray());
    }

    /** Encode the _output_ of our system contract: it's a boolean */
    @NonNull
    ByteBuffer encodedAuthorizationOutput(final boolean authorized) {
        return IsAuthorizedRawTranslator.IS_AUTHORIZED_RAW.getOutputs().encode(Tuple.singleton(authorized));
    }

    /** Format our message hash and signature for input to the ECRECOVER precompile */
    @NonNull
    public Optional<byte[]> formatEcrecoverInput(@NonNull final byte[] messageHash, @NonNull final byte[] signature) {
        requireNonNull(messageHash, "messageHash");
        requireNonNull(signature, "signature");

        // Signature:
        //   [ 0;  31]  r
        //   [32;  63]  s
        //   [64;  64+]  v (but possibly 0..1 instead of 27..28)

        // From evm.codes, input to ECRECOVER:
        //   [ 0;  31]  hash
        //   [32;  63]  v == recovery identifier ∈ {27,28}
        //   [64;  95]  r == x-value ∈ (0, secp256k1n);
        //   [96; 127]  s ∈ (0; sep256k1n ÷ 2 + 1)

        if (messageHash.length != 32 || signature.length <= 64) return Optional.empty();
        final var ov = reverseV(signature);
        if (ov.isEmpty()) return Optional.empty();

        final byte[] result = new byte[128];
        System.arraycopy(messageHash, 0, result, 0, 32); // hash
        result[63] = ov.get(); //                           v
        System.arraycopy(signature, 0, result, 64, 32); //  r
        System.arraycopy(signature, 32, result, 96, 32); // s

        return Optional.of(result);
    }

    private static final Map<BigInteger, Byte> knownVs = Map.of(
            BigInteger.ZERO,
            (byte) 27,
            BigInteger.ONE,
            (byte) 28,
            BigInteger.valueOf(27),
            (byte) 27,
            BigInteger.valueOf(28),
            (byte) 28);

    private static final BigInteger BIG_35 = BigInteger.valueOf(35);

    /** Make sure v ∈ {27, 28} - but after EIP-155 it might come in with a chain id ... */
    @NonNull
    public Optional<Byte> reverseV(final byte[] signature) {
        requireNonNull(signature);
        if (signature.length <= 64) throw new IllegalArgumentException("Signature is too short");

        final var v = new BigInteger(1, signature, 64, signature.length - 64);

        if (knownVs.containsKey(v)) return Optional.of(knownVs.get(v));
        if (BIG_35.compareTo(v) > 0) return Optional.empty(); // invalid

        // v = {0,1} + (chainid * 2) + 35 thus parity is the opposite of the low order bit
        var parity = !v.testBit(0);
        return Optional.of((byte) (parity ? 28 : 27));
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
            return !isLongZero(enhancement.nativeOperations().entityIdFactory(), address);
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
