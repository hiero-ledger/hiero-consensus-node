// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_META_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_META_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.customFeesAssertions;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.nftAssertions;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.tokenAssertions;
import static org.assertj.core.api.Assertions.assertThatList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.utils.AddressIdConverter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
    void decodeCreateTokenWithMeta() {
        byte[] inputBytes = CREATE_FUNGIBLE_TOKEN_WITH_METADATA
                .encodeCall(CREATE_FUNGIBLE_WITH_META_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateFungibleTokenWithMetadata(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
        assertNotNull(transaction.tokenCreationOrThrow().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreationOrThrow().metadata());
    }

    @Test
    void decodeCreateTokenWithMetaAndCustomFees() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                .encodeCall(CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateFungibleTokenWithMetadataAndCustomFees(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
        assertNotNull(transaction.tokenCreationOrThrow().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreationOrThrow().metadata());
    }

    @Test
    void decodeCreateNonFungibleWithMetadata() {
        byte[] inputBytes = CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_META_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateNonFungibleWithMetadata(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isEmpty();
        assertNotNull(transaction.tokenCreationOrThrow().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreationOrThrow().metadata());
    }

    @Test
    void decodeCreateNonFungibleWithMetadataAndCustomFees() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE)
                .array();
        final var transactionWrapper = subject.decodeCreateNonFungibleWithMetadataAndCustomFees(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        final var transaction = transactionWrapper.body();
        assertNotNull(transaction);
        assertNull(transactionWrapper.preemptingStatus());
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreationOrThrow().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreationOrThrow().customFees());
        assertNotNull(transaction.tokenCreationOrThrow().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreationOrThrow().metadata());
    }
}
