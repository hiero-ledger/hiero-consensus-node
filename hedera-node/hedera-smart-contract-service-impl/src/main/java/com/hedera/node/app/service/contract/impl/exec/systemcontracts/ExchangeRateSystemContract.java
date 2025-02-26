// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.hapi.utils.ValidationUtils.validateTrue;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.BigIntegerType;
import com.esaulpaugh.headlong.abi.TypeFactory;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

public class ExchangeRateSystemContract extends AbstractFullContract implements HederaSystemContract {
    private static final String PRECOMPILE_NAME = "ExchangeRate";
    private static final BigIntegerType WORD_DECODER = TypeFactory.create("uint256");

    // tinycentsToTinybars(uint256)
    public static final int TO_TINYBARS_SELECTOR = 0x2e3cff6a;
    // tinybarsToTinycents(uint256)
    public static final int TO_TINYCENTS_SELECTOR = 0x43a88229;

    public static final String EXCHANGE_RATE_SYSTEM_CONTRACT_ADDRESS = "0x168";

    // The system contract ID always uses shard 0 and realm 0 so we cannot use ConversionUtils methods for this
    public static final ContractID EXCHANGE_RATE_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(EXCHANGE_RATE_SYSTEM_CONTRACT_ADDRESS)))
            .build();

    private long gasRequirement;

    @Inject
    public ExchangeRateSystemContract(@NonNull final GasCalculator gasCalculator) {
        super(PRECOMPILE_NAME, gasCalculator);
    }

    @Override
    @NonNull
    public FullResult computeFully(
            @NonNull ContractID contractID, @NonNull Bytes input, @NonNull MessageFrame messageFrame) {
        requireNonNull(input);
        requireNonNull(messageFrame);
        try {
            validateTrue(input.size() >= 4, INVALID_TRANSACTION_BODY);
            gasRequirement = contractsConfigOf(messageFrame).precompileExchangeRateGasCost();
            final var selector = input.getInt(0);
            final var amount = biValueFrom(input);
            final var activeRate = proxyUpdaterFor(messageFrame).currentExchangeRate();
            final var result =
                    switch (selector) {
                        case TO_TINYBARS_SELECTOR -> padded(
                                ConversionUtils.fromAToB(amount, activeRate.hbarEquiv(), activeRate.centEquiv()));
                        case TO_TINYCENTS_SELECTOR -> padded(
                                ConversionUtils.fromAToB(amount, activeRate.centEquiv(), activeRate.hbarEquiv()));
                        default -> null;
                    };
            requireNonNull(result);
            return new FullResult(PrecompileContractResult.success(result), gasRequirement, null);
        } catch (Exception e) {
            return new FullResult(
                    PrecompileContractResult.halt(Bytes.EMPTY, Optional.of(ExceptionalHaltReason.INVALID_OPERATION)),
                    gasRequirement,
                    null);
        }
    }

    @NonNull
    private BigInteger biValueFrom(@NonNull final Bytes input) {
        return WORD_DECODER.decode(input.slice(4).toArrayUnsafe());
    }

    @NonNull
    private Bytes padded(@NonNull final BigInteger result) {
        return Bytes32.leftPad(Bytes.wrap(result.toByteArray()));
    }
}
