// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update.address_0x167;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateExpiryTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator;
import java.time.Instant;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateDecoderTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    protected HederaNativeOperations nativeOperations;

    private final UpdateDecoder subject = new UpdateDecoder();
    private final String newName = "NEW NAME";
    private static final long EXPIRY_TIMESTAMP = Instant.now().plusSeconds(3600).toEpochMilli() / 1000;
    private static final long AUTO_RENEW_PERIOD = 8_000_000L;
    private final Tuple expiry = Tuple.of(EXPIRY_TIMESTAMP, OWNER_HEADLONG_ADDRESS, AUTO_RENEW_PERIOD);
    private final Tuple hederaToken = Tuple.from(
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
            expiry);

    @Nested
    class UpdateTokenInfoAndExpiry {
        @BeforeEach
        void setUp() {
            given(attempt.addressIdConverter()).willReturn(addressIdConverter);
            given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
        }

        @Test
        void updateV1Works() {
            final var encoded = Bytes.wrapByteBuffer(UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V1.encodeCallWithArgs(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken));
            given(attempt.input()).willReturn(encoded);
            given(attempt.nativeOperations()).willReturn(nativeOperations);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

            final var body = subject.decodeTokenUpdateV1(attempt);
            final var tokenUpdate = body.tokenUpdateOrThrow();
            assertEquals(tokenUpdate.name(), newName);
        }

        @Test
        void updateV2Works() {
            final var encoded = Bytes.wrapByteBuffer(UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V2.encodeCallWithArgs(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken));
            given(attempt.input()).willReturn(encoded);
            given(attempt.nativeOperations()).willReturn(nativeOperations);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

            final var body = subject.decodeTokenUpdateV2(attempt);
            final var tokenUpdate = body.tokenUpdateOrThrow();
            assertEquals(tokenUpdate.name(), newName);
        }

        @Test
        void updateV3Works() {
            final var encoded = Bytes.wrapByteBuffer(UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3.encodeCallWithArgs(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken));
            given(attempt.input()).willReturn(encoded);
            given(attempt.nativeOperations()).willReturn(nativeOperations);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

            final var body = subject.decodeTokenUpdateV3(attempt);
            final var tokenUpdate = body.tokenUpdateOrThrow();
            assertEquals(tokenUpdate.name(), newName);
        }

        @Test
        void updateExpiryV1Works() {
            final var encoded =
                    Bytes.wrapByteBuffer(UpdateExpiryTranslator.UPDATE_TOKEN_EXPIRY_INFO_V1.encodeCallWithArgs(
                            FUNGIBLE_TOKEN_HEADLONG_ADDRESS, expiry));
            given(attempt.input()).willReturn(encoded);
            given(attempt.nativeOperations()).willReturn(nativeOperations);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

            final var body = subject.decodeTokenUpdateExpiryV1(attempt);
            final var tokenUpdate = body.tokenUpdateOrThrow();

            assertNotNull(tokenUpdate.expiry());
            assertNotNull(tokenUpdate.autoRenewPeriod());

            assertEquals(EXPIRY_TIMESTAMP, tokenUpdate.expiry().seconds());
            assertEquals(AUTO_RENEW_PERIOD, tokenUpdate.autoRenewPeriod().seconds());
            assertEquals(OWNER_ID, tokenUpdate.autoRenewAccount());
        }

        @Test
        void updateExpiryV2Works() {
            final var encoded =
                    Bytes.wrapByteBuffer(UpdateExpiryTranslator.UPDATE_TOKEN_EXPIRY_INFO_V2.encodeCallWithArgs(
                            FUNGIBLE_TOKEN_HEADLONG_ADDRESS, expiry));
            given(attempt.input()).willReturn(encoded);
            given(attempt.nativeOperations()).willReturn(nativeOperations);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

            final var body = subject.decodeTokenUpdateExpiryV2(attempt);
            final var tokenUpdate = body.tokenUpdateOrThrow();

            assertNotNull(tokenUpdate.expiry());
            assertNotNull(tokenUpdate.autoRenewPeriod());

            assertEquals(EXPIRY_TIMESTAMP, tokenUpdate.expiry().seconds());
            assertEquals(AUTO_RENEW_PERIOD, tokenUpdate.autoRenewPeriod().seconds());
            assertEquals(OWNER_ID, tokenUpdate.autoRenewAccount());
        }
    }
}
