// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_FILE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_NEGATIVE_VALUE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ERROR_DECODING_BYTESTRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SERIALIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WRONG_CHAIN_ID;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.WEIBARS_IN_A_TINYBAR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_DELETED_CONTRACT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CONSTRUCTOR_PARAMS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_ENTITIES_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_LEDGER_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEV_CHAIN_ID_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITHOUT_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.INITCODE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.INITCODE_FILE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MAX_GAS_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RELAYER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_DURATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_MEMO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.EthTxSigsCache;
import com.hedera.node.app.service.contract.impl.infra.HevmTransactionFactory;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.base.utility.CommonUtils;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HevmTransactionFactoryTest {
    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private EthTxSigsCache ethereumSignatures;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private ReadableFileStore fileStore;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private HederaEvmContext context;

    private static final long TOP_LEVEL_TINYBAR_GAS_PRICE = 100L;

    private HevmTransactionFactory subject;

    @BeforeEach
    void setUp() {
        subject = new HevmTransactionFactory(
                networkInfo,
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_HEDERA_CONFIG,
                featureFlags,
                gasCalculator,
                DEFAULT_CONTRACTS_CONFIG,
                DEFAULT_ENTITIES_CONFIG,
                null,
                accountStore,
                expiryValidator,
                fileStore,
                attributeValidator,
                tokenServiceApi,
                ethereumSignatures,
                context,
                entityIdFactory);
    }

    @Test
    void fromHapiCallFailsWithGasBelowFixedLowerBound() {
        assertCallFailsWith(INSUFFICIENT_GAS, b -> b.gas(20_999L));
    }

    @Test
    void fromHapiCallFailsWithGasBelowGasCalculatorIntrinsicCost() {
        given(gasCalculator.transactionIntrinsicGasCost(org.apache.tuweni.bytes.Bytes.EMPTY, false))
                .willReturn(22_000L);
        assertCallFailsWith(INSUFFICIENT_GAS, b -> b.gas(21_999L));
    }

    @Test
    void fromHapiCallFailsNegativeValue() {
        assertCallFailsWith(CONTRACT_NEGATIVE_VALUE, b -> b.gas(30_000L).amount(-1L));
    }

    @Test
    void fromHapiCallFailsOverMaxGas() {
        assertCallFailsWith(MAX_GAS_LIMIT_EXCEEDED, b -> b.gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec() + 1));
    }

    @Test
    void fromHapiCallUsesEmptyCallDataWhenNotSet() {
        final var transaction = getManufacturedCall(
                b -> b.amount(123L).contractID(CALLED_CONTRACT_ID).gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec()));
        assertEquals(SENDER_ID, transaction.senderId());
        assertEquals(CALLED_CONTRACT_ID, transaction.contractId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertEquals(Bytes.EMPTY, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(123L, transaction.value());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec(), transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiCallGenerateExceptionTransaction() {
        final var transaction = getManufacturedCallException(
                b -> b.contractID(CALLED_CONTRACT_ID).gas(30_000L));
        assertEquals(AccountID.DEFAULT, transaction.senderId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertEquals(Bytes.EMPTY, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(0L, transaction.value());
        assertEquals(30_000L, transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiCallThrowsOnDeletedContractIfFeatureFlagNotEnabled() {
        given(accountStore.getContractById(CALLED_CONTRACT_ID)).willReturn(A_DELETED_CONTRACT);
        assertCallFailsWith(CONTRACT_DELETED, b -> b.amount(123L)
                .functionParameters(CALL_DATA)
                .contractID(CALLED_CONTRACT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec()));
    }

    @Test
    void fromHapiCallIgnoresDeletedContractIfFeatureFlagEnabled() {
        given(accountStore.getContractById(CALLED_CONTRACT_ID)).willReturn(A_DELETED_CONTRACT);
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(
                        DEFAULT_CONTRACTS_CONFIG, CALLED_CONTRACT_ID.contractNumOrThrow()))
                .willReturn(true);
        final var transaction = getManufacturedCall(b -> b.amount(123L)
                .functionParameters(CALL_DATA)
                .contractID(CALLED_CONTRACT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec()));
        assertEquals(SENDER_ID, transaction.senderId());
        assertEquals(CALLED_CONTRACT_ID, transaction.contractId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertEquals(CALL_DATA, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(123L, transaction.value());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec(), transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiCallUsesCallParamsWhenSet() {
        final var transaction = getManufacturedCall(b -> b.amount(123L)
                .functionParameters(CALL_DATA)
                .contractID(CALLED_CONTRACT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec()));
        assertEquals(SENDER_ID, transaction.senderId());
        assertEquals(CALLED_CONTRACT_ID, transaction.contractId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertEquals(CALL_DATA, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(123L, transaction.value());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec(), transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiCreationFailsOnSystemInitcode() {
        assertCreateFailsWith(
                INVALID_FILE_ID,
                b -> b.fileID(FileID.newBuilder()
                        .fileNum(DEFAULT_HEDERA_CONFIG.firstUserEntity() - 1)
                        .build()));
    }

    @Test
    void fromHapiCreationFailsOnInvalidRenewalPeriod() {
        assertCreateFailsWith(INVALID_RENEWAL_PERIOD, b -> b.autoRenewPeriod(Duration.DEFAULT));
    }

    @Test
    void fromHapiCreationFailsOnOutOfRangeDuration() {
        doThrow(new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE))
                .when(attributeValidator)
                .validateAutoRenewPeriod(SOME_DURATION.seconds());
        assertCreateFailsWith(AUTORENEW_DURATION_NOT_IN_RANGE, b -> b.autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationFailsOnNegativeGas() {
        assertCreateFailsWith(CONTRACT_NEGATIVE_GAS, b -> b.gas(-1).autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationFailsOnNegativeValue() {
        assertCreateFailsWith(
                CONTRACT_NEGATIVE_VALUE, b -> b.initialBalance(-1).gas(1).autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationFailsOnExcessGas() {
        assertCreateFailsWith(MAX_GAS_LIMIT_EXCEEDED, b -> b.initialBalance(1)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec() + 1)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationDoesNotPermitExcessAutoAssociations() {
        givenInsteadAutoAssociatingSubject();
        assertCreateFailsWith(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT, b -> b.gas(
                        DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .maxAutomaticTokenAssociations(DEFAULT_LEDGER_CONFIG.maxAutoAssociations() + 1)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationDoesNotPermitNonDefaultProxyField() {
        assertCreateFailsWith(PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED, b -> b.gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesStaking() {
        doThrow(new HandleException(INVALID_STAKING_ID))
                .when(tokenServiceApi)
                .assertValidStakingElectionForCreation(false, "STAKED_NODE_ID", null, 123L, accountStore, networkInfo);
        assertCreateFailsWith(INVALID_STAKING_ID, b -> b.stakedNodeId(123)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesMemo() {
        doThrow(new HandleException(MEMO_TOO_LONG)).when(attributeValidator).validateMemo(SOME_MEMO);
        assertCreateFailsWith(MEMO_TOO_LONG, b -> b.memo(SOME_MEMO)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesKeyWithSerializationFailedStatus() {
        doThrow(new HandleException(BAD_ENCODING)).when(attributeValidator).validateKey(AN_ED25519_KEY);
        assertCreateFailsWith(SERIALIZATION_FAILED, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesAutoRenewId() {
        final var createMeta = new ExpiryMeta(NA, SOME_DURATION.seconds(), NON_SYSTEM_ACCOUNT_ID);
        doThrow(new HandleException(INVALID_AUTORENEW_ACCOUNT))
                .when(expiryValidator)
                .resolveCreationAttempt(true, createMeta, HederaFunctionality.CONTRACT_CREATE);
        assertCreateFailsWith(INVALID_AUTORENEW_ACCOUNT, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesInitcodeFileId() {
        assertCreateFailsWith(INVALID_FILE_ID, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .fileID(INITCODE_FILE_ID)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesInitcodeDeletionStatus() {
        given(fileStore.getFileLeaf(INITCODE_FILE_ID))
                .willReturn(File.newBuilder().deleted(true).build());
        assertCreateFailsWith(FILE_DELETED, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .fileID(INITCODE_FILE_ID)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesInitcodeNotEmpty() {
        given(fileStore.getFileLeaf(INITCODE_FILE_ID))
                .willReturn(File.newBuilder().build());
        assertCreateFailsWith(CONTRACT_FILE_EMPTY, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .fileID(INITCODE_FILE_ID)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationValidatesInlineInitcodeNotEmpty() {
        assertCreateFailsWith(CONTRACT_BYTECODE_EMPTY, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .initcode(Bytes.EMPTY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationTranslatesHexParsingException() {
        given(fileStore.getFileLeaf(INITCODE_FILE_ID))
                .willReturn(File.newBuilder().contents(CALL_DATA).build());
        assertCreateFailsWith(ERROR_DECODING_BYTESTRING, b -> b.memo(SOME_MEMO)
                .adminKey(AN_ED25519_KEY)
                .constructorParameters(Bytes.wrap(new byte[] {(byte) 0xab}))
                .fileID(INITCODE_FILE_ID)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
    }

    @Test
    void fromHapiCreationStillPermitsEmptyKey() {
        final var immutabilitySentinelKey =
                Key.newBuilder().keyList(KeyList.DEFAULT).build();
        final var transaction = getManufacturedCreation(b -> b.memo(SOME_MEMO)
                .initcode(CALL_DATA)
                .initialBalance(123L)
                .adminKey(immutabilitySentinelKey)
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
        assertEquals(SENDER_ID, transaction.senderId());
        assertNull(transaction.contractId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertSame(CALL_DATA, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(123L, transaction.value());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec(), transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNotNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiCreationAppendsConstructorArgsIfPresent() {
        given(fileStore.getFileLeaf(INITCODE_FILE_ID))
                .willReturn(File.newBuilder().contents(INITCODE).build());
        String hexedPayload = new String(INITCODE.toByteArray()) + CommonUtils.hex(CONSTRUCTOR_PARAMS.toByteArray());
        final var expectedPayload = Bytes.wrap(CommonUtils.unhex(hexedPayload));
        final var transaction = getManufacturedCreation(b -> b.memo(SOME_MEMO)
                .fileID(INITCODE_FILE_ID)
                .constructorParameters(CONSTRUCTOR_PARAMS)
                .initialBalance(123L)
                .adminKey(Key.newBuilder().keyList(KeyList.DEFAULT))
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
        assertEquals(SENDER_ID, transaction.senderId());
        assertNull(transaction.contractId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertEquals(expectedPayload, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(123L, transaction.value());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec(), transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNotNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiCreationSkips0xPrefixFromInitcodeIfPresent() {
        given(fileStore.getFileLeaf(INITCODE_FILE_ID))
                .willReturn(File.newBuilder()
                        .contents(Bytes.wrap("0x" + new String(INITCODE.toByteArray())))
                        .build());
        String hexedPayload = new String(INITCODE.toByteArray()) + CommonUtils.hex(CONSTRUCTOR_PARAMS.toByteArray());
        final var expectedPayload = Bytes.wrap(CommonUtils.unhex(hexedPayload));
        final var transaction = getManufacturedCreation(b -> b.memo(SOME_MEMO)
                .fileID(INITCODE_FILE_ID)
                .constructorParameters(CONSTRUCTOR_PARAMS)
                .initialBalance(123L)
                .adminKey(Key.newBuilder().keyList(KeyList.DEFAULT))
                .gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec())
                .proxyAccountID(AccountID.DEFAULT)
                .autoRenewPeriod(SOME_DURATION));
        assertEquals(SENDER_ID, transaction.senderId());
        assertNull(transaction.contractId());
        assertNull(transaction.relayerId());
        assertFalse(transaction.hasExpectedNonce());
        assertEquals(expectedPayload, transaction.payload());
        assertNull(transaction.chainId());
        assertEquals(123L, transaction.value());
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec(), transaction.gasLimit());
        assertFalse(transaction.hasOfferedGasPrice());
        assertFalse(transaction.hasMaxGasAllowance());
        assertNotNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiTransactionThrowsOnNonContractOperation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.fromHapiTransaction(TransactionBody.DEFAULT, AccountID.DEFAULT));
    }

    @Test
    void fromHapiEthFailsImmediatelyWithoutHydratedData() {
        givenInsteadFailedHydrationSubject();
        assertEthTxFailsWith(CONTRACT_FILE_EMPTY, b -> b.callData(INITCODE_FILE_ID));
    }

    @Test
    void fromHapiEthFailsImmediatelyWithNegativeAllowance() {
        givenInsteadHydratedEthTxWithWrongChainId(ETH_DATA_WITH_CALL_DATA);
        assertEthTxFailsWith(NEGATIVE_ALLOWANCE_AMOUNT, b -> b.maxGasAllowance(-1));
    }

    @Test
    void fromHapiEthFailsImmediatelyWithWrongChainId() {
        givenInsteadHydratedEthTxWithWrongChainId(ETH_DATA_WITH_CALL_DATA);
        assertEthTxFailsWith(WRONG_CHAIN_ID, b -> {});
    }

    @Test
    void fromHapiEthFailsImmediatelyWithoutToAddressButNoCallData() {
        givenInsteadHydratedEthTxWithRightChainId(ETH_DATA_WITHOUT_TO_ADDRESS.replaceCallData(new byte[0]));
        assertEthTxFailsWith(INVALID_ETHEREUM_TRANSACTION, b -> {});
    }

    @Test
    void fromHapiEthRepresentsCallAsExpected() {
        givenInsteadHydratedEthTxWithRightChainId(ETH_DATA_WITH_TO_ADDRESS);
        final var sig = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS)).willReturn(sig);
        final var transaction = getManufacturedEthTx(b -> b.maxGasAllowance(MAX_GAS_ALLOWANCE));
        final var expectedSenderId =
                AccountID.newBuilder().alias(Bytes.wrap(sig.address())).build();
        assertEquals(expectedSenderId, transaction.senderId());
        assertEquals(RELAYER_ID, transaction.relayerId());
        final var expectedContractId = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.to()))
                .build();
        assertEquals(expectedContractId, transaction.contractId());
        assertTrue(transaction.hasExpectedNonce());
        assertEquals(0, transaction.nonce());
        assertEquals(Bytes.EMPTY, transaction.payload());
        assertEquals(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.chainId()), transaction.chainId());
        assertEquals(
                ETH_DATA_WITH_TO_ADDRESS.value().divide(WEIBARS_IN_A_TINYBAR).longValueExact(), transaction.value());
        assertEquals(ETH_DATA_WITH_TO_ADDRESS.gasLimit(), transaction.gasLimit());
        assertEquals(
                ETH_DATA_WITH_TO_ADDRESS
                        .getMaxGasAsBigInteger(TOP_LEVEL_TINYBAR_GAS_PRICE)
                        .divide(WEIBARS_IN_A_TINYBAR)
                        .longValueExact(),
                transaction.offeredGasPrice());
        assertEquals(MAX_GAS_ALLOWANCE, transaction.maxGasAllowance());
        assertNull(transaction.hapiCreation());
    }

    @Test
    void fromHapiEthRepresentsCreateAsExpected() {
        final var dataToUse = ETH_DATA_WITHOUT_TO_ADDRESS.replaceCallData(CALL_DATA.toByteArray());
        givenInsteadHydratedEthTxWithRightChainId(dataToUse);
        final var sig = EthTxSigs.extractSignatures(dataToUse);
        given(ethereumSignatures.computeIfAbsent(dataToUse)).willReturn(sig);
        final var transaction = getManufacturedEthTx(b -> b.maxGasAllowance(MAX_GAS_ALLOWANCE));
        final var expectedSenderId =
                AccountID.newBuilder().alias(Bytes.wrap(sig.address())).build();
        assertEquals(expectedSenderId, transaction.senderId());
        assertEquals(RELAYER_ID, transaction.relayerId());
        assertNull(transaction.contractId());
        assertTrue(transaction.hasExpectedNonce());
        assertEquals(0, transaction.nonce());
        assertEquals(CALL_DATA, transaction.payload());
        assertEquals(Bytes.wrap(dataToUse.chainId()), transaction.chainId());
        assertEquals(dataToUse.value().divide(WEIBARS_IN_A_TINYBAR).longValueExact(), transaction.value());
        assertEquals(dataToUse.gasLimit(), transaction.gasLimit());
        assertEquals(
                dataToUse.effectiveOfferedGasPriceInTinybars(TOP_LEVEL_TINYBAR_GAS_PRICE),
                transaction.offeredGasPrice());
        assertEquals(MAX_GAS_ALLOWANCE, transaction.maxGasAllowance());

        final var minAutoRenewPeriod = Duration.newBuilder()
                .seconds(DEFAULT_LEDGER_CONFIG.autoRenewPeriodMinDuration())
                .build();
        final var expectedCreation = ContractCreateTransactionBody.newBuilder()
                .autoRenewPeriod(minAutoRenewPeriod)
                .gas(dataToUse.gasLimit())
                .initialBalance(dataToUse.effectiveTinybarValue())
                .initcode(CALL_DATA)
                .build();
        assertEquals(expectedCreation, transaction.hapiCreation());
    }

    private void assertCreateFailsWith(
            @NonNull final ResponseCodeEnum status,
            @NonNull final Consumer<ContractCreateTransactionBody.Builder> spec) {
        assertFailsWith(
                status,
                () -> subject.fromHapiTransaction(
                        TransactionBody.newBuilder()
                                .transactionID(TransactionID.newBuilder().accountID(SENDER_ID))
                                .contractCreateInstance(createWith(spec))
                                .build(),
                        SENDER_ID));
    }

    private void assertCallFailsWith(
            @NonNull final ResponseCodeEnum status, @NonNull final Consumer<ContractCallTransactionBody.Builder> spec) {
        assertFailsWith(
                status,
                () -> subject.fromHapiTransaction(
                        TransactionBody.newBuilder()
                                .transactionID(TransactionID.newBuilder().accountID(SENDER_ID))
                                .contractCall(callWith(spec))
                                .build(),
                        SENDER_ID));
    }

    private void assertEthTxFailsWith(
            @NonNull final ResponseCodeEnum status, @NonNull final Consumer<EthereumTransactionBody.Builder> spec) {
        assertFailsWith(
                status,
                () -> subject.fromHapiTransaction(
                        TransactionBody.newBuilder()
                                .transactionID(TransactionID.newBuilder().accountID(SENDER_ID))
                                .ethereumTransaction(ethTxWith(spec))
                                .build(),
                        SENDER_ID));
    }

    private HederaEvmTransaction getManufacturedEthTx(@NonNull final Consumer<EthereumTransactionBody.Builder> spec) {
        return subject.fromHapiTransaction(
                TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(RELAYER_ID))
                        .ethereumTransaction(ethTxWith(spec))
                        .build(),
                RELAYER_ID);
    }

    private HederaEvmTransaction getManufacturedCreation(
            @NonNull final Consumer<ContractCreateTransactionBody.Builder> spec) {
        return subject.fromHapiTransaction(
                TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(SENDER_ID))
                        .contractCreateInstance(createWith(spec))
                        .build(),
                SENDER_ID);
    }

    private HederaEvmTransaction getManufacturedCall(
            @NonNull final Consumer<ContractCallTransactionBody.Builder> spec) {
        return subject.fromHapiTransaction(
                TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(SENDER_ID))
                        .contractCall(callWith(spec))
                        .build(),
                SENDER_ID);
    }

    private HederaEvmTransaction getManufacturedCallException(
            @NonNull final Consumer<ContractCallTransactionBody.Builder> spec) {
        return subject.fromContractTxException(
                TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder())
                        .contractCall(callWith(spec))
                        .build(),
                new HandleException(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }

    private ContractCreateTransactionBody createWith(final Consumer<ContractCreateTransactionBody.Builder> spec) {
        final var builder = ContractCreateTransactionBody.newBuilder();
        spec.accept(builder);
        return builder.build();
    }

    private ContractCallTransactionBody callWith(final Consumer<ContractCallTransactionBody.Builder> spec) {
        final var builder = ContractCallTransactionBody.newBuilder();
        spec.accept(builder);
        return builder.build();
    }

    private EthereumTransactionBody ethTxWith(final Consumer<EthereumTransactionBody.Builder> spec) {
        final var builder = EthereumTransactionBody.newBuilder();
        spec.accept(builder);
        return builder.build();
    }

    private void givenInsteadAutoAssociatingSubject() {
        subject = new HevmTransactionFactory(
                networkInfo,
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_HEDERA_CONFIG,
                featureFlags,
                gasCalculator,
                DEFAULT_CONTRACTS_CONFIG,
                DEFAULT_ENTITIES_CONFIG,
                null,
                accountStore,
                expiryValidator,
                fileStore,
                attributeValidator,
                tokenServiceApi,
                ethereumSignatures,
                context,
                entityIdFactory);
    }

    private void givenInsteadFailedHydrationSubject() {
        subject = new HevmTransactionFactory(
                networkInfo,
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_HEDERA_CONFIG,
                featureFlags,
                gasCalculator,
                DEFAULT_CONTRACTS_CONFIG,
                DEFAULT_ENTITIES_CONFIG,
                HydratedEthTxData.failureFrom(CONTRACT_FILE_EMPTY),
                accountStore,
                expiryValidator,
                fileStore,
                attributeValidator,
                tokenServiceApi,
                ethereumSignatures,
                context,
                entityIdFactory);
    }

    private void givenInsteadHydratedEthTxWithWrongChainId(@NonNull final EthTxData ethTxData) {
        subject = new HevmTransactionFactory(
                networkInfo,
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_HEDERA_CONFIG,
                featureFlags,
                gasCalculator,
                DEFAULT_CONTRACTS_CONFIG,
                DEFAULT_ENTITIES_CONFIG,
                HydratedEthTxData.successFrom(ethTxData, false),
                accountStore,
                expiryValidator,
                fileStore,
                attributeValidator,
                tokenServiceApi,
                ethereumSignatures,
                context,
                entityIdFactory);
    }

    private void givenInsteadHydratedEthTxWithRightChainId(@NonNull final EthTxData ethTxData) {
        subject = new HevmTransactionFactory(
                networkInfo,
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_HEDERA_CONFIG,
                featureFlags,
                gasCalculator,
                DEV_CHAIN_ID_CONTRACTS_CONFIG,
                DEFAULT_ENTITIES_CONFIG,
                HydratedEthTxData.successFrom(ethTxData, false),
                accountStore,
                expiryValidator,
                fileStore,
                attributeValidator,
                tokenServiceApi,
                ethereumSignatures,
                context,
                entityIdFactory);
    }
}
