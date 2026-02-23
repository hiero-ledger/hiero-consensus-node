// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.encodedRc;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;

/**
 * Minimal implementation support for an {@link Call} that needs an {@link HederaWorldUpdater.Enhancement}
 * and {@link SystemContractGasCalculator}.
 */
public abstract class AbstractCall implements Call {
    protected final SystemContractGasCalculator gasCalculator;
    protected final HederaWorldUpdater.Enhancement enhancement;
    protected final boolean isViewCall;
    private SystemContractMethod systemContractMethod;

    protected AbstractCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final boolean isViewCall) {
        this.gasCalculator = requireNonNull(gasCalculator);
        this.enhancement = requireNonNull(enhancement);
        this.isViewCall = isViewCall;
    }

    public void setSystemContractMethod(@NonNull final SystemContractMethod systemContractMethod) {
        this.systemContractMethod = systemContractMethod;
    }

    public SystemContractMethod getSystemContractMethod() {
        return systemContractMethod;
    }

    protected HederaOperations operations() {
        return enhancement.operations();
    }

    protected HederaNativeOperations nativeOperations() {
        return enhancement.nativeOperations();
    }

    protected ReadableAccountStore readableAccountStore() {
        return nativeOperations().readableAccountStore();
    }

    protected SystemContractOperations systemContractOperations() {
        return enhancement.systemOperations();
    }

    protected PricedResult completionWith(@NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return gasOnly(successResult(encodedRc(standardized(status)), gasRequirement), status, isViewCall);
    }

    protected PricedResult completionWith(
            final long gasRequirement,
            @NonNull final ContractCallStreamBuilder recordBuilder,
            @NonNull final ByteBuffer output) {
        requireNonNull(output);
        requireNonNull(recordBuilder);
        return gasOnly(successResult(output, gasRequirement, recordBuilder), recordBuilder.status(), isViewCall);
    }

    protected PricedResult reversionWith(@NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return gasOnly(revertResult(standardized(status), gasRequirement), status, isViewCall);
    }

    protected PricedResult reversionWith(
            final long gasRequirement, @NonNull final ContractCallStreamBuilder recordBuilder) {
        return gasOnly(revertResult(recordBuilder, gasRequirement), recordBuilder.status(), isViewCall);
    }

    protected PricedResult haltWith(final long gasRequirement, @NonNull final ContractCallStreamBuilder recordBuilder) {
        return gasOnly(haltResult(recordBuilder, gasRequirement), recordBuilder.status(), isViewCall);
    }
}
