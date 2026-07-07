// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.associations;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsTranslator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssociationsDecoderTest {

    private static final long TRANSACTION_MAX_GAS = 15_000_000L;
    private static final long SINGLE_TOKEN_ASSOCIATION_COST = 705_424L;

    @Mock
    protected SystemContractGasCalculator gasCalculator;

    @Mock
    private Configuration configuration;

    private static final ContractsConfig CONFIG_WITH_MAX_GAS = HederaTestConfigBuilder.create()
            .withValue("contracts.maxGasPerTransaction", TRANSACTION_MAX_GAS)
            .getOrCreateConfig()
            .getConfigData(ContractsConfig.class);

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private HederaNativeOperations hederaNativeOperations;

    private final AssociationsDecoder subject = new AssociationsDecoder();

    @Test
    void hrcAssociateWorks() {
        given(attempt.senderId()).willReturn(OWNER_ID);
        given(attempt.redirectTokenId()).willReturn(NON_FUNGIBLE_TOKEN_ID);
        final var body = subject.decodeHrcAssociate(attempt);
        assertAssociationPresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void hrcDissociateWorks() {
        given(attempt.senderId()).willReturn(OWNER_ID);
        given(attempt.redirectTokenId()).willReturn(NON_FUNGIBLE_TOKEN_ID);
        final var body = subject.decodeHrcDissociate(attempt);
        assertDissociationPresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void associateOneWorks() {
        final var encoded = AssociationsTranslator.ASSOCIATE_ONE
                .encodeCallWithArgs(OWNER_HEADLONG_ADDRESS, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                .array();
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.canonicalGasRequirement(DispatchType.ASSOCIATE)).willReturn(SINGLE_TOKEN_ASSOCIATION_COST);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(CONFIG_WITH_MAX_GAS);
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible();
        final var body = subject.decodeAssociateOne(attempt);
        assertAssociationPresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void associateManyWorks() {
        final var encoded = AssociationsTranslator.ASSOCIATE_MANY
                .encodeCallWithArgs(
                        OWNER_HEADLONG_ADDRESS,
                        new Address[] {NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, FUNGIBLE_TOKEN_HEADLONG_ADDRESS})
                .array();
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.canonicalGasRequirement(DispatchType.ASSOCIATE)).willReturn(SINGLE_TOKEN_ASSOCIATION_COST);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(CONFIG_WITH_MAX_GAS);
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible();
        final var body = subject.decodeAssociateMany(attempt);
        assertAssociationPresent(body, FUNGIBLE_TOKEN_ID);
        assertAssociationPresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void associateManyLimit() {
        final var encoded = AssociationsTranslator.ASSOCIATE_MANY
                .encodeCallWithArgs(
                        OWNER_HEADLONG_ADDRESS,
                        Stream.generate(() -> FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                                .limit(22)
                                .toArray(Address[]::new))
                .array();
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.canonicalGasRequirement(DispatchType.ASSOCIATE)).willReturn(SINGLE_TOKEN_ASSOCIATION_COST);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(CONFIG_WITH_MAX_GAS);
        given(attempt.inputBytes()).willReturn(encoded);
        assertThrows(HandleException.class, () -> subject.decodeAssociateMany(attempt));
    }

    @Test
    void dissociateOneWorks() {
        final var encoded = AssociationsTranslator.DISSOCIATE_ONE
                .encodeCallWithArgs(OWNER_HEADLONG_ADDRESS, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                .array();
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.canonicalGasRequirement(DispatchType.ASSOCIATE)).willReturn(SINGLE_TOKEN_ASSOCIATION_COST);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(CONFIG_WITH_MAX_GAS);
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible();
        final var body = subject.decodeDissociateOne(attempt);
        assertDissociationPresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void dissociateManyWorks() {
        final var encoded = AssociationsTranslator.DISSOCIATE_MANY
                .encodeCallWithArgs(
                        OWNER_HEADLONG_ADDRESS,
                        new Address[] {NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, FUNGIBLE_TOKEN_HEADLONG_ADDRESS})
                .array();
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.canonicalGasRequirement(DispatchType.ASSOCIATE)).willReturn(SINGLE_TOKEN_ASSOCIATION_COST);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(CONFIG_WITH_MAX_GAS);
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible();
        final var body = subject.decodeDissociateMany(attempt);
        assertDissociationPresent(body, FUNGIBLE_TOKEN_ID);
        assertDissociationPresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void dissociateManyLimit() {
        final var encoded = AssociationsTranslator.DISSOCIATE_MANY
                .encodeCallWithArgs(
                        OWNER_HEADLONG_ADDRESS,
                        Stream.generate(() -> FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                                .limit(22)
                                .toArray(Address[]::new))
                .array();
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.canonicalGasRequirement(DispatchType.ASSOCIATE)).willReturn(SINGLE_TOKEN_ASSOCIATION_COST);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(CONFIG_WITH_MAX_GAS);
        given(attempt.inputBytes()).willReturn(encoded);
        assertThrows(HandleException.class, () -> subject.decodeDissociateMany(attempt));
    }

    private void givenConvertible() {
        given(addressIdConverter.convert(
                        com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS))
                .willReturn(com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID);
    }

    private void assertAssociationPresent(@NonNull final TransactionBody body, @NonNull final TokenID tokenId) {
        final var associate = body.tokenAssociateOrThrow();
        assertEquals(com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID, associate.account());
        org.assertj.core.api.Assertions.assertThat(associate.tokens()).contains(tokenId);
    }

    private void assertDissociationPresent(@NonNull final TransactionBody body, @NonNull final TokenID tokenId) {
        final var dissociate = body.tokenDissociateOrThrow();
        assertEquals(com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID, dissociate.account());
        org.assertj.core.api.Assertions.assertThat(dissociate.tokens()).contains(tokenId);
    }
}
