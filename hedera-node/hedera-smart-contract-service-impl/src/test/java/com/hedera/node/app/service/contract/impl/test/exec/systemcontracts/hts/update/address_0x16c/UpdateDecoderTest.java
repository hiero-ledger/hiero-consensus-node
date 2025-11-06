// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateNFTsMetadataTranslator;
import java.time.Instant;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateDecoderTest {

    @Mock
    private HtsCallAttempt attempt;

    private UpdateDecoder subject = new UpdateDecoder();

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    protected HederaNativeOperations nativeOperations;

    private final String newName = "NEW NAME";
    private final String metadata = "LionTigerBear";
    private static final long EXPIRY_TIMESTAMP = Instant.now().plusSeconds(3600).toEpochMilli() / 1000;
    private static final long AUTO_RENEW_PERIOD = 8_000_000L;
    private final Tuple expiry = Tuple.of(EXPIRY_TIMESTAMP, OWNER_HEADLONG_ADDRESS, AUTO_RENEW_PERIOD);
    private final Tuple hederaTokenWithMetadata = Tuple.from(
            newName,
            "symbol",
            OWNER_HEADLONG_ADDRESS,
            "memo",
            true,
            1000L,
            false,
            // TokenKey
            new Tuple[] {},
            // Expiry
            expiry,
            // Metadata,
            metadata.getBytes());

    @BeforeEach
    void setUp() {
        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
        lenient().when(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).thenReturn(OWNER_ID);
    }

    @Test
    void updateWithMetadataWorks() {
        final var encoded = Bytes.wrapByteBuffer(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA.encodeCallWithArgs(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaTokenWithMetadata));
        given(attempt.input()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var body = subject.decodeTokenUpdateWithMetadata(attempt);
        final var tokenUpdate = body.tokenUpdateOrThrow();
        assertEquals(tokenUpdate.metadata().asUtf8String(), metadata);
    }

    @Test
    void updateNFTsMetadataWorks() {
        final var encoded = Bytes.wrapByteBuffer(UpdateNFTsMetadataTranslator.UPDATE_NFTs_METADATA.encodeCallWithArgs(
                NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, new long[] {1, 2, 3}, "Jerry".getBytes()));
        given(attempt.input()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var body = subject.decodeUpdateNFTsMetadata(attempt);
        final var tokenUpdate = requireNonNull(body).tokenUpdateNftsOrThrow();

        assertNotNull(tokenUpdate.metadata());
        assertEquals("Jerry", tokenUpdate.metadata().asUtf8String());
        assertEquals(3, tokenUpdate.serialNumbers().size());
    }
}
