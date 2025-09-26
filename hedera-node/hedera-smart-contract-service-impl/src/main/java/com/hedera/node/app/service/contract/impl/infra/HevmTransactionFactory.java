// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_FILE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_NEGATIVE_VALUE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ERROR_DECODING_BYTESTRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SERIALIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WRONG_CHAIN_ID;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static com.hedera.node.app.service.contract.impl.handlers.ContractUpdateHandler.UNLIMITED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction.NOT_APPLICABLE;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPriorityId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.removeIfAnyLeading0x;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthEthTxCreation;
import static com.hedera.node.app.service.contract.impl.utils.ValidationUtils.getMaxGasLimit;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;
import static org.apache.tuweni.bytes.Bytes.EMPTY;

import com.esaulpaugh.headlong.util.Integers;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.contract.impl.annotations.InitialState;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

@TransactionScope
public class HevmTransactionFactory {

    private final NetworkInfo networkInfo;
    private final LedgerConfig ledgerConfig;
    private final HederaConfig hederaConfig;
    private final FeatureFlags featureFlags;
    private final GasCalculator gasCalculator;
    private final ContractsConfig contractsConfig;
    private final EntitiesConfig entitiesConfig;
    private final ReadableFileStore fileStore;
    private final TokenServiceApi tokenServiceApi;
    private final ReadableAccountStore accountStore;
    private final ExpiryValidator expiryValidator;
    private final AttributeValidator attributeValidator;
    private final HydratedEthTxData hydratedEthTxData;
    private final EthTxSigsCache ethereumSignatures;
    private final HederaEvmContext hederaEvmContext;
    private final EntityIdFactory entityIdFactory;

