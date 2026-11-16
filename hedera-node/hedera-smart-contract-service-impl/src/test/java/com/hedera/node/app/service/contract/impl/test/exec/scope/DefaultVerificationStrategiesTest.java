// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.DefaultVerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultVerificationStrategiesTest {
    private static final long NUMBER = 1234;
    private static final Address ALIAS = Address.fromHexString("abcdef0123456789abcdef0123456789abcdef01");
    private static final Address NUMERIC = Address.fromHexString("00000000000000000000000000000000000004d2");

    @Mock
    private HederaNativeOperations nativeOperations;

    private final DefaultVerificationStrategies subject = new DefaultVerificationStrategies();

    @Test
    void aliasAndNumericReferencesSupplyTheSameCanonicalAuthority() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(DEFAULT_CONFIG);
        given(nativeOperations.resolveAlias(0, 0, Bytes.wrap(ALIAS.getBytes().toArrayUnsafe())))
                .willReturn(NUMBER);

        final var alias = assertInstanceOf(
                AccountAuthorizingVerificationStrategy.class,
                subject.activatingOnlyContractKeysFor(ALIAS, false, nativeOperations));
        final var numeric = assertInstanceOf(
                AccountAuthorizingVerificationStrategy.class,
                subject.activatingOnlyContractKeysFor(NUMERIC, false, nativeOperations));

        assertEquals(entityIdFactory.newAccountId(NUMBER), alias.accountId());
        assertEquals(alias.accountId(), numeric.accountId());
    }

    @Test
    void missingAliasCannotAcquireAuthority() {
        given(nativeOperations.configuration()).willReturn(DEFAULT_CONFIG);
        given(nativeOperations.resolveAlias(0, 0, Bytes.wrap(ALIAS.getBytes().toArrayUnsafe())))
                .willReturn(MISSING_ENTITY_NUMBER);
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.activatingOnlyContractKeysFor(ALIAS, false, nativeOperations));
    }
}
