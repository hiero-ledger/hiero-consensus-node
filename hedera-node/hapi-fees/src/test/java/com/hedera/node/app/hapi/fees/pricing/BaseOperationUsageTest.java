// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_AUTO_RENEW;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BaseOperationUsageTest {
    @Test
    void picksAppropriateFileOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(FILE_APPEND, DEFAULT);
        verify(mock).fileAppend();
    }

    @Test
    void picksAppropriateContractOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(CONTRACT_AUTO_RENEW, DEFAULT);
        verify(mock).contractAutoRenew();
    }

    @Test
    void picksAppropriateCryptoOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(CRYPTO_TRANSFER, DEFAULT);
        verify(mock).hbarCryptoTransfer();

        mock.baseUsageFor(CRYPTO_TRANSFER, TOKEN_FUNGIBLE_COMMON);
        verify(mock).htsCryptoTransfer();

        mock.baseUsageFor(CRYPTO_TRANSFER, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        verify(mock).htsCryptoTransferWithCustomFee();

        mock.baseUsageFor(CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).nftCryptoTransfer();

        mock.baseUsageFor(CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
        verify(mock).nftCryptoTransferWithCustomFee();

        mock.baseUsageFor(CRYPTO_CREATE, DEFAULT);
        verify(mock).cryptoCreate(0);

        mock.baseUsageFor(CRYPTO_UPDATE, DEFAULT);
        verify(mock).cryptoUpdate(0);

        mock.baseUsageFor(HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE, DEFAULT);
        verify(mock).cryptoApproveAllowance();

        mock.baseUsageFor(HederaFunctionality.CRYPTO_DELETE_ALLOWANCE, DEFAULT);
        verify(mock).cryptoDeleteAllowance();
    }

    @Test
    void picksAppropriateTokenOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(TOKEN_ACCOUNT_WIPE, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleCommonTokenWipe();

        mock.baseUsageFor(TOKEN_ACCOUNT_WIPE, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenWipe();

        mock.baseUsageFor(TOKEN_BURN, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleCommonTokenBurn();

        mock.baseUsageFor(TOKEN_BURN, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenBurn();

        mock.baseUsageFor(TOKEN_MINT, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleCommonTokenMint();

        mock.baseUsageFor(TOKEN_MINT, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenMint();

        mock.baseUsageFor(CONSENSUS_SUBMIT_MESSAGE, DEFAULT);
        verify(mock).submitMessage();

        mock.baseUsageFor(TOKEN_FEE_SCHEDULE_UPDATE, DEFAULT);
        verify(mock).feeScheduleUpdate();

        mock.baseUsageFor(TOKEN_CREATE, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleTokenCreate();

        mock.baseUsageFor(TOKEN_CREATE, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenCreate();

        mock.baseUsageFor(TOKEN_CREATE, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        verify(mock).fungibleTokenCreateWithCustomFees();

        mock.baseUsageFor(TOKEN_CREATE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
        verify(mock).uniqueTokenCreateWithCustomFees();
    }

    @Test
    void picksAppropriateScheduleOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(SCHEDULE_CREATE, DEFAULT);
        verify(mock).scheduleCreate();

        mock.baseUsageFor(SCHEDULE_CREATE, SCHEDULE_CREATE_CONTRACT_CALL);
        verify(mock).scheduleCreateWithContractCall();
    }

    @Test
    void picksUtilPrngOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(UTIL_PRNG, DEFAULT);
        verify(mock).utilPrng();
    }

    @Test
    void failsOnUnrecognizedTokenTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(TOKEN_CREATE, SubType.fromString("UNRECOGNIZED")));

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(TOKEN_MINT, SubType.fromString("UNRECOGNIZED")));

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(TOKEN_ACCOUNT_WIPE, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES));

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(TOKEN_BURN, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
    }

    @Test
    void failsOnUnrecognizedCryptoTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(CRYPTO_TRANSFER, SubType.fromString("UNRECOGNIZED")));
    }

    @Test
    void failsOnUnrecognizedFileTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(FILE_APPEND, SubType.fromString("UNRECOGNIZED")));
    }

    @Test
    void failsOnUnrecognizedScheduleTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(SCHEDULE_CREATE, SubType.fromString("UNRECOGNIZED")));

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(SCHEDULE_SIGN, DEFAULT));
    }
}
