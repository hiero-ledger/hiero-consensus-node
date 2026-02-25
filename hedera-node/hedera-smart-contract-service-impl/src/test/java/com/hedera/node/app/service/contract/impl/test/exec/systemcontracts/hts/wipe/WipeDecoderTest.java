// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.wipe;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NUMBERS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NUMBERS_LIST;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class WipeDecoderTest {

    public static final long WIPE_FUNGIBLE_TOKEN_AMOUNT = 30L;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    protected HederaNativeOperations nativeOperations;

    private final WipeDecoder subject = new WipeDecoder();

    @BeforeEach
    void setUp() {
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
    }

    @Test
    void wipeNonFungibleToken_Works() {
        // given
        final var encoded = WipeTranslator.WIPE_NFT
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, OWNER_HEADLONG_ADDRESS, NFT_SERIAL_NUMBERS)
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when
        final var body = subject.decodeWipeNonFungible(attempt);
        final var wipeTokenAccount = body.tokenWipeOrThrow();

        // then
        assertThat(wipeTokenAccount.token()).isEqualTo(FUNGIBLE_TOKEN_ID);
        assertThat(wipeTokenAccount.account()).isEqualTo(OWNER_ID);
        assertThat(wipeTokenAccount.serialNumbers()).isEqualTo(NFT_SERIAL_NUMBERS_LIST);
    }

    @Test
    void wipeFungibleToken_Works() {
        // given
        final var encoded = WipeTranslator.WIPE_FUNGIBLE_V1
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, OWNER_HEADLONG_ADDRESS, WIPE_FUNGIBLE_TOKEN_AMOUNT)
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when
        final var body = subject.decodeWipeFungibleV1(attempt);
        final var wipeTokenAccount = body.tokenWipeOrThrow();

        // then
        assertDecodedTransactionBody(wipeTokenAccount);
    }

    @Test
    void wipeFungibleTokenV2_Works() {
        // given
        final var encoded = WipeTranslator.WIPE_FUNGIBLE_V2
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, OWNER_HEADLONG_ADDRESS, WIPE_FUNGIBLE_TOKEN_AMOUNT)
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when
        final var body = subject.decodeWipeFungibleV2(attempt);
        final var wipeTokenAccount = body.tokenWipeOrThrow();

        // then
        assertDecodedTransactionBody(wipeTokenAccount);
    }

    private static void assertDecodedTransactionBody(final TokenWipeAccountTransactionBody wipeTokenAccount) {
        assertThat(wipeTokenAccount.token()).as("Token ID should match").isEqualTo(FUNGIBLE_TOKEN_ID);
        assertThat(wipeTokenAccount.account()).as("Account ID should match").isEqualTo(OWNER_ID);
        assertThat(wipeTokenAccount.amount()).as("Amount should match").isEqualTo(WIPE_FUNGIBLE_TOKEN_AMOUNT);
    }
}
