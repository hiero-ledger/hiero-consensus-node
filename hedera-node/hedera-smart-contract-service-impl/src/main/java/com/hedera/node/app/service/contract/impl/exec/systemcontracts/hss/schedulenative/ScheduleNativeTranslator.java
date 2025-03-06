// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateCommons.createMethodsSet;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommons.updateMethodsSet;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.datatypes.Address.fromHexString;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;

@Singleton
public class ScheduleNativeTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod SCHEDULED_NATIVE_CALL = SystemContractMethod.declare(
                    "scheduleNative(address,bytes,address)", ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);
    private static final int SCHEDULE_CONTRACT_ADDRESS = 0;
    private static final int SCHEDULE_CALL_DATA = 1;
    private static final int SCHEDULE_PAYER = 2;

    private final HtsCallFactory htsCallFactory;

    @Inject
    public ScheduleNativeTranslator(
            @NonNull final HtsCallFactory htsCallFactory,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);
        registerMethods(SCHEDULED_NATIVE_CALL);

        requireNonNull(htsCallFactory);
        this.htsCallFactory = htsCallFactory;
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        final var scheduleNativeEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractScheduleNativeEnabled();

        if (!attempt.isSelectorIfConfigEnabled(scheduleNativeEnabled, SCHEDULED_NATIVE_CALL)) return Optional.empty();
        if (!innerCallValidation(attempt)) return Optional.empty();
        return Optional.of(SCHEDULED_NATIVE_CALL);
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        final var call = SCHEDULED_NATIVE_CALL.decodeCall(attempt.inputBytes());
        final var innerCallData = Bytes.wrap((byte[]) call.get(SCHEDULE_CALL_DATA));
        final var payerID = attempt.addressIdConverter().convert(call.get(SCHEDULE_PAYER));
        return new ScheduleNativeCall(
                attempt.systemContractID(),
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.defaultVerificationStrategy(),
                payerID,
                ScheduleNativeTranslator::gasRequirement,
                attempt.keySetFor(),
                innerCallData,
                htsCallFactory,
                false);
    }

    /**
     * Calculates the gas requirement for a {@code SCHEDULE_CREATE} call.
     *
     * @param body the transaction body
     * @param systemContractGasCalculator the gas calculator
     * @param enhancement the enhancement
     * @param payerId the payer account ID
     * @return the gas requirement
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.SCHEDULE_CREATE, payerId);
    }

    // This method is used to validate the inner call of the scheduled create call
    // We validate that the sender contract is HTS, that the sender address is a valid account
    // And that the inner call is one of create or update token functions
    private boolean innerCallValidation(@NonNull final HssCallAttempt attempt) {
        final var call = SCHEDULED_NATIVE_CALL.decodeCall(attempt.inputBytes());
        final var contractAddress = (Address) call.get(SCHEDULE_CONTRACT_ADDRESS);
        final var payerAddress = (Address) call.get(SCHEDULE_PAYER);
        final var payerID = attempt.addressIdConverter().convert(payerAddress);
        validateTrue(payerID != AccountID.DEFAULT, ResponseCodeEnum.INVALID_ACCOUNT_ID);
        final var besuContractAddress = fromHexString(contractAddress.toString());
        validateTrue(
                besuContractAddress.equals(fromHexString(HTS_167_EVM_ADDRESS)), ResponseCodeEnum.INVALID_CONTRACT_ID);
        final var innerCallSelector =
                Bytes.wrap((byte[]) call.get(SCHEDULE_CALL_DATA)).slice(0, 4).toArray();
        final var canBeCreateToken =
                createMethodsSet.stream().anyMatch(s -> Arrays.equals(s.selector(), innerCallSelector));
        final var canBeUpdateToken =
                updateMethodsSet.stream().anyMatch(s -> Arrays.equals(s.selector(), innerCallSelector));

        return canBeCreateToken || canBeUpdateToken;
    }
}
