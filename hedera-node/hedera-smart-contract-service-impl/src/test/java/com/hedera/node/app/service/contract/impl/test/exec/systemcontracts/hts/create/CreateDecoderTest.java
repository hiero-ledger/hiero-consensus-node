/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create;

import static com.hedera.hapi.node.base.TokenSupplyType.FINITE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_FEES_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_FEES_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_FEES_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_FUNGIBLE_WITH_META_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_FEES_V1_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_FEES_V2_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_FEES_V3_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.CreateTestHelper.CREATE_NON_FUNGIBLE_WITH_META_TUPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateTranslator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
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

    private com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateDecoder
            subject0x167;
    private com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateDecoder
            subject0x16c;

    @BeforeEach
    void setUp() {
        subject0x167 = new CreateDecoder();
        subject0x16c =
                new com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c
                        .CreateDecoder();
        given(addressIdConverter.convert(any())).willReturn(SENDER_ID);
    }

    @Test
    void decodeCreateTokenV1() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V1
                .encodeCall(CREATE_FUNGIBLE_V1_TUPLE)
                .array();
        final TransactionBody transaction =
                subject0x167.decodeCreateFungibleTokenV1(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
    }

    @Test
    void decodeCreateTokenV2() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V2
                .encodeCall(CREATE_FUNGIBLE_V2_TUPLE)
                .array();
        final TransactionBody transaction =
                subject0x167.decodeCreateFungibleTokenV2(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
    }

    @Test
    void decodeCreateTokenV3() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3
                .encodeCall(CREATE_FUNGIBLE_V3_TUPLE)
                .array();
        final TransactionBody transaction =
                subject0x167.decodeCreateFungibleTokenV3(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
    }

    @Test
    void decodeCreateTokenWithMeta() {
        byte[] inputBytes = CREATE_FUNGIBLE_TOKEN_WITH_METADATA
                .encodeCall(CREATE_FUNGIBLE_WITH_META_TUPLE)
                .array();
        final TransactionBody transaction = subject0x16c.decodeCreateFungibleTokenWithMetadata(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    @Test
    void decodeCreateTokenWithCustomFeesV1() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1
                .encodeCall(CREATE_FUNGIBLE_WITH_FEES_V1_TUPLE)
                .array();
        final TransactionBody transaction = subject0x167.decodeCreateFungibleTokenWithCustomFeesV1(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
    }

    @Test
    void decodeCreateTokenWithCustomFeesV2() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2
                .encodeCall(CREATE_FUNGIBLE_WITH_FEES_V2_TUPLE)
                .array();
        final TransactionBody transaction = subject0x167.decodeCreateFungibleTokenWithCustomFeesV2(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
    }

    @Test
    void decodeCreateTokenWithCustomFeesV3() {
        byte[] inputBytes = CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3
                .encodeCall(CREATE_FUNGIBLE_WITH_FEES_V3_TUPLE)
                .array();
        final TransactionBody transaction = subject0x167.decodeCreateFungibleTokenWithCustomFeesV3(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
    }

    @Test
    void decodeCreateTokenWithMetaAndCustomFees() {
        byte[] inputBytes = CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                .encodeCall(CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE)
                .array();
        final TransactionBody transaction = subject0x16c.decodeCreateFungibleTokenWithMetadataAndCustomFees(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        tokenAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    @Test
    void decodeCreateNonFungibleV1() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V1
                .encodeCall(CREATE_NON_FUNGIBLE_V1_TUPLE)
                .array();
        final TransactionBody transaction =
                subject0x167.decodeCreateNonFungibleV1(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
    }

    @Test
    void decodeCreateNonFungibleV2() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V2
                .encodeCall(CREATE_NON_FUNGIBLE_V2_TUPLE)
                .array();
        final TransactionBody transaction =
                subject0x167.decodeCreateNonFungibleV2(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
    }

    @Test
    void decodeCreateNonFungibleV3() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V3
                .encodeCall(CREATE_NON_FUNGIBLE_V3_TUPLE)
                .array();
        final TransactionBody transaction =
                subject0x167.decodeCreateNonFungibleV3(inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
    }

    @Test
    void decodeCreateNonFungibleWithMetadata() {
        byte[] inputBytes = CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_META_TUPLE)
                .array();
        final TransactionBody transaction = subject0x16c.decodeCreateNonFungibleWithMetadata(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isEmpty();
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    @Test
    void decodeCreateNonFungibleWithCustomFeesV1() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_FEES_V1_TUPLE)
                .array();
        final TransactionBody transaction = subject0x167.decodeCreateNonFungibleWithCustomFeesV1(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
    }

    @Test
    void decodeCreateNonFungibleWithCustomFeesV2() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_FEES_V2_TUPLE)
                .array();
        final TransactionBody transaction = subject0x167.decodeCreateNonFungibleWithCustomFeesV2(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
    }

    @Test
    void decodeCreateNonFungibleWithCustomFeesV3() {
        byte[] inputBytes = CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_FEES_V3_TUPLE)
                .array();
        final TransactionBody transaction = subject0x167.decodeCreateNonFungibleWithCustomFeesV3(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
    }

    @Test
    void decodeCreateNonFungibleWithMetadataAndCustomFees() {
        byte[] inputBytes = CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                .encodeCall(CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE)
                .array();
        final TransactionBody transaction = subject0x16c.decodeCreateNonFungibleWithMetadataAndCustomFees(
                inputBytes, SENDER_ID, nativeOperations, addressIdConverter);
        nftAssertions(transaction);
        assertThatList(transaction.tokenCreation().customFees()).isNotEmpty();
        customFeesAssertions(transaction.tokenCreation().customFees());
        assertNotNull(transaction.tokenCreation().metadata());
        assertEquals(Bytes.wrap("metadata"), transaction.tokenCreation().metadata());
    }

    private void tokenAssertions(final TransactionBody transaction) {
        assertThat(transaction).isNotNull();
        final var tokenCreation = transaction.tokenCreation();
        assertNotNull(tokenCreation);
        assertEquals(10L, tokenCreation.initialSupply());
        assertEquals(5, tokenCreation.decimals());
        assertEquals("name", tokenCreation.name());
        assertEquals("symbol", tokenCreation.symbol());
        assertEquals(SENDER_ID, tokenCreation.treasury());
        assertEquals("memo", tokenCreation.memo());
        assertFalse(tokenCreation.freezeDefault());
        assertEquals(1000L, tokenCreation.maxSupply());
        assertEquals(FINITE, tokenCreation.supplyType());
        assertEquals(TokenType.FUNGIBLE_COMMON, tokenCreation.tokenType());
    }

    private void nftAssertions(final TransactionBody transaction) {
        assertThat(transaction).isNotNull();
        final var tokenCreation = transaction.tokenCreation();
        assertNotNull(tokenCreation);
        assertEquals("name", tokenCreation.name());
        assertEquals("symbol", tokenCreation.symbol());
        assertEquals(SENDER_ID, tokenCreation.treasury());
        assertEquals("memo", tokenCreation.memo());
        assertFalse(tokenCreation.freezeDefault());
        assertEquals(0L, tokenCreation.initialSupply());
        assertEquals(TokenType.NON_FUNGIBLE_UNIQUE, tokenCreation.tokenType());
    }

    private void customFeesAssertions(final List<CustomFee> customFees) {
        assertThatList(customFees).size().isEqualTo(2);
        final var customFee1 = customFees.get(0);
        assertTrue(customFee1.hasFixedFee());
        assertEquals(2L, customFee1.fixedFee().amount());
        assertNull(customFee1.fixedFee().denominatingTokenId());
        assertEquals(SENDER_ID, customFee1.feeCollectorAccountId());
        final var customFee2 = customFees.get(1);
        assertTrue(customFee2.hasFixedFee());
        assertEquals(3L, customFee2.fixedFee().amount());
        assertEquals(FUNGIBLE_TOKEN_ID, customFee2.fixedFee().denominatingTokenId());
        assertEquals(SENDER_ID, customFee2.feeCollectorAccountId());
    }
}
