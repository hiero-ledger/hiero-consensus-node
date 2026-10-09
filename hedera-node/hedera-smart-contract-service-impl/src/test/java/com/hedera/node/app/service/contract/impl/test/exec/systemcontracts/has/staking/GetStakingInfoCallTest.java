// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.staking;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.GET_STAKING_INFO;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.StakingInfo;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.GetStakingInfoCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetStakingInfoCallTest extends CallTestBase {

    private static final long VIEW_GAS = 100L;
    private static final AccountID TARGET_ID =
            AccountID.newBuilder().accountNum(1234L).build();
    private static final AccountID STAKED_TO_ID =
            AccountID.newBuilder().accountNum(5678L).build();
    private static final Address STAKED_TO_LONG_ZERO = asHeadlongAddress(asEvmAddress(5678L));
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);
    private static final Bytes EVM_ALIAS = Bytes.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private Account account;

    @BeforeEach
    void setUp() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
    }

    @Test
    void isAViewCallUsableFromAStaticFrame() {
        final var subject = new GetStakingInfoCall(attempt, GET_STAKING_INFO, TARGET_ID);
        assertThat(subject.allowsStaticFrame()).isTrue();
    }

    @Test
    void reportsANodeStakedAccount() {
        givenViewGas();
        givenTargetWith(StakingInfo.newBuilder()
                .declineReward(false)
                .stakePeriodStart(Timestamp.newBuilder().seconds(1_700_000_000L))
                .pendingReward(42L)
                .stakedToMe(7L)
                .stakedNodeId(3L)
                .build());

        assertOutputIs(SUCCESS, Tuple.of(false, 1_700_000_000L, 42L, 7L, 3L, ZERO_ADDRESS));
    }

    @Test
    void reportsAnAccountStakedAccountUsingItsLongZeroAddressWhenItHasNoAlias() {
        givenViewGas();
        givenTargetWith(StakingInfo.newBuilder()
                .declineReward(true)
                .stakedToMe(1L)
                .stakedAccountId(STAKED_TO_ID)
                .build());
        final var stakedTo =
                Account.newBuilder().accountId(STAKED_TO_ID).alias(Bytes.EMPTY).build();
        given(nativeOperations.getAccount(STAKED_TO_ID)).willReturn(stakedTo);

        // no node, so the node id reports its sentinel and stakePeriodStart/pendingReward stay zero
        assertOutputIs(SUCCESS, Tuple.of(true, 0L, 0L, 1L, SENTINEL_NODE_ID, STAKED_TO_LONG_ZERO));
    }

    @Test
    void reportsAnAliasedStakedAccountUsingItsEvmAddress() {
        givenViewGas();
        givenTargetWith(StakingInfo.newBuilder().stakedAccountId(STAKED_TO_ID).build());
        final var stakedTo =
                Account.newBuilder().accountId(STAKED_TO_ID).alias(EVM_ALIAS).build();
        given(nativeOperations.getAccount(STAKED_TO_ID)).willReturn(stakedTo);

        // HIP-1522 specifies the priority EVM address, so an aliased account reports its alias, not long-zero
        assertOutputIs(
                SUCCESS, Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, asHeadlongAddress(EVM_ALIAS.toByteArray())));
    }

    @Test
    void fallsBackToLongZeroWhenTheStakedToAccountIsGone() {
        givenViewGas();
        givenTargetWith(StakingInfo.newBuilder().stakedAccountId(STAKED_TO_ID).build());
        given(nativeOperations.getAccount(STAKED_TO_ID)).willReturn(null);

        assertOutputIs(SUCCESS, Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, STAKED_TO_LONG_ZERO));
    }

    @Test
    void reportsBothSentinelsForAnUnstakedAccount() {
        givenViewGas();
        givenTargetWith(StakingInfo.newBuilder().build());

        assertOutputIs(SUCCESS, Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, ZERO_ADDRESS));
    }

    @Test
    void missingAccountReturnsInvalidAccountIdAndAZeroedStruct() {
        givenViewGas();
        given(nativeOperations.getAccount(TARGET_ID)).willReturn(null);

        final var result = new GetStakingInfoCall(attempt, GET_STAKING_INFO, TARGET_ID)
                .execute(frame)
                .fullResult()
                .result();

        // Non-reverting, so a caller reading another account's state gets a response code, not a revert
        assertThat(result.state()).isEqualTo(State.COMPLETED_SUCCESS);
        assertThat(result.output())
                .isEqualTo(encoded(INVALID_ACCOUNT_ID, Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, ZERO_ADDRESS)));
    }

    @Test
    void unresolvableFacadeTargetReturnsInvalidAccountId() {
        givenViewGas();

        final var result = new GetStakingInfoCall(attempt, GET_STAKING_INFO, null)
                .execute(frame)
                .fullResult();

        assertThat(result.result().output())
                .isEqualTo(encoded(INVALID_ACCOUNT_ID, Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, ZERO_ADDRESS)));
        assertThat(result.gasRequirement()).isEqualTo(VIEW_GAS);
    }

    private void givenViewGas() {
        given(gasCalculator.viewGasRequirement()).willReturn(VIEW_GAS);
    }

    private void givenTargetWith(final StakingInfo info) {
        given(nativeOperations.getAccount(TARGET_ID)).willReturn(account);
        given(nativeOperations.stakingInfoOf(account)).willReturn(info);
    }

    private void assertOutputIs(final com.hedera.hapi.node.base.ResponseCodeEnum status, final Tuple expectedInfo) {
        final var result = new GetStakingInfoCall(attempt, GET_STAKING_INFO, TARGET_ID)
                .execute(frame)
                .fullResult()
                .result();
        assertThat(result.state()).isEqualTo(State.COMPLETED_SUCCESS);
        assertThat(result.output()).isEqualTo(encoded(status, expectedInfo));
    }

    private static org.apache.tuweni.bytes.Bytes encoded(
            final com.hedera.hapi.node.base.ResponseCodeEnum status, final Tuple info) {
        return org.apache.tuweni.bytes.Bytes.wrap(GET_STAKING_INFO
                .getOutputs()
                .encode(Tuple.of((long) status.protoOrdinal(), info))
                .array());
    }
}
