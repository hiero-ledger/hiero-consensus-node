// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.node.app.service.token.impl.util.CryptoTransferHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoTransferHelperTest {
    private static final TokenID tokenId = TokenID.newBuilder().tokenNum(1).build();
    private static final AccountID fromAccount =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID toAccount =
            AccountID.newBuilder().accountNum(1002).build();

    @Test
    void testCreateFungibleTransfer() {
        final long amount = 1000L;
        final var transferList = CryptoTransferHelper.createFungibleTransfer(tokenId, fromAccount, amount, toAccount);

        assertThat(transferList.token()).isEqualTo(tokenId);
        assertThat(transferList.transfers()).hasSize(2);
        assertThat(transferList.transfers().get(0).accountID()).isEqualTo(fromAccount);
        assertThat(transferList.transfers().get(0).amount()).isEqualTo(-amount);
        assertThat(transferList.transfers().get(1).accountID()).isEqualTo(toAccount);
        assertThat(transferList.transfers().get(1).amount()).isEqualTo(amount);
    }

    @Test
    void testCreateNftTransfer() {
        final long serialNumber = 123456L;
        final var transferList = CryptoTransferHelper.createNftTransfer(
                tokenId, CryptoTransferHelper.nftTransfer(fromAccount, toAccount, serialNumber));

        assertThat(transferList.token()).isEqualTo(tokenId);
        assertThat(transferList.nftTransfers()).hasSize(1);
        final var nftTransfer = transferList.nftTransfers().getFirst();
        assertThat(nftTransfer.senderAccountID()).isEqualTo(fromAccount);
        assertThat(nftTransfer.receiverAccountID()).isEqualTo(toAccount);
        assertThat(nftTransfer.serialNumber()).isEqualTo(serialNumber);
    }

    @Test
    void testNftTransfer() {
        final long serialNumber = 123456L;
        final NftTransfer nftTransfer = CryptoTransferHelper.nftTransfer(fromAccount, toAccount, serialNumber);

        assertThat(nftTransfer.senderAccountID()).isEqualTo(fromAccount);
        assertThat(nftTransfer.receiverAccountID()).isEqualTo(toAccount);
        assertThat(nftTransfer.serialNumber()).isEqualTo(serialNumber);
    }
}
