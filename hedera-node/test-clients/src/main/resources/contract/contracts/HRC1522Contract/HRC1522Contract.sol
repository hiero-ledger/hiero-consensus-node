// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaAccountService.sol";
import "./IHederaAccountService.sol";
import "./HederaResponseCodes.sol";

/// Exercises the account staking-configuration functions HIP-1522 adds to the Hedera Account Service.
///
/// These deliberately live in their own contract rather than in HRC632Contract. That fixture is shared
/// with the HIP-632 suites, two of which deploy it under a hard-coded `creationGas` and two of which pin
/// the exact gas at which a call to it flips between INSUFFICIENT_GAS and SUCCESS. Growing it perturbs
/// both, so each HIP gets its own contract.
contract HRC1522Contract is HederaAccountService {

    // Deliberately no `require(responseCode == SUCCESS)`: these return the response code so a spec can
    // assert failure cases (an unauthorized cross-account call, an invalid node id) without reverting.

    function stakeToNodeCall(address account, int64 nodeId) external returns (int64 responseCode) {
        responseCode = HederaAccountService.stakeToNode(account, nodeId);
    }

    function stakeToAccountCall(address account, address stakedTo) external returns (int64 responseCode) {
        responseCode = HederaAccountService.stakeToAccount(account, stakedTo);
    }

    function unstakeCall(address account) external returns (int64 responseCode) {
        responseCode = HederaAccountService.unstake(account);
    }

    function setDeclineRewardCall(address account, bool decline) external returns (int64 responseCode) {
        responseCode = HederaAccountService.setDeclineReward(account, decline);
    }

    function stakeToNodeAndDeclineRewardCall(address account, int64 nodeId, bool decline)
        external returns (int64 responseCode) {
        responseCode = HederaAccountService.stakeToNodeAndDeclineReward(account, nodeId, decline);
    }

    function getStakingInfoCall(address account) external
        returns (int64 responseCode, IHederaAccountService.StakingInfo memory info) {
        (responseCode, info) = HederaAccountService.getStakingInfo(account);
    }

    /// Configures this contract's own staking, the path HIP-1522 exists to serve: a contract with no
    /// admin key directing its own balance, authorized purely by executing this bytecode.
    function stakeSelfToNodeCall(int64 nodeId, bool decline) external returns (int64 responseCode) {
        responseCode = HederaAccountService.stakeToNodeAndDeclineReward(address(this), nodeId, decline);
    }

    /// Reads this contract's own staking state.
    function getOwnStakingInfoCall() external
        returns (int64 responseCode, IHederaAccountService.StakingInfo memory info) {
        (responseCode, info) = HederaAccountService.getStakingInfo(address(this));
    }
}
