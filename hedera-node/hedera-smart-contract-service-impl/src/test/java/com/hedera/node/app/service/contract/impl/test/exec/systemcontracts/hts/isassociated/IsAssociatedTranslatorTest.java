// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.isassociated;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedTranslator.IS_ASSOCIATED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class IsAssociatedTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt mockAttempt;

    @Mock
    private ContractMetrics contractMetrics;

    private IsAssociatedTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new IsAssociatedTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesWithCorrectSelectorAndTokenRedirectReturnsTrue() {
        given(nativeOperations.getToken(any(TokenID.class))).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        mockAttempt = createHtsCallAttemptForRedirect(IS_ASSOCIATED, subject);
        assertThat(subject.identifyMethod(mockAttempt)).isPresent();
    }

    @Test
    void matchesWithIncorrectSelectorReturnsFalse() {
        given(nativeOperations.getToken(any(TokenID.class))).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        mockAttempt = createHtsCallAttemptForRedirect(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(mockAttempt)).isEmpty();
    }

    @Test
    void matchesWithTokenRedirectFalseReturnsFalse() {
        when(mockAttempt.isRedirect()).thenReturn(false);
        assertThat(subject.identifyMethod(mockAttempt)).isEmpty();
    }

    @Test
    void callFromWithValidAttemptReturnsIsAssociatedCall() {
        when(mockAttempt.systemContractGasCalculator()).thenReturn(gasCalculator);
        when(mockAttempt.enhancement()).thenReturn(mockEnhancement());
        when(mockAttempt.senderId()).thenReturn(AccountID.DEFAULT);
        when(mockAttempt.redirectToken()).thenReturn(Token.DEFAULT);
        var result = subject.callFrom(mockAttempt);

        assertInstanceOf(IsAssociatedCall.class, result);
    }
}
