// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.staking;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.GET_STAKING_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.GET_STAKING_INFO_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.SET_DECLINE_REWARD;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.SET_DECLINE_REWARD_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_ACCOUNT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_ACCOUNT_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_NODE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_NODE_AND_DECLINE_REWARD;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_NODE_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.UNSTAKE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.UNSTAKE_PROXY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_ACCOUNT_ID;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.never;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.GetStakingInfoCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingUpdateCall;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;

class StakingTranslatorTest extends CallAttemptTestBase {

    private static final long NODE_ID = 3L;
    private static final AccountID TARGET_ID =
            AccountID.newBuilder().accountNum(1234L).build();
    private static final AccountID STAKED_TO_ID =
            AccountID.newBuilder().accountNum(5678L).build();
    private static final Address TARGET_ADDRESS = asHeadlongAddress(asEvmAddress(1234L));
    private static final Address STAKED_TO_ADDRESS = asHeadlongAddress(asEvmAddress(5678L));
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);

    // callFrom() probes the other selectors before it reaches the one under test, so unstubbed
    // isSelector() calls must be allowed to return false
    @Mock(strictness = Strictness.LENIENT)
    private HasCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private VerificationStrategy verificationStrategy;

    private StakingTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new StakingTranslator(systemContractMethodRegistry, contractMetrics);
    }

    // --- Selectors ------------------------------------------------------------------------------------

    @Test
    void selectorsAreTheOnesTheHipSpecifies() {
        assertSelector(STAKE_TO_NODE_PROXY, "5fbd84d5");
        assertSelector(STAKE_TO_ACCOUNT_PROXY, "a69431fe");
        assertSelector(UNSTAKE_PROXY, "2def6620");
        assertSelector(SET_DECLINE_REWARD_PROXY, "293d496f");
        assertSelector(STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY, "fad3a941");
        assertSelector(STAKE_TO_NODE, "7a852f7c");
        assertSelector(STAKE_TO_ACCOUNT, "7563f477");
        assertSelector(UNSTAKE, "f2888dbb");
        assertSelector(SET_DECLINE_REWARD, "f8afc6b4");
        assertSelector(STAKE_TO_NODE_AND_DECLINE_REWARD, "d52d84ea");
        assertSelector(GET_STAKING_INFO_PROXY, "b40cd21d");
        assertSelector(GET_STAKING_INFO, "aa4704f3");
    }

    private void assertSelector(final SystemContractMethod method, final String expectedHex) {
        assertThat(Bytes.wrap(method.selector()).toUnprefixedHexString())
                .as(method.signature())
                .isEqualTo(expectedHex);
    }

    // --- identifyMethod -------------------------------------------------------------------------------

    @Test
    void matchesFacadeFormOnRedirectWhenEnabled() {
        givenStakingEnabled(true);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var redirected = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(
                        UNSTAKE_PROXY.encodeCallWithArgs().array(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                configuration,
                subject);
        assertThat(subject.identifyMethod(redirected)).isPresent();
    }

    @Test
    void doesNotMatchWhenDisabled() {
        givenStakingEnabled(false);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var redirected = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(
                        UNSTAKE_PROXY.encodeCallWithArgs().array(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                configuration,
                subject);
        assertThat(subject.identifyMethod(redirected)).isEmpty();
    }

    @Test
    void matchesExplicitFormSentDirectlyTo0x16a() {
        givenStakingEnabled(true);
        final var direct = createHasCallAttempt(
                Bytes.wrap(UNSTAKE.encodeCallWithArgs(STAKED_TO_ADDRESS).array()), configuration, subject);
        assertThat(subject.identifyMethod(direct)).isPresent();
    }

    @Test
    void doesNotMatchFacadeFormSentDirectlyTo0x16a() {
        givenStakingEnabled(true);
        // The facade forms name no account, so they mean nothing without a redirect. Selector matching alone
        // would still match them (CallVia is metadata), and HasCallAttempt#redirectAccountId throws when there
        // is no redirect -- so identifyMethod must reject them outright.
        final var direct = createHasCallAttempt(
                Bytes.wrap(UNSTAKE_PROXY.encodeCallWithArgs().array()), configuration, subject);
        assertThat(direct.isRedirect()).isFalse();
        assertThat(subject.identifyMethod(direct)).isEmpty();
    }

    // --- Bodies ---------------------------------------------------------------------------------------

    @Test
    void stakeToNodeSetsOnlyTheNodeId() {
        final var body = bodyDispatchedBy(STAKE_TO_NODE, NODE_ID).cryptoUpdateAccountOrThrow();
        assertThat(body.accountIDToUpdate()).isEqualTo(TARGET_ID);
        assertThat(body.hasStakedNodeId()).isTrue();
        assertThat(body.stakedNodeIdOrThrow()).isEqualTo(NODE_ID);
        assertThat(body.hasStakedAccountId()).isFalse();
        // "no change" for the reward preference, not "false"
        assertThat(body.hasDeclineReward()).isFalse();
    }

    @Test
    void stakeToNodeAcceptsTheSentinelAsAnUnstakeSpelling() {
        final var body = bodyDispatchedBy(STAKE_TO_NODE, SENTINEL_NODE_ID).cryptoUpdateAccountOrThrow();
        assertThat(body.stakedNodeIdOrThrow()).isEqualTo(SENTINEL_NODE_ID);
        assertThat(body.hasDeclineReward()).isFalse();
    }

    @Test
    void stakeToAccountSetsOnlyTheStakedAccountId() {
        given(addressIdConverter.convert(STAKED_TO_ADDRESS)).willReturn(STAKED_TO_ID);
        final var body = bodyDispatchedBy(STAKE_TO_ACCOUNT, STAKED_TO_ADDRESS).cryptoUpdateAccountOrThrow();
        assertThat(body.accountIDToUpdate()).isEqualTo(TARGET_ID);
        assertThat(body.hasStakedAccountId()).isTrue();
        assertThat(body.stakedAccountIdOrThrow()).isEqualTo(STAKED_TO_ID);
        assertThat(body.hasStakedNodeId()).isFalse();
        assertThat(body.hasDeclineReward()).isFalse();
    }

    @Test
    void stakeToAccountWithTheZeroAddressClearsTheTarget() {
        // The converter resolves the zero address to 0.0.0, the HAPI staked_account_id sentinel
        given(addressIdConverter.convert(ZERO_ADDRESS)).willReturn(SENTINEL_ACCOUNT_ID);
        final var body = bodyDispatchedBy(STAKE_TO_ACCOUNT, ZERO_ADDRESS).cryptoUpdateAccountOrThrow();
        assertThat(body.stakedAccountIdOrThrow()).isEqualTo(SENTINEL_ACCOUNT_ID);
    }

    @Test
    void unstakeUsesTheNodeIdSentinel() {
        final var body = bodyDispatchedBy(UNSTAKE).cryptoUpdateAccountOrThrow();
        assertThat(body.accountIDToUpdate()).isEqualTo(TARGET_ID);
        assertThat(body.hasStakedNodeId()).isTrue();
        assertThat(body.stakedNodeIdOrThrow()).isEqualTo(SENTINEL_NODE_ID);
        assertThat(body.hasStakedAccountId()).isFalse();
        assertThat(body.hasDeclineReward()).isFalse();
    }

    @Test
    void setDeclineRewardSetsOnlyTheRewardPreference() {
        final var body = bodyDispatchedBy(SET_DECLINE_REWARD, true).cryptoUpdateAccountOrThrow();
        assertThat(body.accountIDToUpdate()).isEqualTo(TARGET_ID);
        assertThat(body.hasDeclineReward()).isTrue();
        assertThat(body.declineReward()).isTrue();
        assertThat(body.hasStakedNodeId()).isFalse();
        assertThat(body.hasStakedAccountId()).isFalse();
    }

    @Test
    void setDeclineRewardFalseIsStillAnExplicitChange() {
        final var body = bodyDispatchedBy(SET_DECLINE_REWARD, false).cryptoUpdateAccountOrThrow();
        assertThat(body.hasDeclineReward()).isTrue();
        assertThat(body.declineReward()).isFalse();
    }

    @Test
    void stakeToNodeAndDeclineRewardSetsBoth() {
        final var body = bodyDispatchedBy(STAKE_TO_NODE_AND_DECLINE_REWARD, NODE_ID, true)
                .cryptoUpdateAccountOrThrow();
        assertThat(body.accountIDToUpdate()).isEqualTo(TARGET_ID);
        assertThat(body.stakedNodeIdOrThrow()).isEqualTo(NODE_ID);
        assertThat(body.declineReward()).isTrue();
        assertThat(body.hasStakedAccountId()).isFalse();
    }

    @Test
    void stakeToNodeAndDeclineRewardRejectsANegativeNodeId() {
        // The sentinel is not accepted here: unstake() is the way to clear the target
        assertReturnsWithoutDispatch(STAKE_TO_NODE_AND_DECLINE_REWARD, INVALID_STAKING_ID, SENTINEL_NODE_ID, true);
    }

    @Test
    void facadeCallOnAMissingAccountReturnsInvalidAccountIdWithoutDispatching() {
        given(attempt.isSelector(UNSTAKE_PROXY)).willReturn(true);
        given(attempt.isRedirect()).willReturn(true);
        given(attempt.redirectAccountId()).willReturn(null);
        given(attempt.inputBytes())
                .willReturn(UNSTAKE_PROXY.encodeCallWithArgs().array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(StakingUpdateCall.class);

        final var output = call.execute(frame).fullResult().result().output();
        assertThat(output).isEqualTo(encodedRc(INVALID_ACCOUNT_ID.protoOrdinal()));
    }

    @Test
    void getStakingInfoResolvesTheNamedAccount() {
        given(attempt.isSelector(GET_STAKING_INFO)).willReturn(true);
        given(attempt.inputBytes())
                .willReturn(GET_STAKING_INFO.encodeCallWithArgs(TARGET_ADDRESS).array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(TARGET_ADDRESS)).willReturn(TARGET_ID);

        assertThat(subject.callFrom(attempt)).isInstanceOf(GetStakingInfoCall.class);
    }

    @Test
    void getStakingInfoFacadeFormResolvesTheRedirectAccount() {
        given(attempt.isSelector(GET_STAKING_INFO_PROXY)).willReturn(true);
        given(attempt.isRedirect()).willReturn(true);
        given(attempt.redirectAccountId()).willReturn(TARGET_ID);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(GetStakingInfoCall.class);
    }

    // --- Helpers --------------------------------------------------------------------------------------

    private void givenStakingEnabled(final boolean enabled) {
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.systemContractAccountServiceStakingEnabled()).willReturn(enabled);
    }

    /**
     * Runs an explicit-form call through the translator and its {@link StakingUpdateCall}, and returns the body
     * actually handed to {@code SystemContractOperations#dispatch}.
     */
    private TransactionBody bodyDispatchedBy(final SystemContractMethod method, final Object... args) {
        givenCallOf(method, args);
        given(systemContractOperations.dispatch(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(StakingUpdateCall.class);
        call.execute(frame);

        final var captor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(systemContractOperations).dispatch(captor.capture(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    private void assertReturnsWithoutDispatch(
            final SystemContractMethod method, final ResponseCodeEnum expected, final Object... args) {
        givenCallOf(method, args);

        final var call = subject.callFrom(attempt);
        final var output = call.execute(frame).fullResult().result().output();
        assertThat(output).isEqualTo(encodedRc(expected.protoOrdinal()));
        verify(systemContractOperations, never()).dispatch(any(), any(), any(), any(), any(), any(), any());
    }

    /** Arranges the attempt for an explicit-form call of {@code method} on {@link #TARGET_ADDRESS}. */
    private void givenCallOf(final SystemContractMethod method, final Object... args) {
        final Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = TARGET_ADDRESS;
        System.arraycopy(args, 0, fullArgs, 1, args.length);

        // isSelector() is what callFrom branches on, and this is a mock, so it has to be told
        given(attempt.isSelector(method)).willReturn(true);
        given(attempt.inputBytes())
                .willReturn(method.encodeCallWithArgs(fullArgs).array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(TARGET_ADDRESS)).willReturn(TARGET_ID);
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
    }

    private static Bytes encodedRc(final long code) {
        final ByteBuffer encoded = STAKE_TO_NODE.getOutputs().encode(com.esaulpaugh.headlong.abi.Tuple.singleton(code));
        return Bytes.wrap(encoded.array());
    }
}
