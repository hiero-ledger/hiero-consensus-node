// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.EVM_VERSIONS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.contract.impl.annotations.ChildTransactionResourcePrices;
import com.hedera.node.app.service.contract.impl.annotations.InitialState;
import com.hedera.node.app.service.contract.impl.annotations.TopLevelResourcePrices;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleSystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.hevm.HandleContextHevmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.EthTxSigsCache;
import com.hedera.node.app.service.contract.impl.infra.EthereumCallDataHydration;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import com.hedera.node.app.service.contract.impl.state.ScopedEvmFrameStateFactory;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.ComputeDispatchFeesAsTopLevel;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Map;

@Module(includes = {TransactionConfigModule.class, TransactionInitialStateModule.class})
public interface TransactionModule {
    @Provides
    @TransactionScope
    static TransactionProcessor provideTransactionProcessor(
            @NonNull final ContractsConfig contractsConfig,
            @NonNull final Map<HederaEvmVersion, TransactionProcessor> processors) {
        return processors.get(EVM_VERSIONS.get(contractsConfig.evmVersion()));
    }

    @Provides
    @TransactionScope
    static FeatureFlags provideFeatureFlags(@NonNull final TransactionProcessor processor) {
        return processor.featureFlags();
    }

    @Provides
    @TransactionScope
    static TinybarValues provideTinybarValues(
            @TopLevelResourcePrices @NonNull final FunctionalityResourcePrices topLevelResourcePrices,
            @ChildTransactionResourcePrices @NonNull final FunctionalityResourcePrices childTransactionResourcePrices,
            @NonNull final ExchangeRate exchangeRate) {
        return TinybarValues.forTransactionWith(exchangeRate, topLevelResourcePrices, childTransactionResourcePrices);
    }

    @Provides
    @TransactionScope
    static SystemContractGasCalculator provideSystemContractGasCalculator(
            @NonNull final HandleContext context,
            @NonNull final CanonicalDispatchPrices canonicalDispatchPrices,
            @NonNull final TinybarValues tinybarValues) {
        return new SystemContractGasCalculator(
                tinybarValues, canonicalDispatchPrices, (body, payerId) -> context.dispatchComputeFees(
                                body, payerId, ComputeDispatchFeesAsTopLevel.NO)
                        .totalFee());
    }

    @Provides
    @TransactionScope
    static Instant provideConsensusTime(@NonNull final HandleContext context) {
        return requireNonNull(context).consensusNow();
    }

    @Provides
    @TransactionScope
    @TopLevelResourcePrices
    static FunctionalityResourcePrices provideTopLevelResourcePrices(
            @NonNull final HederaFunctionality functionality, @NonNull final HandleContext context) {
        return context.resourcePriceCalculator().resourcePricesFor(functionality, SubType.DEFAULT);
    }

    @Provides
    @TransactionScope
    @ChildTransactionResourcePrices
    static FunctionalityResourcePrices provideChildTransactionResourcePrices(@NonNull final HandleContext context) {
        return context.resourcePriceCalculator().resourcePricesFor(HederaFunctionality.CONTRACT_CALL, SubType.DEFAULT);
    }

    @Provides
    @TransactionScope
    static ExchangeRate provideExchangeRate(@NonNull final Instant now, @NonNull final HandleContext context) {
        return context.exchangeRateInfo().activeRate(now);
    }

    @Provides
    @Nullable
    @TransactionScope
    static HydratedEthTxData maybeProvideHydratedEthTxData(
            @NonNull final HandleContext context,
            @NonNull final EthereumCallDataHydration hydration,
            @NonNull final HederaConfig hederaConfig,
            @NonNull @InitialState final ReadableFileStore fileStore) {
        final var body = context.body();
        return body.hasEthereumTransaction()
                ? hydration.tryToHydrate(body.ethereumTransactionOrThrow(), fileStore, hederaConfig.firstUserEntity())
                : null;
    }

    /**
     * If the top-level transaction is an {@code EthereumTransaction}, provides an ECDSA {@link Key} with
     * the public key of the sender address; otherwise returns {@code null}.
     *
     * @param ethTxSigsCache the cache of Ethereum transaction signatures
     * @param hydratedEthTxData the hydrated Ethereum transaction data, if this is an {@code EthereumTransaction}
     * @return the ECDSA {@link Key} with the public key of the sender address, or {@code null}
     */
    @Provides
    @Nullable
    @TransactionScope
    static Key provideSenderEcdsaKey(
            @NonNull final EthTxSigsCache ethTxSigsCache, @Nullable final HydratedEthTxData hydratedEthTxData) {
        if (hydratedEthTxData != null && hydratedEthTxData.isAvailable()) {
            final var ethTxSigs = ethTxSigsCache.computeIfAbsent(hydratedEthTxData.ethTxDataOrThrow());
            return Key.newBuilder()
                    .ecdsaSecp256k1(Bytes.wrap(ethTxSigs.publicKey()))
                    .build();
        } else {
            return null;
        }
    }

    @Provides
    @TransactionScope
    static EvmActionTracer provideEvmActionTracer() {
        return new EvmActionTracer(new ActionStack());
    }

    @Provides
    @TransactionScope
    static HederaEvmContext provideHederaEvmContext(
            @NonNull final HandleContext context,
            @NonNull final TinybarValues tinybarValues,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaOperations hederaOperations,
            @NonNull final HederaEvmBlocks hederaEvmBlocks,
            @NonNull final PendingCreationMetadataRef pendingCreationMetadataRef) {
        return new HederaEvmContext(
                hederaOperations.gasPriceInTinybars(),
                false,
                hederaEvmBlocks,
                tinybarValues,
                systemContractGasCalculator,
                context.savepointStack().getBaseBuilder(ContractOperationStreamBuilder.class),
                pendingCreationMetadataRef);
    }

    @Provides
    @TransactionScope
    static AttributeValidator provideAttributeValidator(@NonNull final HandleContext context) {
        return context.attributeValidator();
    }

    @Provides
    @TransactionScope
    static ExpiryValidator provideExpiryValidator(@NonNull final HandleContext context) {
        return context.expiryValidator();
    }

    @Provides
    @TransactionScope
    static NetworkInfo provideNetworkInfo(@NonNull final HandleContext context) {
        return context.networkInfo();
    }

    @Provides
    @TransactionScope
    static HederaWorldUpdater.Enhancement provideEnhancement(
            @NonNull final HederaOperations operations,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final SystemContractOperations systemContractOperations) {
        requireNonNull(operations);
        requireNonNull(nativeOperations);
        requireNonNull(systemContractOperations);
        return new HederaWorldUpdater.Enhancement(operations.begin(), nativeOperations, systemContractOperations);
    }

    @Binds
    @TransactionScope
    EvmFrameStateFactory bindEvmFrameStateFactory(ScopedEvmFrameStateFactory factory);

    @Binds
    @TransactionScope
    HederaOperations bindHederaOperations(HandleHederaOperations handleExtWorldScope);

    @Binds
    @TransactionScope
    HederaNativeOperations bindHederaNativeOperations(HandleHederaNativeOperations handleExtFrameScope);

    @Binds
    @TransactionScope
    SystemContractOperations bindSystemContractOperations(
            HandleSystemContractOperations handleSystemContractOperations);

    @Binds
    @TransactionScope
    HederaEvmBlocks bindHederaEvmBlocks(HandleContextHevmBlocks handleContextHevmBlocks);
}
