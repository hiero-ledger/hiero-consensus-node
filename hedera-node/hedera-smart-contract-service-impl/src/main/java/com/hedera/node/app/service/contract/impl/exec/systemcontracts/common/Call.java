/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;

/**
 * Encapsulates a call to the HTS system contract.
 */
public interface Call {
    /**
     * Encapsulates the result of a call to the HAS/HSS/HTS system contract. There are two elements,
     * <ol>
     *     <li>The "full result" of the call, including both its EVM-standard {@link PrecompileContractResult}
     *     and gas requirement (which is often difficult to compute without executing the call); as well as
     *     any {@link ContractCallStreamBuilder} created
     *     as a side effect of executing the system contract.</li>
     *     <li>Any additional cost <i>beyond</i> the gas requirement.</li>
     * </ol>
     *
     * @param fullResult the full result of the call
     * @param nonGasCost any additional cost beyond the gas requirement
     * @param responseCode the response code after the execution
     */
    record PricedResult(FullResult fullResult, long nonGasCost, ResponseCodeEnum responseCode, boolean isViewCall) {
        /**
         * @param result the full result of the call
         * @param responseCode the response code after the execution
         * @param isViewCall whether it is a view call
         * @return the result, the gas requirement, and any non-gas cost
         */
        public static PricedResult gasOnly(FullResult result, ResponseCodeEnum responseCode, boolean isViewCall) {
            return new PricedResult(result, 0L, responseCode, isViewCall);
        }

        /**
         * @param result the full result of the call
         * @param responseCode the response code after the execution
         * @param isViewCall whether it is a view call
         * @param nonGasCost any additional cost beyond the gas requirement
         * @return the result, the gas requirement, and any non-gas cost
         */
        public static PricedResult gasPlus(
                FullResult result, ResponseCodeEnum responseCode, boolean isViewCall, long nonGasCost) {
            return new PricedResult(result, nonGasCost, responseCode, isViewCall);
        }

        /**
         * @param senderId the account that is the sender
         * @param contractId the smart contract instance whose function was called
         * @param functionParameters the parameters passed into the contract call
         * @param remainingGas the gas limit
         * @return the contract function result
         */
        public ContractFunctionResult asResultOfInsufficientGasRemaining(
                @NonNull final AccountID senderId,
                @NonNull final ContractID contractId,
                @NonNull final Bytes functionParameters,
                final long remainingGas) {
            return ContractFunctionResult.newBuilder()
                    .contractID(contractId)
                    .amount(nonGasCost)
                    .contractCallResult(Bytes.EMPTY)
                    .errorMessage(INSUFFICIENT_GAS.protoName())
                    .gasUsed(fullResult().gasRequirement())
                    .gas(remainingGas)
                    .functionParameters(functionParameters)
                    .senderId(senderId)
                    .build();
        }

        /**
         * @param senderId  the account that is the sender
         * @param contractId the smart contract instance whose function was called
         * @param functionParameters the parameters passed into the contract call
         * @param remainingGas the gas limit
         * @return the contract function result
         */
        public ContractFunctionResult asResultOfCall(
                @NonNull final AccountID senderId,
                @NonNull final ContractID contractId,
                @NonNull final Bytes functionParameters,
                final long remainingGas) {
            final var errorMessage = responseCode == SUCCESS ? "" : responseCode.protoName();
            return ContractFunctionResult.newBuilder()
                    .contractID(contractId)
                    .amount(nonGasCost)
                    .contractCallResult(tuweniToPbjBytes(fullResult.output()))
                    .errorMessage(errorMessage)
                    .gasUsed(fullResult().gasRequirement())
                    .gas(remainingGas)
                    .functionParameters(functionParameters)
                    .senderId(senderId)
                    .build();
        }
    }

    /**
     * Executes the call, returning the {@link PrecompileContractResult}, the gas requirement, and any
     * non-gas cost that must be sent as value with the call.
     *
     * @return the result, the gas requirement, and any non-gas cost
     */
    @NonNull
    default PricedResult execute() {
        throw new UnsupportedOperationException("Prefer an explicit execute(MessageFrame) override");
    }

    /**
     * @param frame the message frame
     * @return the result, the gas requirement, and any non-gas cost
     */
    @NonNull
    default PricedResult execute(MessageFrame frame) {
        return execute();
    }

    /**
     * Returns whether this call allows a static frame. Default is false for safety.
     *
     * @return whether this call allows a static frame
     */
    default boolean allowsStaticFrame() {
        return false;
    }

    /**
     * Prepares a {@link SchedulableTransactionBody} for dispatching a scheduled call.
     *
     * @return the native TransactionBody implied by this call
     */
    @NonNull
    default SchedulableTransactionBody asSchedulableDispatchIn() {
        throw new UnsupportedOperationException("Needs scheduleNative() support");
    }

    void setSystemContractMethod(@NonNull final SystemContractMethod systemContractMethod);

    SystemContractMethod getSystemContractMethod();
}
