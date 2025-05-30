// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasPlus;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.RC_AND_ADDRESS_ENCODER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.stackIncludesActiveAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy.UseTopLevelSigs;
import com.hedera.node.app.service.contract.impl.exec.scope.EitherOrVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.SpecificCryptoVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateTranslator;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the token redirect {@code createToken()} call of the HTS system contract.
 */
public class ClassicCreatesCall extends AbstractCall {
    /**
     * The mono-service stipulated gas cost for a token creation (remaining fee is collected by sent value)
     */
    private static final long FIXED_GAS_COST = 100_000L;

    @Nullable
    final TransactionBody syntheticCreate;

    private final VerificationStrategy verificationStrategy;
    private final AccountID spenderId;

    /**
     * @param systemContractGasCalculator the gas calculator for the system contract
     * @param enhancement the enhancement to be used
     * @param syntheticCreate the body of synthetic create operation
     * @param verificationStrategy the verification strategy to use
     * @param spender the spender account id
     */
    public ClassicCreatesCall(
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final TransactionBody syntheticCreate,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID spender) {
        super(systemContractGasCalculator, enhancement, false);
        this.verificationStrategy = requireNonNull(verificationStrategy);
        this.spenderId = spender;
        this.syntheticCreate = syntheticCreate;
    }

    private record LegacyActivation(long contractNum, Bytes pbjAddress, Address besuAddress) {}

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        if (syntheticCreate == null) {
            return gasOnly(
                    haltResult(
                            ERROR_DECODING_PRECOMPILE_INPUT,
                            contractsConfigOf(frame).precompileHtsDefaultGasCost()),
                    INVALID_TRANSACTION_BODY,
                    false);
        }
        final var timestampSeconds = frame.getBlockValues().getTimestamp();
        final var timestamp = Timestamp.newBuilder().seconds(timestampSeconds).build();
        final var syntheticCreateWithId = syntheticCreate
                .copyBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.DEFAULT)
                        .transactionValidStart(timestamp)
                        .build())
                .build();
        final var baseCost = gasCalculator.feeCalculatorPriceInTinyBars(syntheticCreateWithId, spenderId);
        // The non-gas cost is a 20% surcharge on the HAPI TokenCreate price, minus the fee taken as gas
        long nonGasCost = baseCost + (baseCost / 5) - gasCalculator.gasCostInTinybars(FIXED_GAS_COST);
        if (frame.getValue().lessThan(Wei.of(nonGasCost))) {
            return completionWith(
                    FIXED_GAS_COST,
                    systemContractOperations()
                            .externalizePreemptedDispatch(syntheticCreate, INSUFFICIENT_TX_FEE, TOKEN_CREATE),
                    RC_AND_ADDRESS_ENCODER.encode(Tuple.of((long) INSUFFICIENT_TX_FEE.protoOrdinal(), ZERO_ADDRESS)));
        } else {
            operations().collectHtsFee(spenderId, nonGasCost);
        }

        final var op = syntheticCreate.tokenCreationOrThrow();
        final var validity = validityOfSynth(op);
        if (validity != OK) {
            return gasOnly(revertResult(validity, FIXED_GAS_COST), validity, true);
        }

        // Choose a dispatch verification strategy based on whether the legacy activation address is active
        final var dispatchVerificationStrategy = verificationStrategyFor(frame, op);
        final var recordBuilder = systemContractOperations()
                .dispatch(syntheticCreate, dispatchVerificationStrategy, spenderId, ContractCallStreamBuilder.class);
        recordBuilder.status(standardized(recordBuilder.status()));

        final var status = recordBuilder.status();
        if (status != SUCCESS) {
            return gasPlus(revertResult(recordBuilder, FIXED_GAS_COST), status, false, nonGasCost);
        } else {
            ByteBuffer encodedOutput;
            final var customFees = op.customFees();
            if (op.tokenType() == FUNGIBLE_COMMON) {
                if (customFees.isEmpty()) {
                    encodedOutput = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V1
                            .getOutputs()
                            .encode(Tuple.of(
                                    (long) SUCCESS.protoOrdinal(), headlongAddressOf(recordBuilder.tokenID())));
                } else {
                    encodedOutput = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1
                            .getOutputs()
                            .encode(Tuple.of(
                                    (long) SUCCESS.protoOrdinal(), headlongAddressOf(recordBuilder.tokenID())));
                }
            } else {
                if (customFees.isEmpty()) {
                    encodedOutput = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V1
                            .getOutputs()
                            .encode(Tuple.of(
                                    (long) SUCCESS.protoOrdinal(), headlongAddressOf(recordBuilder.tokenID())));
                } else {
                    encodedOutput = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1
                            .getOutputs()
                            .encode(Tuple.of(
                                    (long) SUCCESS.protoOrdinal(), headlongAddressOf(recordBuilder.tokenID())));
                }
            }
            return gasPlus(successResult(encodedOutput, FIXED_GAS_COST, recordBuilder), status, false, nonGasCost);
        }
    }

    @NonNull
    @Override
    public SchedulableTransactionBody asSchedulableDispatchIn() {
        if (syntheticCreate == null) {
            return super.asSchedulableDispatchIn();
        }
        return SchedulableTransactionBody.newBuilder()
                .tokenCreation(syntheticCreate.tokenCreation())
                .build();
    }

    private ResponseCodeEnum validityOfSynth(@NonNull final TokenCreateTransactionBody op) {
        if (op.symbol().isEmpty()) {
            return MISSING_TOKEN_SYMBOL;
        }
        final var treasuryAccount = nativeOperations().getAccount(op.treasuryOrThrow());
        if (treasuryAccount == null) {
            return INVALID_ACCOUNT_ID;
        }
        return OK;
    }

    private VerificationStrategy verificationStrategyFor(
            @NonNull final MessageFrame frame, @NonNull final TokenCreateTransactionBody op) {
        final var legacyActivation = legacyActivationIn(frame);

        // If there is a crypto admin key, we need an either/or strategy to let it
        // be activated by a top-level signature with that key
        final var baseVerificationStrategy = hasCryptoAdminKey(op)
                ? new EitherOrVerificationStrategy(
                        verificationStrategy, new SpecificCryptoVerificationStrategy(op.adminKeyOrThrow()))
                : verificationStrategy;
        // And our final dispatch verification strategy must vary depending on if
        // a legacy activation address is active (somewhere on the stack)
        return stackIncludesActiveAddress(frame, legacyActivation.besuAddress())
                ? new EitherOrVerificationStrategy(
                        baseVerificationStrategy,
                        new ActiveContractVerificationStrategy(
                                entityIdFactory(frame).newContractId(legacyActivation.contractNum()),
                                legacyActivation.pbjAddress(),
                                false,
                                UseTopLevelSigs.NO))
                : baseVerificationStrategy;
    }

    private boolean hasCryptoAdminKey(@NonNull final TokenCreateTransactionBody op) {
        return op.hasAdminKey()
                && (op.adminKeyOrThrow().hasEd25519() || op.adminKeyOrThrow().hasEcdsaSecp256k1());
    }

    private LegacyActivation legacyActivationIn(@NonNull final MessageFrame frame) {
        final var literal = configOf(frame).getConfigData(ContractsConfig.class).keysLegacyActivations();
        final var contractNum = Long.parseLong(literal.substring(literal.indexOf("[") + 1, literal.indexOf("]")));
        final var pbjAddress =
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(asEvmAddress(entityIdFactory(frame), contractNum));
        return new LegacyActivation(contractNum, pbjAddress, pbjToBesuAddress(pbjAddress));
    }
}
