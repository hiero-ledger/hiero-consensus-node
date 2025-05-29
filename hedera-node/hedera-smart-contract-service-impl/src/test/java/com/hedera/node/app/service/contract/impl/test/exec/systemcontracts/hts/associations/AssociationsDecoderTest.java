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
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsTranslator;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssociationsDecoderTest {
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
        assertAssociationPresent(body, OWNER_ID, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void hrcDissociateWorks() {
        given(attempt.senderId()).willReturn(OWNER_ID);
        given(attempt.redirectTokenId()).willReturn(NON_FUNGIBLE_TOKEN_ID);
        final var body = subject.decodeHrcDissociate(attempt);
        assertDissociationPresent(body, OWNER_ID, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void associateOneWorks() {
        final var encoded = AssociationsTranslator.ASSOCIATE_ONE
                .encodeCallWithArgs(OWNER_HEADLONG_ADDRESS, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                .array();
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible(OWNER_HEADLONG_ADDRESS, OWNER_ID);
        final var body = subject.decodeAssociateOne(attempt);
        assertAssociationPresent(body, OWNER_ID, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void associateManyWorks() {
        final var encoded = AssociationsTranslator.ASSOCIATE_MANY
                .encodeCallWithArgs(
                        OWNER_HEADLONG_ADDRESS,
                        new Address[] {NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, FUNGIBLE_TOKEN_HEADLONG_ADDRESS})
                .array();
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible(OWNER_HEADLONG_ADDRESS, OWNER_ID);
        final var body = subject.decodeAssociateMany(attempt);
        assertAssociationPresent(body, OWNER_ID, FUNGIBLE_TOKEN_ID);
        assertAssociationPresent(body, OWNER_ID, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void dissociateOneWorks() {
        final var encoded = AssociationsTranslator.DISSOCIATE_ONE
                .encodeCallWithArgs(OWNER_HEADLONG_ADDRESS, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible(OWNER_HEADLONG_ADDRESS, OWNER_ID);
        final var body = subject.decodeDissociateOne(attempt);
        assertDissociationPresent(body, OWNER_ID, NON_FUNGIBLE_TOKEN_ID);
    }

    @Test
    void dissociateManyWorks() {
        final var encoded = AssociationsTranslator.DISSOCIATE_MANY
                .encodeCallWithArgs(
                        OWNER_HEADLONG_ADDRESS,
                        new Address[] {NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, FUNGIBLE_TOKEN_HEADLONG_ADDRESS})
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        givenConvertible(OWNER_HEADLONG_ADDRESS, OWNER_ID);
        final var body = subject.decodeDissociateMany(attempt);
        assertDissociationPresent(body, OWNER_ID, FUNGIBLE_TOKEN_ID);
        assertDissociationPresent(body, OWNER_ID, NON_FUNGIBLE_TOKEN_ID);
    }

    private void givenConvertible(@NonNull final Address address, @NonNull final AccountID id) {
        given(addressIdConverter.convert(address)).willReturn(id);
    }

    private void assertAssociationPresent(
            @NonNull final TransactionBody body, @NonNull final AccountID target, @NonNull final TokenID tokenId) {
        final var associate = body.tokenAssociateOrThrow();
        assertEquals(target, associate.account());
        org.assertj.core.api.Assertions.assertThat(associate.tokens()).contains(tokenId);
    }

    private void assertDissociationPresent(
            @NonNull final TransactionBody body, @NonNull final AccountID target, @NonNull final TokenID tokenId) {
        final var dissociate = body.tokenDissociateOrThrow();
        assertEquals(target, dissociate.account());
        org.assertj.core.api.Assertions.assertThat(dissociate.tokens()).contains(tokenId);
    }
}
