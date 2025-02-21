// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.AutoAccountCreator;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AutoAccountCreatorTest extends StepsBase {
    private AutoAccountCreator subject;

    @BeforeEach
    public void setUp() {
        accountCreatorInternalSetup(true);
    }

    private void accountCreatorInternalSetup(final boolean prepopulateReceiverIds) {
        super.baseInternalSetUp(prepopulateReceiverIds);
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        givenTxn();

        transferContext = new TransferContextImpl(handleContext);
        subject = new AutoAccountCreator(handleContext);
    }

    @Test
    void refusesToCreateBeyondMaxNumber() {
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("accounts.maxNumber", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        transferContext = new TransferContextImpl(handleContext);
        final var aliasBytes = alias.alias();
        assertThatThrownBy(() -> subject.create(aliasBytes, 0))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED));
    }

    @Test
    // TODO: In end to end tests need to validate other fields set correctly on auto created accounts
    void happyPathECKeyAliasWorks() {
        accountCreatorInternalSetup(false);
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.putAndIncrementCount(copy);
                    writableAccountStore.putAndIncrementCountAlias(ecKeyAlias.value(), asAccount(0L, 0L, hbarReceiver));
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        assertThat(writableAccountStore.sizeOfAliasesState()).isEqualTo(2);
        assertThat(writableAccountStore.modifiedAccountsInState()).isEmpty();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNull();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, tokenReceiver))).isNull();
        assertThat(writableAliases.get(ecKeyAlias)).isNull();

        subject.create(ecKeyAlias.value(), 0);

        assertThat(writableAccountStore.modifiedAliasesInState()).hasSize(1);
        assertThat(writableAccountStore.modifiedAccountsInState()).hasSize(1);
        assertThat(writableAccountStore.sizeOfAliasesState()).isEqualTo(3);
        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNotNull();
        assertThat(writableAliases.get(ecKeyAlias).accountNum()).isEqualTo(hbarReceiver);
    }

    @Test
    // TODO: In end to end tests need to validate other fields set correctly on auto created accounts
    void happyPathEDKeyAliasWorks() {
        accountCreatorInternalSetup(false);
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.putAndIncrementCount(copy);
                    writableAccountStore.putAndIncrementCountAlias(edKeyAlias.value(), asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        assertThat(writableAccountStore.sizeOfAliasesState()).isEqualTo(2);
        assertThat(writableAccountStore.modifiedAccountsInState()).isEmpty();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNull();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, tokenReceiver))).isNull();
        assertThat(writableAliases.get(edKeyAlias)).isNull();

        subject.create(edKeyAlias.value(), 0);

        assertThat(writableAccountStore.modifiedAliasesInState()).hasSize(1);
        assertThat(writableAccountStore.modifiedAccountsInState()).hasSize(1);
        assertThat(writableAccountStore.sizeOfAliasesState()).isEqualTo(3);
        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNotNull();
        assertThat(writableAliases.get(edKeyAlias).accountNum()).isEqualTo(hbarReceiver);
    }

    @Test
    // TODO: In end to end tests need to validate other fields set on auto created accounts
    void happyPathWithHollowAccountAliasInHbarTransfersWorks() {
        accountCreatorInternalSetup(false);
        final var address = new ProtoBytes(Bytes.wrap(evmAddress));
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.putAndIncrementCount(copy);
                    writableAliases.put(address, asAccount(0L, 0L, hbarReceiver));
                    writableAccountStore.putAndIncrementCountAlias(address.value(), asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        assertThat(writableAccountStore.sizeOfAliasesState()).isEqualTo(2);
        assertThat(writableAccountStore.modifiedAccountsInState()).isEmpty();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNull();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, tokenReceiver))).isNull();
        assertThat(writableAliases.get(address)).isNull();

        subject.create(address.value(), 0);

        assertThat(writableAccountStore.modifiedAliasesInState()).hasSize(1);
        assertThat(writableAccountStore.modifiedAccountsInState()).hasSize(1);
        assertThat(writableAccountStore.sizeOfAliasesState()).isEqualTo(3);
        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNotNull();
        assertThat(writableAliases.get(address).accountNum()).isEqualTo(hbarReceiver);
    }
}
