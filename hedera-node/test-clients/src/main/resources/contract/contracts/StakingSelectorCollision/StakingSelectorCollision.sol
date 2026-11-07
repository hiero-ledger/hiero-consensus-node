// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/// An EIP-7702 delegation target whose functions share their selectors with the HIP-1522 IHRC632 staking
/// facade. Each returns a marker the Hedera Account Service never would, so a spec can tell whether a call
/// on a delegated account ran this code or was redirected to HAS.
contract StakingSelectorCollision {
    int64 public constant DELEGATE_MARKER = 4242;

    function unstake() external pure returns (int64) {
        return DELEGATE_MARKER;
    }

    function stakeToNode(int64) external pure returns (int64) {
        return DELEGATE_MARKER;
    }

    function setDeclineReward(bool) external pure returns (int64) {
        return DELEGATE_MARKER;
    }
}
