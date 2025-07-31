// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code scheduleCall*} calls to the HSS system contract. For details
 * {@see <a href=https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1215.md>HIP-1215</a>}
 */
@Singleton
public class ScheduleCallTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod SCHEDULED_CALL = SystemContractMethod.declare(
                    "scheduleCall(address,uint256,uint256,uint64,bytes)", ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);
    public static final SystemContractMethod SCHEDULED_CALL_WITH_SENDER = SystemContractMethod.declare(
                    "scheduleCallWithSender(address,address,uint256,uint256,uint64,bytes)",
                    ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);
    public static final SystemContractMethod EXECUTE_CALL_ON_SENDER_SIGNATURE = SystemContractMethod.declare(
                    "executeCallOnSenderSignature(address,address,uint256,uint256,uint64,bytes)",
                    ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);

    @Inject
    public ScheduleCallTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HSS, systemContractMethodRegistry, contractMetrics);
        registerMethods(SCHEDULED_CALL);
        registerMethods(SCHEDULED_CALL_WITH_SENDER);
        registerMethods(EXECUTE_CALL_ON_SENDER_SIGNATURE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        if (attempt.configuration().getConfigData(ContractsConfig.class).systemContractScheduleCallEnabled()) {
            return attempt.isMethod(SCHEDULED_CALL, SCHEDULED_CALL_WITH_SENDER, EXECUTE_CALL_ON_SENDER_SIGNATURE);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        // read parameters
        final Tuple call;
        final Address to;
        final AccountID sender;
        final boolean waitForExpiry;
        int paramIndex = 0;
        if (attempt.isSelector(SCHEDULED_CALL)) {
            call = SCHEDULED_CALL.decodeCall(attempt.inputBytes());
            to = call.get(paramIndex++);
            sender = attempt.addressIdConverter().convertSender(attempt.senderAddress());
            waitForExpiry = true;
        } else if (attempt.isSelector(SCHEDULED_CALL_WITH_SENDER)) {
            call = SCHEDULED_CALL_WITH_SENDER.decodeCall(attempt.inputBytes());
            to = call.get(paramIndex++);
            sender = attempt.addressIdConverter().convert(call.get(paramIndex++));
            waitForExpiry = true;
        } else if (attempt.isSelector(EXECUTE_CALL_ON_SENDER_SIGNATURE)) {
            call = EXECUTE_CALL_ON_SENDER_SIGNATURE.decodeCall(attempt.inputBytes());
            to = call.get(paramIndex++);
            sender = attempt.addressIdConverter().convert(call.get(paramIndex++));
            waitForExpiry = false;
        } else {
            throw new IllegalStateException("Unexpected function selector");
        }
        final BigInteger expiry = call.get(paramIndex++);
        final BigInteger gasLimit = call.get(paramIndex++);
        final BigInteger value = call.get(paramIndex++);
        final var callData = Bytes.wrap((byte[]) call.get(paramIndex));

        // convert parameters
        final var contractId = ConversionUtils.asContractId(
                attempt.enhancement().nativeOperations().entityIdFactory(), ConversionUtils.fromHeadlongAddress(to));

        Set<Key> keys = attempt.keySetFor();
        // create TransactionBody
        TransactionBody body = TransactionBody.newBuilder()
                .transactionID(attempt.enhancement().nativeOperations().getTransactionID())
                // create ScheduleCreateTransactionBody
                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                .contractCall(ContractCallTransactionBody.newBuilder()
                                        .contractID(contractId)
                                        .gas(gasLimit.longValueExact())
                                        .amount(value.longValueExact())
                                        .functionParameters(callData)))
                        // we need to set adminKey for make schedule not immutable and to be able to delete schedule
                        .adminKey(keys.stream().findFirst().orElse(null))
                        .expirationTime(Timestamp.newBuilder().seconds(expiry.longValueExact()))
                        .payerAccountID(sender)
                        .waitForExpiry(waitForExpiry))
                .build();

        return new DispatchForResponseCodeHssCall(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                sender,
                body,
                attempt.defaultVerificationStrategy(),
                ScheduleCallTranslator::gasRequirement,
                keys,
                DispatchForResponseCodeHssCall::scheduleCreateResultEncode);
    }

    /**
     * Calculates the gas requirement for a {@code SCHEDULE_CREATE} call.
     *
     * @param body                        the transaction body
     * @param systemContractGasCalculator the gas calculator
     * @param enhancement                 the enhancement
     * @param payerId                     the payer account ID
     * @return the gas requirement
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.SCHEDULE_CREATE_CONTRACT_CALL, payerId);
    }
}
