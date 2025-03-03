// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ClassicTransfersGasCalcTest extends CallTestBase {
    private static final long PRETEND_CRYPTO_CREATE_TINYBAR_PRICE = 1_234L;
    private static final long PRETEND_CRYPTO_UPDATE_TINYBAR_PRICE = 2_345L;
    private static final long PRETEND_LAZY_CREATION_TINYBAR_PRICE =
            PRETEND_CRYPTO_CREATE_TINYBAR_PRICE + PRETEND_CRYPTO_UPDATE_TINYBAR_PRICE;
    private static final long PRETEND_NFT_TRANSFER_TINYBAR_PRICE = 3_456L;
    private static final long PRETEND_NFT_TRANSFER_CUSTOM_FEES_TINYBAR_PRICE = 2 * PRETEND_NFT_TRANSFER_TINYBAR_PRICE;
    private static final long PRETEND_HBAR_TRANSFER_TINYBAR_PRICE = 4_567L;
    private static final long PRETEND_FUNGIBLE_TRANSFER_TINYBAR_PRICE = 5_678L;
    private static final long PRETEND_FUNGIBLE_TRANSFER_CUSTOM_FEES_TINYBAR_PRICE =
            2 * PRETEND_FUNGIBLE_TRANSFER_TINYBAR_PRICE;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    private final CryptoTransferTransactionBody op = transferToSometimeAliasedReceiver();

    @Test
    void chargesForPerceivedLazyCreations() {
        givenTokensUseCustomFees();
        givenPretendLazyCreationPrices();
        givenPretendPricesWithCustomFees();
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);

        final var expectedFtMinimumPrice = PRETEND_FUNGIBLE_TRANSFER_CUSTOM_FEES_TINYBAR_PRICE / 2;
        final var expectedHbarMinimumPrice = PRETEND_HBAR_TRANSFER_TINYBAR_PRICE / 2;
        final var expectedTotalTinybarPrice = PRETEND_NFT_TRANSFER_CUSTOM_FEES_TINYBAR_PRICE
                + 2 * expectedFtMinimumPrice
                + 2 * expectedHbarMinimumPrice
                + PRETEND_LAZY_CREATION_TINYBAR_PRICE;
        final var expectedGasRequirement = 666L;
        final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();
        given(systemContractGasCalculator.gasRequirementWithTinycents(
                        body, AccountID.DEFAULT, expectedTotalTinybarPrice))
                .willReturn(expectedGasRequirement);

        final var actualGasRequirement = ClassicTransfersCall.transferGasRequirement(
                body, systemContractGasCalculator, mockEnhancement(), AccountID.DEFAULT);
        assertEquals(expectedGasRequirement, actualGasRequirement);
    }

    @Test
    void doesNotChargeForExtantAliases() {
        givenPretendPricesWithoutCustomFees();
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);
        given(accountStore.getAccountIDByAlias(0, 0, TestHelpers.ALIASED_RECEIVER_ID.aliasOrThrow()))
                .willReturn(AccountID.DEFAULT);

        final var expectedFtMinimumPrice = PRETEND_FUNGIBLE_TRANSFER_TINYBAR_PRICE / 2;
        final var expectedHbarMinimumPrice = PRETEND_HBAR_TRANSFER_TINYBAR_PRICE / 2;
        final var expectedTotalTinybarPrice =
                PRETEND_NFT_TRANSFER_TINYBAR_PRICE + 2 * expectedFtMinimumPrice + 2 * expectedHbarMinimumPrice;
        final var expectedGasRequirement = 666L;
        final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();
        given(systemContractGasCalculator.gasRequirementWithTinycents(
                        body, AccountID.DEFAULT, expectedTotalTinybarPrice))
                .willReturn(expectedGasRequirement);

        final var actualGasRequirement = ClassicTransfersCall.transferGasRequirement(
                body, systemContractGasCalculator, mockEnhancement(), AccountID.DEFAULT);
        assertEquals(expectedGasRequirement, actualGasRequirement);
    }

    private void givenPretendPricesWithCustomFees() {
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_HBAR))
                .willReturn(PRETEND_HBAR_TRANSFER_TINYBAR_PRICE);
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_NFT_CUSTOM_FEES))
                .willReturn(PRETEND_NFT_TRANSFER_CUSTOM_FEES_TINYBAR_PRICE);
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_FUNGIBLE_CUSTOM_FEES))
                .willReturn(PRETEND_FUNGIBLE_TRANSFER_CUSTOM_FEES_TINYBAR_PRICE);
    }

    private void givenPretendPricesWithoutCustomFees() {
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_NFT))
                .willReturn(PRETEND_NFT_TRANSFER_TINYBAR_PRICE);
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_HBAR))
                .willReturn(PRETEND_HBAR_TRANSFER_TINYBAR_PRICE);
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_FUNGIBLE))
                .willReturn(PRETEND_FUNGIBLE_TRANSFER_TINYBAR_PRICE);
    }

    private void givenPretendLazyCreationPrices() {
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.CRYPTO_UPDATE))
                .willReturn(PRETEND_CRYPTO_UPDATE_TINYBAR_PRICE);
        given(systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.CRYPTO_CREATE))
                .willReturn(PRETEND_CRYPTO_CREATE_TINYBAR_PRICE);
    }

    private void givenExtantReceiver() {
        given(accountStore.getAccountById(TestHelpers.ALIASED_RECEIVER_ID)).willReturn(TestHelpers.ALIASED_RECEIVER);
    }

    private void givenTokensUseCustomFees() {
        given(nativeOperations.checkForCustomFees(op)).willReturn(true);
    }

    private CryptoTransferTransactionBody transferToSometimeAliasedReceiver() {
        return CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(TestHelpers.SENDER_ID)
                                        .amount(-1)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(TestHelpers.RECEIVER_ID)
                                        .amount(+1)
                                        .build())
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(TestHelpers.FUNGIBLE_TOKEN_ID)
                                .transfers(
                                        AccountAmount.newBuilder()
                                                .accountID(TestHelpers.SENDER_ID)
                                                .amount(-1)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(TestHelpers.ALIASED_RECEIVER_ID)
                                                .amount(+1)
                                                .build())
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(TestHelpers.NON_FUNGIBLE_TOKEN_ID)
                                .nftTransfers(NftTransfer.newBuilder()
                                        .senderAccountID(TestHelpers.SENDER_ID)
                                        .receiverAccountID(TestHelpers.ALIASED_RECEIVER_ID)
                                        .serialNumber(123L)
                                        .build())
                                .build())
                .build();
    }
}
