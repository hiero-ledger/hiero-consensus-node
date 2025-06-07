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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateDecoder;
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
        final TransactionBody transaction = subject.decodeCreateFungibleTokenWithMetadata(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    @Test
    void decodeCreateTokenWithMetaAndCustomFees() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                .encodeCall(CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE)
                .array();
        final TransactionBody transaction = subject.decodeCreateFungibleTokenWithMetadataAndCustomFees(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    @Test
    void decodeCreateNonFungibleWithMetadata() {
        byte[] inputBytes = CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_META_TUPLE)
                .array();
        final TransactionBody transaction = subject.decodeCreateNonFungibleWithMetadata(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    @Test
    void decodeCreateNonFungibleWithMetadataAndCustomFees() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        byte[] inputBytes = CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE)
                .array();
        final TransactionBody transaction = subject.decodeCreateNonFungibleWithMetadataAndCustomFees(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }
}
