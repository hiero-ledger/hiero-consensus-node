// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QueryHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QueryHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QuerySystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.QueryContextHevmBlocks;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.HederaConfig;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hyperledger.besu.evm.code.CodeFactory;

@Module
public interface QueryModule {
    @Provides
    @QueryScope
    static HederaConfig provideHederaConfig(@NonNull final QueryContext context) {
        return requireNonNull(context).configuration().getConfigData(HederaConfig.class);
    }

    @Provides
    @QueryScope
    static TinybarValues provideTinybarValues(@NonNull final ExchangeRate exchangeRate) {
        return TinybarValues.forQueryWith(exchangeRate);
    }

    @Provides
    @QueryScope
    static SystemContractGasCalculator provideSystemContractGasCalculator(
            @NonNull final CanonicalDispatchPrices canonicalDispatchPrices,
            @NonNull final TinybarValues tinybarValues) {
        return new SystemContractGasCalculator(tinybarValues, canonicalDispatchPrices, (body, payerId) -> {
            throw new IllegalStateException("Queries should fail before dispatching a child transaction");
        });
    }

    @Provides
    @QueryScope
    static ExchangeRate provideExchangeRate(@NonNull final Instant now, @NonNull final QueryContext context) {
        return context.exchangeRateInfo().activeRate(now);
    }

    @Provides
    @QueryScope
    static HederaWorldUpdater.Enhancement provideEnhancement(
            @NonNull final HederaOperations operations,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final SystemContractOperations systemContractOperations) {
        return new HederaWorldUpdater.Enhancement(operations, nativeOperations, systemContractOperations);
    }

    @Provides
    @QueryScope
    static ProxyWorldUpdater provideProxyWorldUpdater(
            @NonNull final HederaWorldUpdater.Enhancement enhancement, @NonNull final EvmFrameStateFactory factory) {
        return new ProxyWorldUpdater(enhancement, requireNonNull(factory), null);
    }

    @Provides
    @QueryScope
    static ActionSidecarContentTracer provideActionSidecarContentTracer() {
        return new EvmActionTracer(new ActionStack());
    }

    @Provides
    @QueryScope
    static HederaEvmContext provideHederaEvmContext(
            @NonNull final HederaOperations hederaOperations,
            @NonNull final HederaEvmBlocks hederaEvmBlocks,
            @NonNull final TinybarValues tinybarValues,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator) {
        // Use null for the top-level record builder and reference to pending creation record builder,
        // as neither is usable by any operation permitted in a static context
        return new HederaEvmContext(
                hederaOperations.gasPriceInTinybars(),
                true,
                hederaEvmBlocks,
                tinybarValues,
                systemContractGasCalculator,
                null,
                null);
    }

    @Binds
    @QueryScope
    HederaOperations bindHederaOperations(QueryHederaOperations queryExtWorldScope);

    @Binds
    @QueryScope
    HederaNativeOperations bindHederaNativeOperations(QueryHederaNativeOperations queryExtFrameScope);

    @Binds
    @QueryScope
    HederaEvmBlocks bindHederaEvmBlocks(QueryContextHevmBlocks queryContextHevmBlocks);

    @Binds
    @QueryScope
    SystemContractOperations bindSystemContractOperations(QuerySystemContractOperations querySystemContractOperations);

    @Provides
    @QueryScope
    static EvmFrameStateFactory provideEvmFrameStateFactory(
            @NonNull final CodeFactory codeFactory,
            @NonNull final HederaOperations operations,
            @NonNull final HederaNativeOperations nativeOperations) {
        return EvmFrameStates.DEFAULT.from(operations, nativeOperations, codeFactory);
    }
}
