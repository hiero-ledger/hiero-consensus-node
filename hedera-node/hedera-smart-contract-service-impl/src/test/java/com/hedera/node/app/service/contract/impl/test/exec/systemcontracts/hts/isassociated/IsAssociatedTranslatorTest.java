// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.isassociated;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedTranslator.IS_ASSOCIATED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorForRedirect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated.IsAssociatedTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IsAssociatedTranslatorTest {

    @Mock
    private HtsCallAttempt mockAttempt;

    @Mock
    private Enhancement enhancement;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private IsAssociatedTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new IsAssociatedTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesWithCorrectSelectorAndTokenRedirectReturnsTrue() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getToken(anyLong())).willReturn(FUNGIBLE_TOKEN);
        mockAttempt = prepareHtsAttemptWithSelectorForRedirect(
                IS_ASSOCIATED,
                translator,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(translator.identifyMethod(mockAttempt)).isPresent();
    }

    @Test
    void matchesWithIncorrectSelectorReturnsFalse() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.getToken(anyLong())).willReturn(FUNGIBLE_TOKEN);
        mockAttempt = prepareHtsAttemptWithSelectorForRedirect(
                BURN_TOKEN_V2,
                translator,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(translator.identifyMethod(mockAttempt)).isEmpty();
    }

    @Test
    void matchesWithTokenRedirectFalseReturnsFalse() {
        when(mockAttempt.isTokenRedirect()).thenReturn(false);
        assertThat(translator.identifyMethod(mockAttempt)).isEmpty();
    }

    @Test
    void callFromWithValidAttemptReturnsIsAssociatedCall() {
        when(mockAttempt.systemContractGasCalculator()).thenReturn(gasCalculator);
        when(mockAttempt.enhancement()).thenReturn(enhancement);
        when(mockAttempt.senderId()).thenReturn(AccountID.DEFAULT);
        when(mockAttempt.redirectToken()).thenReturn(Token.DEFAULT);
        var result = translator.callFrom(mockAttempt);

        assertInstanceOf(IsAssociatedCall.class, result);
    }
}
