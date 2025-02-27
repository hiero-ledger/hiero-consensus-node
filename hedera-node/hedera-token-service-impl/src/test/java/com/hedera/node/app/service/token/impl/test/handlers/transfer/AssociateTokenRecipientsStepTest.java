// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AssociateTokenRecipientsStep}.
 */
@ExtendWith(MockitoExtension.class)
public class AssociateTokenRecipientsStepTest extends StepsBase {
    @Mock(strictness = Mock.Strictness.LENIENT)
    private HandleContext handleContext;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private CryptoTransferStreamBuilder builder;

    private AssociateTokenRecipientsStep subject;
    private AssociateTokenRecipientsStep subjectWithApproval;
    private AssociateTokenRecipientsStep subjectNFTWithApproval;
    private CryptoTransferTransactionBody txn;
    private CryptoTransferTransactionBody txnWithApproval;
    private CryptoTransferTransactionBody txnNFTWithApproval;
    private TransferContextImpl transferContext;

    @BeforeEach
    public void setUp() {
        super.setUp();
        givenValidTxn();
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        subject = new AssociateTokenRecipientsStep(txn);
        subjectWithApproval = new AssociateTokenRecipientsStep(txnWithApproval);
        subjectNFTWithApproval = new AssociateTokenRecipientsStep(txnNFTWithApproval);
        transferContext = new TransferContextImpl(handleContext);
        writableTokenStore.put(givenValidFungibleToken(ownerId, false, false, false, false, false));
        writableTokenStore.put(givenValidNonFungibleToken(false));
    }

    @Test
    void associatesTokenRecipients() {
        assertThat(writableTokenRelStore.get(ownerId, fungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(ownerId, nonFungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(spenderId, fungibleTokenId)).isNull();
        assertThat(writableTokenRelStore.get(spenderId, nonFungibleTokenId)).isNull();

        final var modifiedConfiguration = HederaTestConfigBuilder.create()
                .withValue("entities.unlimitedAutoAssociationsEnabled", false)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(modifiedConfiguration);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(builder);

        subject.doIn(transferContext);

        assertThat(writableTokenRelStore.get(ownerId, fungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(ownerId, nonFungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(spenderId, fungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(spenderId, nonFungibleTokenId)).isNotNull();
    }

    @Test
    void validateFungibleAllowancesAndOtherPrivateChecks() {
        assertThat(writableTokenRelStore.get(ownerId, fungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(ownerId, nonFungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(spenderId, fungibleTokenId)).isNull();
        assertThat(writableTokenRelStore.get(spenderId, nonFungibleTokenId)).isNull();

        final var modifiedConfiguration = HederaTestConfigBuilder.create()
                .withValue("entities.unlimitedAutoAssociationsEnabled", false)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(modifiedConfiguration);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(builder);
        given(builder.isUserDispatch()).willThrow(new HandleException(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));

        AssertionsForClassTypes.assertThatThrownBy(() -> subjectWithApproval.doIn(transferContext))
                .isInstanceOf(HandleException.class);
    }

    @Test
    void validateNFTAllowancesAndOtherPrivateChecks() {
        assertThat(writableTokenRelStore.get(ownerId, fungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(ownerId, nonFungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(spenderId, fungibleTokenId)).isNull();
        assertThat(writableTokenRelStore.get(spenderId, nonFungibleTokenId)).isNull();

        final var modifiedConfiguration = HederaTestConfigBuilder.create()
                .withValue("entities.unlimitedAutoAssociationsEnabled", true)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(modifiedConfiguration);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(builder);
        given(builder.isUserDispatch()).willReturn(true);

        AssertionsForClassTypes.assertThatThrownBy(() -> subjectNFTWithApproval.doIn(transferContext))
                .isInstanceOf(HandleException.class);
    }

    @Test
    void validateNFTAllowancesAndForceToThrow() {
        assertThat(writableTokenRelStore.get(ownerId, fungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(ownerId, nonFungibleTokenId)).isNotNull();
        assertThat(writableTokenRelStore.get(spenderId, fungibleTokenId)).isNull();
        assertThat(writableTokenRelStore.get(spenderId, nonFungibleTokenId)).isNull();
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .accountId(ownerId)
                .tokenId(nonFungibleTokenId)
                .build());
        final var modifiedConfiguration = HederaTestConfigBuilder.create()
                .withValue("entities.unlimitedAutoAssociationsEnabled", true)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(modifiedConfiguration);
        given(handleContext.savepointStack()).willReturn(stack);

        AssertionsForClassTypes.assertThatThrownBy(() -> subjectNFTWithApproval.doIn(transferContext))
                .isInstanceOf(HandleException.class);
    }

    void givenValidTxn() {
        txn = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjustFrom(ownerId, -1_000, false))
                        .accountAmounts(adjustFrom(spenderId, +1_000, false))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .transfers(List.of(
                                        adjustFrom(ownerId, -1_000, false), adjustFrom(spenderId, +1_000, false)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWith(ownerId, spenderId, 1, false))
                                .build())
                .build();
        txnWithApproval = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjustFrom(ownerId, -1_000, true))
                        .accountAmounts(adjustFrom(spenderId, -1_000, true))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(fungibleTokenId)
                        .transfers(List.of(adjustFrom(ownerId, -1_000, true), adjustFrom(spenderId, -1_000, true)))
                        .build())
                .build();
        txnNFTWithApproval = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjustFrom(ownerId, -1_000, true))
                        .accountAmounts(adjustFrom(spenderId, -1_000, true))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(nonFungibleTokenId)
                        .nftTransfers(nftTransferWith(ownerId, spenderId, 1, true))
                        .build())
                .build();
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        lenient()
                .when(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .thenReturn(ResponseCodeEnum.OK);
        given(handleContext.savepointStack()).willReturn(stack);
    }

    private AccountAmount adjustFrom(AccountID account, long amount, boolean isApproval) {
        return AccountAmount.newBuilder()
                .accountID(account)
                .amount(amount)
                .isApproval(isApproval)
                .build();
    }

    private NftTransfer nftTransferWith(AccountID from, AccountID to, long serialNo, boolean isApproval) {
        return NftTransfer.newBuilder()
                .senderAccountID(from)
                .receiverAccountID(to)
                .serialNumber(serialNo)
                .isApproval(isApproval)
                .build();
    }
}
