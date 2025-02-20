// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_TOKEN_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V3;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelector;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorAndCustomConfig;
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
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.ClassicCreatesCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CreateTranslator}.
 */
@ExtendWith(MockitoExtension.class)
public class CreateTranslatorTest extends CallTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    Configuration configuration;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final CreateDecoder decoder = new CreateDecoder();

    private CreateTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new CreateTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesCreateFungibleTokenV1() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_FUNGIBLE_TOKEN_V1,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenV2() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_FUNGIBLE_TOKEN_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenV3() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_FUNGIBLE_TOKEN_V3,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithMetadata() {
        enableConfig();
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithCustomFeesV1() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithCustomFeesV2() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithCustomFeesV3() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithMetadataAndCustomFees() {
        enableConfig();
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenV1() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_NON_FUNGIBLE_TOKEN_V1,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenV2() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_NON_FUNGIBLE_TOKEN_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenV3() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_NON_FUNGIBLE_TOKEN_V3,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithMetadata() {
        enableConfig();
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithCustomFeesV1() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithCustomFeesV2() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithCustomFeesV3() {
        attempt = prepareHtsAttemptWithSelector(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithMetadataAndCustomFees() {
        enableConfig();
        attempt = prepareHtsAttemptWithSelectorAndCustomConfig(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry,
                configuration);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector() {
        attempt = prepareHtsAttemptWithSelector(
                BURN_TOKEN_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromCreateTokenV1() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_FUNGIBLE_TOKEN_V1.encodeCall(CREATE_FUNGIBLE_V1_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenV2() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_FUNGIBLE_TOKEN_V2.encodeCall(CREATE_FUNGIBLE_V2_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenV3() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_FUNGIBLE_TOKEN_V3.encodeCall(CREATE_FUNGIBLE_V3_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenWithMeta() {
        byte[] inputBytes = Bytes.wrapByteBuffer(
                        CREATE_FUNGIBLE_TOKEN_WITH_METADATA.encodeCall(CREATE_FUNGIBLE_WITH_META_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenWithCustomFeesV1() {
        byte[] inputBytes = Bytes.wrapByteBuffer(
                        CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1.encodeCall(CREATE_FUNGIBLE_WITH_FEES_V1_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenWithCustomFeesV2() {
        byte[] inputBytes = Bytes.wrapByteBuffer(
                        CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2.encodeCall(CREATE_FUNGIBLE_WITH_FEES_V2_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenWithCustomFeesV3() {
        byte[] inputBytes = Bytes.wrapByteBuffer(
                        CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3.encodeCall(CREATE_FUNGIBLE_WITH_FEES_V3_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateTokenWithMetaAndCustomFees() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES.encodeCall(
                        CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftV1() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_V1.encodeCall(CREATE_NON_FUNGIBLE_V1_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftV2() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_V2.encodeCall(CREATE_NON_FUNGIBLE_V2_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftV3() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_V3.encodeCall(CREATE_NON_FUNGIBLE_V3_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftWithMeta() {
        byte[] inputBytes = Bytes.wrapByteBuffer(
                        CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA.encodeCall(CREATE_NON_FUNGIBLE_WITH_META_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftWithCustomFeesV1() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1.encodeCall(
                        CREATE_NON_FUNGIBLE_WITH_FEES_V1_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftWithCustomFeesV2() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2.encodeCall(
                        CREATE_NON_FUNGIBLE_WITH_FEES_V2_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftWithCustomFeesV3() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3.encodeCall(
                        CREATE_NON_FUNGIBLE_WITH_FEES_V3_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    @Test
    void callFromCreateNftWithMetaAndCustomFees() {
        byte[] inputBytes = Bytes.wrapByteBuffer(CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES.encodeCall(
                        CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE))
                .toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        assertThat(subject.callFrom(attempt)).isInstanceOf(ClassicCreatesCall.class);
    }

    private void enableConfig() {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.metadataKeyAndFieldEnabled()).willReturn(true);
    }
}
