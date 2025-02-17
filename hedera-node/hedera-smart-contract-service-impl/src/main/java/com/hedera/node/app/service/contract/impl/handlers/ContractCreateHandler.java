/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.throwIfUnsuccessful;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_CREATE}.
 */
@Singleton
public class ContractCreateHandler extends AbstractContractTransactionHandler {
    private static final AccountID REMOVE_AUTO_RENEW_ACCOUNT_SENTINEL =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(0).build();

    /**
     * Constructs a {@link ContractCreateHandler} with the given {@link Provider} and {@link GasCalculator}.
     *
     * @param provider the provider to be used
     * @param gasCalculator the gas calculator to be used
     */
    @Inject
    public ContractCreateHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractServiceComponent component) {
        super(provider, gasCalculator, component);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // Create the transaction-scoped component
        final var component = getTransactionComponent(context, CONTRACT_CREATE);

        // Run its in-scope transaction and get the outcome
        final var outcome = component.contextTransactionProcessor().call();

        // Assemble the appropriate top-level record for the result
        outcome.addCreateDetailsTo(context.savepointStack().getBaseBuilder(ContractCreateStreamBuilder.class));

        throwIfUnsuccessful(outcome.status());
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        try {
            final var txn = context.body();
            final var op = txn.contractCreateInstanceOrThrow();

            final var intrinsicGas = gasCalculator.transactionIntrinsicGasCost(Bytes.wrap(new byte[0]), true);
            validateTruePreCheck(op.gas() >= intrinsicGas, INSUFFICIENT_GAS);
        } catch (@NonNull final Exception e) {
            bumpExceptionMetrics(CONTRACT_CREATE, e);
            throw e;
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().contractCreateInstanceOrThrow();

        // The transaction cannot set the admin key unless the transaction was signed by that key,
        // unless the key is a "contract ID" key, in which case we don't gather the signature (since
        // there is not one) and it is a perfectly valid arrangement.
        if (op.hasAdminKey()) {
            final var adminKey = op.adminKeyOrThrow();
            if (!adminKey.hasContractID()) {
                context.requireKey(adminKey);
            }
        }

        // If an account is to be used for auto-renewal, then the account must exist and the transaction
        // must be signed with that account's key.
        if (op.hasAutoRenewAccountId()) {
            final var autoRenewAccountID = op.autoRenewAccountIdOrThrow();
            if (!autoRenewAccountID.equals(REMOVE_AUTO_RENEW_ACCOUNT_SENTINEL)) {
                context.requireKeyOrThrow(autoRenewAccountID, INVALID_AUTORENEW_ACCOUNT);
            }
        }
    }

    @Override
    protected /*abstract*/ @NonNull FeeData getFeeMatrices(
            @NonNull final SmartContractFeeBuilder usageEstimator,
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txBody,
            @NonNull final SigValueObj sigValObj) {
        return usageEstimator.getContractCreateTxFeeMatrices(txBody, sigValObj);
    }
}
