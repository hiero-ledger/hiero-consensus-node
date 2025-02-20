// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractAutoRenew;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleSign;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.UtilPrng;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.UNRECOGNIZED;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BaseOperationUsageTest {
    @Test
    void picksAppropriateFileOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(FileAppend, DEFAULT);
        verify(mock).fileAppend();
    }

    @Test
    void picksAppropriateContractOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(ContractAutoRenew, DEFAULT);
        verify(mock).contractAutoRenew();
    }

    @Test
    void picksAppropriateCryptoOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(CryptoTransfer, DEFAULT);
        verify(mock).hbarCryptoTransfer();

        mock.baseUsageFor(CryptoTransfer, TOKEN_FUNGIBLE_COMMON);
        verify(mock).htsCryptoTransfer();

        mock.baseUsageFor(CryptoTransfer, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        verify(mock).htsCryptoTransferWithCustomFee();

        mock.baseUsageFor(CryptoTransfer, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).nftCryptoTransfer();

        mock.baseUsageFor(CryptoTransfer, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
        verify(mock).nftCryptoTransferWithCustomFee();

        mock.baseUsageFor(CryptoCreate, DEFAULT);
        verify(mock).cryptoCreate(0);

        mock.baseUsageFor(CryptoUpdate, DEFAULT);
        verify(mock).cryptoUpdate(0);

        mock.baseUsageFor(HederaFunctionality.CryptoApproveAllowance, DEFAULT);
        verify(mock).cryptoApproveAllowance();

        mock.baseUsageFor(HederaFunctionality.CryptoDeleteAllowance, DEFAULT);
        verify(mock).cryptoDeleteAllowance();
    }

    @Test
    void picksAppropriateTokenOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(TokenAccountWipe, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleCommonTokenWipe();

        mock.baseUsageFor(TokenAccountWipe, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenWipe();

        mock.baseUsageFor(TokenBurn, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleCommonTokenBurn();

        mock.baseUsageFor(TokenBurn, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenBurn();

        mock.baseUsageFor(TokenMint, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleCommonTokenMint();

        mock.baseUsageFor(TokenMint, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenMint();

        mock.baseUsageFor(ConsensusSubmitMessage, DEFAULT);
        verify(mock).submitMessage();

        mock.baseUsageFor(TokenFeeScheduleUpdate, DEFAULT);
        verify(mock).feeScheduleUpdate();

        mock.baseUsageFor(TokenCreate, TOKEN_FUNGIBLE_COMMON);
        verify(mock).fungibleTokenCreate();

        mock.baseUsageFor(TokenCreate, TOKEN_NON_FUNGIBLE_UNIQUE);
        verify(mock).uniqueTokenCreate();

        mock.baseUsageFor(TokenCreate, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        verify(mock).fungibleTokenCreateWithCustomFees();

        mock.baseUsageFor(TokenCreate, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
        verify(mock).uniqueTokenCreateWithCustomFees();
    }

    @Test
    void picksAppropriateScheduleOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(ScheduleCreate, DEFAULT);
        verify(mock).scheduleCreate();

        mock.baseUsageFor(ScheduleCreate, SCHEDULE_CREATE_CONTRACT_CALL);
        verify(mock).scheduleCreateWithContractCall();
    }

    @Test
    void picksUtilPrngOp() {
        final var mock = Mockito.spy(new BaseOperationUsage());

        mock.baseUsageFor(UtilPrng, DEFAULT);
        verify(mock).utilPrng();
    }

    @Test
    void failsOnUnrecognizedTokenTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(TokenCreate, UNRECOGNIZED));

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(TokenMint, UNRECOGNIZED));

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(TokenAccountWipe, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES));

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.baseUsageFor(TokenBurn, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
    }

    @Test
    void failsOnUnrecognizedCryptoTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(CryptoTransfer, UNRECOGNIZED));
    }

    @Test
    void failsOnUnrecognizedFileTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(FileAppend, UNRECOGNIZED));
    }

    @Test
    void failsOnUnrecognizedScheduleTypes() {
        final var subject = new BaseOperationUsage();

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(ScheduleCreate, UNRECOGNIZED));

        assertThrows(IllegalArgumentException.class, () -> subject.baseUsageFor(ScheduleSign, DEFAULT));
    }
}
