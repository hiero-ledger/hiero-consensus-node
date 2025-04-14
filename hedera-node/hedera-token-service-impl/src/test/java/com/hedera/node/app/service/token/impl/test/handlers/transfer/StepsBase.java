// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWithAllowance;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWithAllowance;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenAirdropTransactionBody;
import com.hedera.hapi.node.token.TokenClaimAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustFungibleTokenChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustHbarChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.NFTOwnersChangeStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.service.token.records.TokenAirdropStreamBuilder;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.DispatchHandleContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Provides common setup for transfer handler tests.
 */
@ExtendWith(MockitoExtension.class)
public class StepsBase extends CryptoTokenHandlerTestBase {
    protected static final Configuration configuration = HederaTestConfigBuilder.createConfig();

    @Mock
    protected CryptoTransferStreamBuilder xferRecordBuilder;

    @Mock
    protected CryptoCreateStreamBuilder cryptoCreateRecordBuilder;

    @Mock
    protected TokenAirdropStreamBuilder tokenAirdropRecordBuilder;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected ConfigProvider configProvider;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected DispatchHandleContext handleContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected HandleContext.SavepointStack stack;

    @Mock
    protected ExpiryValidator expiryValidator;

    @Mock
    protected KeyVerifier keyVerifier;

    protected EnsureAliasesStep ensureAliasesStep;
    protected ReplaceAliasesWithIDsInOp replaceAliasesWithIDsInOp;
    protected AssociateTokenRecipientsStep associateTokenRecepientsStep;
    protected NFTOwnersChangeStep changeNFTOwnersStep;
    protected AdjustHbarChangesStep adjustHbarChangesStep;
    protected AdjustFungibleTokenChangesStep adjustFungibleTokenChangesStep;
    protected CryptoTransferTransactionBody body;
    protected TokenAirdropTransactionBody airdropBody;
    protected TokenClaimAirdropTransactionBody claimAirdropBody;
    protected TransactionBody txn;
    protected TransferContextImpl transferContext;

    @BeforeEach
    public void setUp() {
        baseInternalSetUp(true);
    }

    protected void baseInternalSetUp(final boolean prepopulateReceiverIds) {
        super.handlerTestBaseInternalSetUp(prepopulateReceiverIds);
        refreshWritableStores();
        given(handleContext.savepointStack()).willReturn(stack);
    }

    protected final AccountID unknownAliasedId =
            AccountID.newBuilder().alias(ecKeyAlias.value()).build();
    protected final AccountID unknownAliasedId1 =
            AccountID.newBuilder().alias(edKeyAlias.value()).build();

    public static final Key AN_ED25519_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0101010101010101010101010101010101010101010101010101010101010101"))
            .build();
    protected static final ProtoBytes edKeyAlias = new ProtoBytes(Key.PROTOBUF.toBytes(AN_ED25519_KEY));
    protected static final byte[] ecdsaKeyBytes =
            Hex.decode("3a21033a514176466fa815ed481ffad09110a2d344f6c9b78c1d14afc351c3a51be33d");
    protected static final ProtoBytes ecKeyAlias = new ProtoBytes(Bytes.wrap(ecdsaKeyBytes));

    protected static final byte[] evmAddress = unhex(idFactory.hexLongZero(3));
    protected static final ProtoBytes mirrorAlias = new ProtoBytes(Bytes.wrap(evmAddress));

    protected TransactionBody asTxn(final CryptoTransferTransactionBody body, final AccountID payerId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .cryptoTransfer(body)
                .build();
    }

    protected TransactionBody asAirdropTxn(final TokenAirdropTransactionBody body, final AccountID payerId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .tokenAirdrop(body)
                .build();
    }

    protected TransactionBody asClaimAirdropTxn(final TokenClaimAirdropTransactionBody body, final AccountID payerId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .tokenClaimAirdrop(body)
                .build();
    }

