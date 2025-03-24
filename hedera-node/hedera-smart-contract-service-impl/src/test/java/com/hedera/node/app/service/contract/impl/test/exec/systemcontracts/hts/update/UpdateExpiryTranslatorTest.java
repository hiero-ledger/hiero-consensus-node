// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateExpiryTranslator.UPDATE_TOKEN_EXPIRY_INFO_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateExpiryTranslator.UPDATE_TOKEN_EXPIRY_INFO_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateExpiryTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.time.Instant;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class UpdateExpiryTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractMetrics contractMetrics;

    private UpdateExpiryTranslator subject;

    private final UpdateDecoder decoder = new UpdateDecoder();

    private static final long EXPIRY_TIMESTAMP = Instant.now().plusSeconds(3600).toEpochMilli() / 1000;
    private static final long AUTO_RENEW_PERIOD = 8_000_000L;
    private static final Tuple expiry = Tuple.of(EXPIRY_TIMESTAMP, OWNER_HEADLONG_ADDRESS, AUTO_RENEW_PERIOD);

    @BeforeEach
    void setUp() {
        subject = new UpdateExpiryTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesUpdateExpiryV1Test() {
        attempt = createHtsCallAttempt(UPDATE_TOKEN_EXPIRY_INFO_V1, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesUpdateExpiryV2Test() {
        attempt = createHtsCallAttempt(UPDATE_TOKEN_EXPIRY_INFO_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsIfIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_KEYS_FUNCTION, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromUpdateTest() {
        final Tuple tuple = Tuple.of(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, expiry);
        final Bytes inputBytes = Bytes.wrapByteBuffer(UPDATE_TOKEN_EXPIRY_INFO_V1.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.selector()).willReturn(UPDATE_TOKEN_EXPIRY_INFO_V1.selector());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(NON_SYSTEM_ACCOUNT_ID);
        given(addressIdConverter.convert(any())).willReturn(NON_SYSTEM_ACCOUNT_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(DispatchForResponseCodeHtsCall.class);
    }
}
