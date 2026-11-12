// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.address_0x167;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_FEES_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_FEES_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_FEES_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_FEES_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_FEES_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_FEES_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.customFeesAssertions;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.nftAssertions;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.tokenAssertions;
import static org.assertj.core.api.Assertions.assertThatList;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.AddressIdConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateDecoderTest {

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private AddressIdConverter addressIdConverter;

    private CreateDecoder subject;

    @BeforeEach
    void setUp() {
        subject = new CreateDecoder();
        given(addressIdConverter.convert(any())).willReturn(SENDER_ID);
    }

    @Test
    void decodeCreateTokenV1() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V1
                .encodeCall(CREATE_FUNGIBLE_V1_TUPLE)
                .array();
        final var transactionWrapper =
                subject.decodeCreateFungibleTokenV1(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
    }

    @Test
    void decodeCreateTokenV2() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V2
                .encodeCall(CREATE_FUNGIBLE_V2_TUPLE)
                .array();
        final var transactionWrapper =
                subject.decodeCreateFungibleTokenV2(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
    }

    @Test
    void decodeCreateTokenV3() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3
                .encodeCall(CREATE_FUNGIBLE_V3_TUPLE)
                .array();
        final var transactionWrapper =
                subject.decodeCreateFungibleTokenV3(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
    }

    @Test
    void decodeCreateTokenWithCustomFeesV1() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1
                .encodeCall(CREATE_FUNGIBLE_WITH_FEES_V1_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateFungibleTokenWithCustomFeesV1(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
    }

    @Test
    void decodeCreateTokenWithCustomFeesV2() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2
                .encodeCall(CREATE_FUNGIBLE_WITH_FEES_V2_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateFungibleTokenWithCustomFeesV2(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
    }

    @Test
    void decodeCreateTokenWithCustomFeesV3() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3
                .encodeCall(CREATE_FUNGIBLE_WITH_FEES_V3_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateFungibleTokenWithCustomFeesV3(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
    }

    @Test
    void decodeCreateNonFungibleV1() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V1
                .encodeCall(CREATE_NON_FUNGIBLE_V1_TUPLE)
                .array();
        final var transactionWrapper =
                subject.decodeCreateNonFungibleV1(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
    }

    @Test
    void decodeCreateNonFungibleV2() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V2
                .encodeCall(CREATE_NON_FUNGIBLE_V2_TUPLE)
                .array();
        final var transactionWrapper =
                subject.decodeCreateNonFungibleV2(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
    }

    @Test
    void decodeCreateNonFungibleV3() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V3
                .encodeCall(CREATE_NON_FUNGIBLE_V3_TUPLE)
                .array();
        final var transactionWrapper =
                subject.decodeCreateNonFungibleV3(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
    }

    @Test
    void decodeCreateNonFungibleWithCustomFeesV1() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_FEES_V1_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateNonFungibleWithCustomFeesV1(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
    }

    @Test
    void decodeCreateNonFungibleWithCustomFeesV2() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_FEES_V2_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateNonFungibleWithCustomFeesV2(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
    }

    @Test
    void decodeCreateNonFungibleWithCustomFeesV3() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_FEES_V3_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateNonFungibleWithCustomFeesV3(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
    }
}