    protected void givenTxn() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .expectedDecimals(1000)
                                .transfers(List.of(aaWith(ownerId, -1_000), aaWith(unknownAliasedId1, +1_000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .expectedDecimals(1000)
                                .nftTransfers(nftTransferWith(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        givenTxn(body, payerId);
    }

    protected void givenTxnWithAllowances() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWithAllowance(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .expectedDecimals(1000)
                                .token(fungibleTokenId)
                                .transfers(List.of(aaWithAllowance(ownerId, -1_000), aaWith(unknownAliasedId1, +1_000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWithAllowance(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        givenTxn(body, spenderId);
    }

    protected void givenTxn(CryptoTransferTransactionBody txnBody, AccountID payerId) {
        body = txnBody;
        txn = asTxn(body, payerId);
        given(handleContext.payer()).willReturn(payerId);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.dispatchComputeFees(any(), any(), any())).willReturn(new Fees(1l, 2l, 3l));
        transferContext = new TransferContextImpl(handleContext);
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
    }

    protected void givenAutoCreationDispatchEffects() {
        givenAutoCreationDispatchEffects(spenderId);
    }

    protected void givenAutoCreationDispatchEffects(AccountID syntheticPayer) {
        given(handleContext.dispatch(
                        argThat(options -> options.streamBuilderType().equals(CryptoCreateStreamBuilder.class)
                                && syntheticPayer.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy = writableAccountStore
                            .get(hbarReceiverId)
                            .copyBuilder()
                            .alias(ecKeyAlias.value())
                            .build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy = writableAccountStore
                            .get(tokenReceiverId)
                            .copyBuilder()
                            .alias(edKeyAlias.value())
                            .build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(0L, 0L, tokenReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(stack.getBaseBuilder(CryptoCreateStreamBuilder.class)).willReturn(cryptoCreateRecordBuilder);
        given(stack.getBaseBuilder(CryptoTransferStreamBuilder.class)).willReturn(xferRecordBuilder);
        given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(xferRecordBuilder);
    }

    protected void givenAirdropTxn() {
        givenAirdropTxn(false, ownerId, AirDropTransferType.TOKEN_AND_NFT_AIRDROP);
    }

    protected void givenAirdropTxn(boolean isReceiverAssociated, AccountID senderId, AirDropTransferType transferType) {
        var receiver = tokenReceiverNoAssociationId;
        if (isReceiverAssociated) {
            receiver = tokenReceiverId;
        }
        List<TokenTransferList> tokenTransferLists = new ArrayList<>();

        TokenTransferList fundgibleTokenTransferList = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .expectedDecimals(1000)
                .transfers(List.of(aaWith(senderId, -1_000), aaWith(receiver, +1_000)))
                .build();
        TokenTransferList nonFungibleTokenTransferList = TokenTransferList.newBuilder()
                .token(nonFungibleTokenId)
                .expectedDecimals(1000)
                .nftTransfers(nftTransferWith(senderId, receiver, 1))
                .build();
        final var tokenAirdropTransactionBody = TokenAirdropTransactionBody.newBuilder();
        if (transferType == AirDropTransferType.TOKEN_AIRDROP
                || transferType == AirDropTransferType.TOKEN_AND_NFT_AIRDROP) {
            tokenTransferLists.add(fundgibleTokenTransferList);
        }
        if (transferType == AirDropTransferType.NFT_AIRDROP
                || transferType == AirDropTransferType.TOKEN_AND_NFT_AIRDROP) {
            tokenTransferLists.add(nonFungibleTokenTransferList);
        }
        tokenAirdropTransactionBody.tokenTransfers(tokenTransferLists);
        airdropBody = tokenAirdropTransactionBody.build();
        givenAirdropTxn(airdropBody, payerId);
    }

    protected void givenAirdropTxn(TokenAirdropTransactionBody txnBody, AccountID payerId) {
        airdropBody = txnBody;
        txn = asAirdropTxn(airdropBody, payerId);
        given(handleContext.payer()).willReturn(payerId);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.dispatch(any())).willReturn(tokenAirdropRecordBuilder);
        given(handleContext.dispatchComputeFees(any(), any(), any())).willReturn(new Fees(1L, 2L, 3L));
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
    }

    protected void givenClaimAirdrop(List<PendingAirdropId> airdrops) {
        claimAirdropBody = TokenClaimAirdropTransactionBody.newBuilder()
                .pendingAirdrops(airdrops)
                .build();
        txn = asClaimAirdropTxn(claimAirdropBody, payerId);
        given(handleContext.payer()).willReturn(payerId);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.dispatchComputeFees(any(), any(), any())).willReturn(new Fees(1L, 2L, 3L));
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
    }
}