    @Inject
    public HevmTransactionFactory(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final HederaConfig hederaConfig,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractsConfig contractsConfig,
            @NonNull final EntitiesConfig entitiesConfig,
            @Nullable final HydratedEthTxData hydratedEthTxData,
            @NonNull @InitialState final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull @InitialState final ReadableFileStore fileStore,
            @NonNull final AttributeValidator attributeValidator,
            @NonNull @InitialState final TokenServiceApi tokenServiceApi,
            @NonNull final EthTxSigsCache ethereumSignatures,
            @NonNull final HederaEvmContext hederaEvmContext,
            @NonNull final EntityIdFactory entityIdFactory) {
        this.featureFlags = featureFlags;
        this.hydratedEthTxData = hydratedEthTxData;
        this.gasCalculator = requireNonNull(gasCalculator);
        this.fileStore = requireNonNull(fileStore);
        this.networkInfo = requireNonNull(networkInfo);
        this.accountStore = requireNonNull(accountStore);
        this.ledgerConfig = requireNonNull(ledgerConfig);
        this.hederaConfig = requireNonNull(hederaConfig);
        this.contractsConfig = requireNonNull(contractsConfig);
        this.entitiesConfig = requireNonNull(entitiesConfig);
        this.tokenServiceApi = requireNonNull(tokenServiceApi);
        this.expiryValidator = requireNonNull(expiryValidator);
        this.attributeValidator = requireNonNull(attributeValidator);
        this.ethereumSignatures = requireNonNull(ethereumSignatures);
        this.hederaEvmContext = requireNonNull(hederaEvmContext);
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    /**
     * Given a {@link TransactionBody}, creates the implied {@link HederaEvmTransaction}.
     *
     * @param body the {@link TransactionBody} to convert
     * @param payerId transaction payer id
     * @return the implied {@link HederaEvmTransaction}
     * @throws IllegalArgumentException if the {@link TransactionBody} is not a contract operation
     */
    public HederaEvmTransaction fromHapiTransaction(@NonNull final TransactionBody body, @NonNull AccountID payerId) {
        return switch (body.data().kind()) {
            case CONTRACT_CREATE_INSTANCE -> fromHapiCreate(payerId, body.contractCreateInstanceOrThrow());
            case CONTRACT_CALL -> fromHapiCall(payerId, body.contractCallOrThrow());
            case ETHEREUM_TRANSACTION -> fromHapiEthereum(payerId, body.ethereumTransactionOrThrow());
            default -> throw new IllegalArgumentException("Not a contract operation");
        };
    }

    private HederaEvmTransaction fromHapiCreate(
            @NonNull final AccountID payer, @NonNull final ContractCreateTransactionBody body) {
        assertValidCreation(body);
        final var payload = initcodeFor(body);
        return new HederaEvmTransaction(
                payer,
                null,
                null,
                NOT_APPLICABLE,
                payload,
                null,
                body.initialBalance(),
                body.gas(),
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                body,
                null);
    }

    private HederaEvmTransaction fromHapiCall(
            @NonNull final AccountID payer, @NonNull final ContractCallTransactionBody body) {
        assertValidCall(body);
        return new HederaEvmTransaction(
                payer,
                null,
                asPriorityId(body.contractIDOrThrow(), accountStore),
                NOT_APPLICABLE,
                body.functionParameters(),
                null,
                body.amount(),
                body.gas(),
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                null,
                null);
    }

    private HederaEvmTransaction fromHapiEthereum(
            @NonNull final AccountID payerId, @NonNull final EthereumTransactionBody body) {
        final var ethTxData = assertValidEthTx(body);
        final var senderId = asAliasedSender(ethTxData);
        return ethTxData.hasToAddress()
                ? fromEthTxCall(payerId, senderId, ethTxData, body.maxGasAllowance())
                : fromEthTxCreate(payerId, senderId, ethTxData, body.maxGasAllowance());
    }

    private @NonNull HederaEvmTransaction fromEthTxCall(
            @NonNull final AccountID relayerId,
            @NonNull final AccountID senderId,
            @NonNull final EthTxData ethTxData,
            final long maxGasAllowance) {
        validateTrue(ethTxData.getAmount() >= 0, CONTRACT_NEGATIVE_VALUE);
        return new HederaEvmTransaction(
                senderId,
                relayerId,
                asPriorityId(entityIdFactory.newContractIdWithEvmAddress(Bytes.wrap(ethTxData.to())), accountStore),
                ethTxData.nonce(),
                ethTxData.hasCallData() ? Bytes.wrap(ethTxData.callData()) : Bytes.EMPTY,
                Bytes.wrap(ethTxData.chainId()),
                ethTxData.effectiveTinybarValue(),
                ethTxData.gasLimit(),
                ethTxData.effectiveOfferedGasPriceInTinybars(hederaEvmContext.gasPrice()),
                maxGasAllowance,
                null,
                null);
    }

    private @NonNull HederaEvmTransaction fromEthTxCreate(
            @NonNull final AccountID relayerId,
            @NonNull final AccountID senderId,
            @NonNull final EthTxData ethTxData,
            final long maxGasAllowance) {
        return new HederaEvmTransaction(
                senderId,
                relayerId,
                null,
                ethTxData.nonce(),
                Bytes.wrap(ethTxData.callData()),
                Bytes.wrap(ethTxData.chainId()),
                ethTxData.effectiveTinybarValue(),
                ethTxData.gasLimit(),
                ethTxData.effectiveOfferedGasPriceInTinybars(hederaEvmContext.gasPrice()),
                maxGasAllowance,
                synthEthTxCreation(ledgerConfig.autoRenewPeriodMinDuration(), ethTxData),
                null);
    }

    /**
     * Given an {@link Exception} and a {@link ContractCallTransactionBody},
     * create and return a {@link HederaEvmTransaction} containing the exception and gas limit
     *
     * @param exception the {@link Exception} to wrap
     * @return the  {@link HederaEvmTransaction} containing the exception
     */
    public HederaEvmTransaction fromContractTxException(
            @NonNull final TransactionBody body, @NonNull final HandleException exception) {
        AccountID sender = null;
        AccountID relayer = null;

        final var gasPrice =
                switch (body.data().kind()) {
                    case CONTRACT_CREATE_INSTANCE ->
                        body.contractCreateInstanceOrThrow().gas();
                    case CONTRACT_CALL -> body.contractCallOrThrow().gas();
                    case ETHEREUM_TRANSACTION -> {
                        final var ethTxData = assertValidEthTx(body.ethereumTransactionOrThrow());
                        sender = asAliasedSender(ethTxData);
                        relayer = body.transactionID().accountID();
                        yield ethTxData.gasLimit();
                    }
                    default -> throw new IllegalArgumentException("Not a contract operation");
                };
        return new HederaEvmTransaction(
                sender == null ? AccountID.DEFAULT : sender,
                relayer,
                null,
                NOT_APPLICABLE,
                Bytes.EMPTY,
                null,
                0,
                gasPrice,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                null,
                exception);
    }

    private @NonNull EthTxData assertValidEthTx(@NonNull final EthereumTransactionBody body) {
        validateTrue(body.maxGasAllowance() >= 0, NEGATIVE_ALLOWANCE_AMOUNT);
        if (!requireNonNull(hydratedEthTxData).isAvailable()) {
            throw new HandleException(hydratedEthTxData.status());
        }
        final var ethTxData = requireNonNull(hydratedEthTxData.ethTxData());
        validateTrue(ethTxData.matchesChainId(Integers.toBytes(contractsConfig.chainId())), WRONG_CHAIN_ID);
        validateTrue(ethTxData.hasToAddress() || ethTxData.hasCallData(), INVALID_ETHEREUM_TRANSACTION);
        return ethTxData;
    }

    private void assertValidCall(@NonNull final ContractCallTransactionBody body) {
        // TODO: Revisit baselineGas with Pectra support epic
        final var minGasLimit = Math.max(
                ContractServiceImpl.INTRINSIC_GAS_LOWER_BOUND,
                gasCalculator.transactionIntrinsicGasCost(EMPTY, false, 0L));
        validateTrue(body.gas() >= minGasLimit, INSUFFICIENT_GAS);
        validateTrue(body.amount() >= 0, CONTRACT_NEGATIVE_VALUE);
        validateTrue(body.gas() <= getMaxGasLimit(contractsConfig), MAX_GAS_LIMIT_EXCEEDED);

        final var contract = accountStore.getContractById(body.contractIDOrThrow());
        if (contract != null) {
            final var contractNum = contract.accountIdOrThrow().accountNumOrThrow();
            final var mayNotExist = featureFlags.isAllowCallsToNonContractAccountsEnabled(contractsConfig, contractNum);
            validateTrue(mayNotExist || !contract.deleted(), CONTRACT_DELETED);
        }
    }

    private void assertValidCreation(@NonNull final ContractCreateTransactionBody body) {
        if (body.hasFileID()) {
            validateTrue(body.fileIDOrThrow().fileNum() >= hederaConfig.firstUserEntity(), INVALID_FILE_ID);
        }
        final var autoRenewPeriod = body.autoRenewPeriodOrElse(Duration.DEFAULT).seconds();
        validateTrue(autoRenewPeriod >= 1, INVALID_RENEWAL_PERIOD);
        attributeValidator.validateAutoRenewPeriod(autoRenewPeriod);
        validateTrue(body.gas() >= 0, CONTRACT_NEGATIVE_GAS);
        validateTrue(body.initialBalance() >= 0, CONTRACT_NEGATIVE_VALUE);
        validateTrue(body.gas() <= getMaxGasLimit(contractsConfig), MAX_GAS_LIMIT_EXCEEDED);
        final var usesInvalidAutoAssociations = body.maxAutomaticTokenAssociations() < UNLIMITED_AUTOMATIC_ASSOCIATIONS
                && entitiesConfig.unlimitedAutoAssociationsEnabled();
        validateFalse(usesInvalidAutoAssociations, INVALID_MAX_AUTO_ASSOCIATIONS);
        validateTrue(
                body.maxAutomaticTokenAssociations() <= ledgerConfig.maxAutoAssociations(),
                REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT);
        final var usesNonDefaultProxyId = body.hasProxyAccountID() && !AccountID.DEFAULT.equals(body.proxyAccountID());
        validateFalse(usesNonDefaultProxyId, PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED);
        tokenServiceApi.assertValidStakingElectionForCreation(
                body.declineReward(),
                body.stakedId().kind().name(),
                body.stakedAccountId(),
                body.stakedNodeId(),
                accountStore,
                networkInfo);
        attributeValidator.validateMemo(body.memo());
        final var effectiveKey = body.adminKeyOrElse(Key.DEFAULT);
        if (!isEmpty(effectiveKey)) {
            try {
                attributeValidator.validateKey(body.adminKeyOrElse(Key.DEFAULT));
            } catch (HandleException | NullPointerException ignore) {
                throw new HandleException(SERIALIZATION_FAILED);
            }
        }
        expiryValidator.resolveCreationAttempt(
                true,
                new ExpiryMeta(
                        NA, autoRenewPeriod, body.hasAutoRenewAccountId() ? body.autoRenewAccountIdOrThrow() : null),
                HederaFunctionality.CONTRACT_CREATE);
    }

    private Bytes initcodeFor(@NonNull final ContractCreateTransactionBody body) {
        if (body.hasInitcode()) {
            validateTrue(body.initcode().length() > 0, CONTRACT_BYTECODE_EMPTY);
            return body.initcode();
        } else {
            final var initcode = fileStore.getFileLeaf(body.fileIDOrElse(FileID.DEFAULT));
            validateFalse(initcode == null, INVALID_FILE_ID);
            validateFalse(initcode.deleted(), FILE_DELETED);
            validateTrue(initcode.contents().length() > 0, CONTRACT_FILE_EMPTY);
            try {
                final var hexedInitcode = new String(removeIfAnyLeading0x(initcode.contents()));
                return Bytes.fromHex(
                        hexedInitcode + body.constructorParameters().toHex());
            } catch (IllegalArgumentException | NullPointerException ignore) {
                throw new HandleException(ERROR_DECODING_BYTESTRING);
            }
        }
    }

    private AccountID asAliasedSender(@NonNull final EthTxData txData) {
        final var txSign = ethereumSignatures.computeIfAbsent(txData);
        return AccountID.newBuilder().alias(Bytes.wrap(txSign.address())).build();
    }
}
