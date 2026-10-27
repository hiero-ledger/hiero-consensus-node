// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferValidationHelper.checkSender;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoTransferValidationHelperTest {
    private static final AccountID SENDER_ID =
            AccountID.newBuilder().accountNum(1234L).build();
    private static final AccountID RECEIVER_ID =
            AccountID.newBuilder().accountNum(5678L).build();
    private static final Key SENDER_KEY = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final NftTransfer PLAIN_TRANSFER = NftTransfer.newBuilder()
            .senderAccountID(SENDER_ID)
            .receiverAccountID(RECEIVER_ID)
            .serialNumber(1L)
            .build();

    @Mock
    private PreHandleContext meta;

    @Mock
    private ReadableAccountStore accountStore;

    static Stream<NftTransfer> hookTransfers() {
        return Stream.of(
                PLAIN_TRANSFER
                        .copyBuilder()
                        .preTxSenderAllowanceHook(HookCall.DEFAULT)
                        .build(),
                PLAIN_TRANSFER
                        .copyBuilder()
                        .prePostTxSenderAllowanceHook(HookCall.DEFAULT)
                        .build());
    }

    @Test
    void senderWithoutHookMustSign() throws PreCheckException {
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(senderWithKey(SENDER_KEY));

        checkSender(SENDER_ID, PLAIN_TRANSFER, meta, accountStore);

        verify(meta).requireKey(SENDER_KEY);
    }

    @Test
    void missingSenderWithoutHookIsRejected() {
        assertThatThrownBy(() -> checkSender(SENDER_ID, PLAIN_TRANSFER, meta, accountStore))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
        verifyNoInteractions(meta);
    }

    @Test
    void immutableSenderWithoutHookIsRejected() {
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(immutableSender());

        assertThatThrownBy(() -> checkSender(SENDER_ID, PLAIN_TRANSFER, meta, accountStore))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
        verifyNoInteractions(meta);
    }

    @Test
    void senderWithoutKeyIsRejected() {
        given(accountStore.getAliasedAccountById(SENDER_ID))
                .willReturn(Account.newBuilder().accountId(SENDER_ID).build());

        assertThatThrownBy(() -> checkSender(SENDER_ID, PLAIN_TRANSFER, meta, accountStore))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
        verifyNoInteractions(meta);
    }

    @Test
    void hollowSenderWithoutHookMustSign() throws PreCheckException {
        final var hollowSender = hollowSender();
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(hollowSender);

        checkSender(SENDER_ID, PLAIN_TRANSFER, meta, accountStore);

        verify(meta).requireSignatureForHollowAccount(hollowSender);
    }

    @Test
    void approvedTransferDoesNotRequireSenderKey() throws PreCheckException {
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(senderWithKey(SENDER_KEY));

        checkSender(SENDER_ID, PLAIN_TRANSFER.copyBuilder().isApproval(true).build(), meta, accountStore);

        verifyNoInteractions(meta);
    }

    @ParameterizedTest
    @MethodSource("hookTransfers")
    void missingSenderIsRejectedEvenWithHook(final NftTransfer nftTransfer) {
        assertThatThrownBy(() -> checkSender(SENDER_ID, nftTransfer, meta, accountStore))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
        verify(accountStore).getAliasedAccountById(SENDER_ID);
        verifyNoInteractions(meta);
    }

    @ParameterizedTest
    @MethodSource("hookTransfers")
    void hookReplacesSenderSignature(final NftTransfer nftTransfer) throws PreCheckException {
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(senderWithKey(SENDER_KEY));

        checkSender(SENDER_ID, nftTransfer, meta, accountStore);

        verify(accountStore).getAliasedAccountById(SENDER_ID);
        verifyNoInteractions(meta);
    }

    @ParameterizedTest
    @MethodSource("hookTransfers")
    void hookSkipsHollowSenderSignature(final NftTransfer nftTransfer) throws PreCheckException {
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(hollowSender());

        checkSender(SENDER_ID, nftTransfer, meta, accountStore);

        verify(accountStore).getAliasedAccountById(SENDER_ID);
        verifyNoInteractions(meta);
    }

    @ParameterizedTest
    @MethodSource("hookTransfers")
    void hookSkipsImmutableSenderCheck(final NftTransfer nftTransfer) throws PreCheckException {
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(immutableSender());

        checkSender(SENDER_ID, nftTransfer, meta, accountStore);

        verify(accountStore).getAliasedAccountById(SENDER_ID);
        verifyNoInteractions(meta);
    }

    private static Account senderWithKey(final Key key) {
        return Account.newBuilder().accountId(SENDER_ID).key(key).build();
    }

    private static Account immutableSender() {
        return senderWithKey(IMMUTABILITY_SENTINEL_KEY);
    }

    private static Account hollowSender() {
        return immutableSender().copyBuilder().alias(Bytes.wrap(new byte[20])).build();
    }
}
