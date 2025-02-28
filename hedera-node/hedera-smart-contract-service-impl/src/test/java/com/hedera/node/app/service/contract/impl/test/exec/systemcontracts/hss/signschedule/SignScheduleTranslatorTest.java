// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.signschedule;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_CONTRACT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.bytesForRedirectScheduleTxn;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHssAttemptWithBytesAndCustomConfig;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHssAttemptWithBytesAndCustomConfigAndDelegatableContractKeys;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHssAttemptWithSelectorAndCustomConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignScheduleTranslatorTest {

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private SystemContractOperations systemContractOperations;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private AccountID payerId;

    @Mock
    private Schedule schedule;

    @Mock
    private ScheduleID scheduleID;

    @Mock
    ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    @Mock
    private Key key;

    @Mock
    private SignatureVerifier signatureVerifier;

    private static final Key ecdsaKey = Key.newBuilder()
            .ecdsaSecp256k1(com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(
                    "02b3c641418e89452cd5202adfd4758f459acb8e364f741fd16cd2db79835d39d2"))
            .build();
    private static final Key ed25519 = Key.newBuilder()
            .ed25519(com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(
                    "02b3c641418e89452cd5202adfd4758f459acb8e364f741fd16cd2db79835d39c5"))
            .build();

    private SignScheduleTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new SignScheduleTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void testMatchesWhenSignScheduleEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSignScheduleEnabled()).willReturn(true);
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                SignScheduleTranslator.SIGN_SCHEDULE_PROXY,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void testFailsMatchesWhenSignScheduleEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSignScheduleEnabled()).willReturn(false);
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                SignScheduleTranslator.SIGN_SCHEDULE_PROXY,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testMatchesWhenAuthorizeScheduleEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractAuthorizeScheduleEnabled()).willReturn(true);
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                SignScheduleTranslator.AUTHORIZE_SCHEDULE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void testFailsMatchesWhenAuthorizeScheduleEnabled() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractAuthorizeScheduleEnabled()).willReturn(false);
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                SignScheduleTranslator.AUTHORIZE_SCHEDULE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testMatchesFailsOnRandomSelector() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractSignScheduleEnabled()).willReturn(true);
        attempt = prepareHssAttemptWithSelectorAndCustomConfig(
                MintTranslator.MINT,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void testAttemptForSignScheduleProxy() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(enhancement.systemOperations()).willReturn(systemContractOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(nativeOperations.getAccount(payerId)).willReturn(SOMEBODY);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, false, nativeOperations))
                .willReturn(verificationStrategy);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when:
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                bytesForRedirectScheduleTxn(
                        SignScheduleTranslator.SIGN_SCHEDULE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // then:
        final var call = subject.callFrom(attempt);

        assertThat(call).isInstanceOf(DispatchForResponseCodeHssCall.class);
    }

    @Test
    void testScheduleIdForSignScheduleProxyEthSender() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(enhancement.systemOperations()).willReturn(systemContractOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(nativeOperations.getAccount(payerId)).willReturn(SOMEBODY);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, false, nativeOperations))
                .willReturn(verificationStrategy);
        given(systemContractOperations.maybeEthSenderKey()).willReturn(key);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when:
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                bytesForRedirectScheduleTxn(
                        SignScheduleTranslator.SIGN_SCHEDULE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // then:
        final var call = subject.callFrom(attempt);

        assertThat(call).isInstanceOf(DispatchForResponseCodeHssCall.class);
    }

    @Test
    void testScheduleIdForWrongSelectorThrows() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when:
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                bytesForRedirectScheduleTxn(MintTranslator.MINT.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // then:
        assertThrows(IllegalStateException.class, () -> subject.callFrom(attempt));
    }

    @Test
    void testAttemptForAuthorizeSchedule() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(nativeOperations.getAccount(payerId)).willReturn(B_CONTRACT);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, false, nativeOperations))
                .willReturn(verificationStrategy);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when:
        final var input = Bytes.wrapByteBuffer(
                SignScheduleTranslator.AUTHORIZE_SCHEDULE.encodeCall(Tuple.singleton(APPROVED_HEADLONG_ADDRESS)));
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                input,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // then:
        final var call = subject.callFrom(attempt);

        assertThat(call).isInstanceOf(DispatchForResponseCodeHssCall.class);
    }

    @Test
    void testScheduleIdForAuthorizeScheduleDelegatableContractKeys() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(nativeOperations.getAccount(payerId)).willReturn(B_CONTRACT);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(payerId);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, true, nativeOperations))
                .willReturn(verificationStrategy);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        // when:
        final var input = Bytes.wrapByteBuffer(
                SignScheduleTranslator.AUTHORIZE_SCHEDULE.encodeCall(Tuple.singleton(APPROVED_HEADLONG_ADDRESS)));
        attempt = prepareHssAttemptWithBytesAndCustomConfigAndDelegatableContractKeys(
                input,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        // then:
        final var call = subject.callFrom(attempt);

        assertThat(call).isInstanceOf(DispatchForResponseCodeHssCall.class);
    }

    @Test
    void testGasRequirement() {
        long expectedGas = 1000L;
        when(gasCalculator.gasRequirement(transactionBody, DispatchType.SCHEDULE_SIGN, payerId))
                .thenReturn(expectedGas);

        long gas = SignScheduleTranslator.gasRequirement(transactionBody, gasCalculator, enhancement, payerId);

        assertEquals(expectedGas, gas);
    }

    @Test
    void testBodyFor() {
        // then:
        final var body = subject.bodyFor(scheduleID);

        assertThat(body.hasScheduleSign()).isTrue();
        assertThat(body.scheduleSignOrThrow().scheduleID()).isEqualTo(scheduleID);
    }

    @Test
    void testScheduleIdForSignScheduleProxy() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                bytesForRedirectScheduleTxn(
                        SignScheduleTranslator.SIGN_SCHEDULE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        final var returnedScheduleId = subject.scheduleIdFor(attempt);
        assertThat(returnedScheduleId).isEqualTo(scheduleID);
    }

    @Test
    void testScheduleIdForAuthorizeSchedule() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);

        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                Bytes.wrapByteBuffer(SignScheduleTranslator.AUTHORIZE_SCHEDULE.encodeCall(
                        Tuple.singleton(APPROVED_HEADLONG_ADDRESS))),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        final var returnedScheduleId = subject.scheduleIdFor(attempt);
        assertThat(returnedScheduleId).isEqualTo(scheduleID);
    }

    @Test
    void testScheduleIdForScheduleService() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);

        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                Bytes.wrapByteBuffer(SignScheduleTranslator.SIGN_SCHEDULE.encodeCall(
                        Tuple.of(APPROVED_HEADLONG_ADDRESS, new byte[0]))),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        final var returnedScheduleId = subject.scheduleIdFor(attempt);
        assertThat(returnedScheduleId).isEqualTo(scheduleID);
    }

    @Test
    void testGetKeysForSignScheduleWhenVerified() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.chainId()).willReturn(296);
        given(signatureVerifier.verifySignature(any(), any(), any(), any(), any()))
                .willReturn(true);

        final var sigMapBytes = getSigMapKnownKeyTypeBytes(296);
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                Bytes.wrapByteBuffer(SignScheduleTranslator.SIGN_SCHEDULE.encodeCall(
                        Tuple.of(APPROVED_HEADLONG_ADDRESS, sigMapBytes.toByteArray()))),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        final var keySet = SignScheduleTranslator.getKeyForSignSchedule(attempt);

        assertThat(keySet).contains(ecdsaKey).contains(ed25519);
    }

    @Test
    void testGetKeysForSignScheduleWhenNotVerified() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.chainId()).willReturn(296);
        given(signatureVerifier.verifySignature(any(), any(), any(), any(), any()))
                .willReturn(false);

        final var sigMapBytes = getSigMapKnownKeyTypeBytes(296);
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                Bytes.wrapByteBuffer(SignScheduleTranslator.SIGN_SCHEDULE.encodeCall(
                        Tuple.of(APPROVED_HEADLONG_ADDRESS, sigMapBytes.toByteArray()))),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        final var keySet = SignScheduleTranslator.getKeyForSignSchedule(attempt);

        assertThat(keySet).isEmpty();
    }

    @Test
    void testGetKeysForSignScheduleThrowsWhenWrongChainId() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.chainId()).willReturn(296);

        final var sigMapBytes = getSigMapKnownKeyTypeBytes(396);
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                Bytes.wrapByteBuffer(SignScheduleTranslator.SIGN_SCHEDULE.encodeCall(
                        Tuple.of(APPROVED_HEADLONG_ADDRESS, sigMapBytes.toByteArray()))),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        assertThrows(HandleException.class, () -> SignScheduleTranslator.getKeyForSignSchedule(attempt));
    }

    @Test
    void testGetKeysForSignScheduleWhenUnknownKeyType() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getSchedule(anyLong())).willReturn(schedule);
        given(schedule.scheduleId()).willReturn(scheduleID);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.chainId()).willReturn(296);

        final var sigMapBytes = getSigMapUnknownKeyTypeBytes();
        attempt = prepareHssAttemptWithBytesAndCustomConfig(
                Bytes.wrapByteBuffer(SignScheduleTranslator.SIGN_SCHEDULE.encodeCall(
                        Tuple.of(APPROVED_HEADLONG_ADDRESS, sigMapBytes.toByteArray()))),
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);

        final var keySet = SignScheduleTranslator.getKeyForSignSchedule(attempt);

        assertThat(keySet).isEmpty();
    }

    private static com.hedera.pbj.runtime.io.buffer.Bytes getSigMapKnownKeyTypeBytes(final int chainId) {
        final var signatureEcdsa = randomBytes(66);

        int v = 35 + (chainId * 2);
        final var ecSig = new byte[66];
        signatureEcdsa[65] = (byte) (v & 0xFF);
        v >>= 8;
        signatureEcdsa[64] = (byte) (v & 0xFF);

        final var signatureEd = randomBytes(64);
        final var sigMap = SignatureMap.newBuilder()
                .sigPair(List.of(
                        SignaturePair.newBuilder()
                                .pubKeyPrefix(ecdsaKey.ecdsaSecp256k1OrThrow())
                                .ecdsaSecp256k1(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(signatureEcdsa))
                                .build(),
                        SignaturePair.newBuilder()
                                .pubKeyPrefix(ed25519.ed25519OrThrow())
                                .ed25519(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(signatureEd))
                                .build()))
                .build();

        return SignatureMap.PROTOBUF.toBytes(sigMap);
    }

    private static com.hedera.pbj.runtime.io.buffer.Bytes getSigMapUnknownKeyTypeBytes() {
        final var signatureEcdsa = randomBytes(64);
        final var sigMap = SignatureMap.newBuilder()
                .sigPair(List.of(SignaturePair.newBuilder()
                        .pubKeyPrefix(ecdsaKey.ecdsaSecp256k1OrThrow())
                        .rsa3072(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(signatureEcdsa))
                        .build()))
                .build();

        return SignatureMap.PROTOBUF.toBytes(sigMap);
    }

    public static byte[] randomBytes(Random r, int length) {
        byte[] ret = new byte[length];
        r.nextBytes(ret);
        return ret;
    }

    public static byte[] randomBytes(int length) {
        return randomBytes(new Random(), length);
    }
}
