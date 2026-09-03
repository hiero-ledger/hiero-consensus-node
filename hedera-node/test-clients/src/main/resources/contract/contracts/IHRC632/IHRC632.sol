// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IHRC632 {
    function hbarAllowance(address spender) external returns (int64 responseCode, int256 allowance);
    function hbarApprove(address spender, int256 amount) external returns (int64 responseCode);

    /// An account's staking state. See IHederaAccountService.StakingInfo -- identical ABI tuple.
    struct StakingInfo {
        /// Whether the account has opted out of receiving staking rewards.
        bool declineReward;
        /// Epoch second at the start of the account's current staking period, derived from the period
        /// number stored on the account; 0 unless the account is staked to a node.
        int64 stakePeriodStart;
        /// Reward in tinybar estimated to be payable at the next reward trigger. An estimate, not a
        /// claimable balance; always 0 when the account declines rewards or is not staked to a node.
        int64 pendingReward;
        /// Total tinybar balance of all accounts staking to this account.
        int64 stakedToMe;
        /// Consensus node the account is staked to, or -1 if the account is not staked to a node.
        int64 stakedNodeId;
        /// Account the account is staked to, or the zero address if not staked to an account. Rendered
        /// in the account's priority EVM form: its EVM address alias when it has one, and the long-zero
        /// address otherwise.
        address stakedAccountId;
    }

    /// Stake the calling account's balance to a consensus node.
    /// @param nodeId A non-negative consensus node id, or -1 to clear the staking target
    ///        (equivalent to unstake()). Any other negative value is invalid.
    /// @return responseCode 22 (SUCCESS) on success; otherwise a Hedera error code.
    function stakeToNode(int64 nodeId) external returns (int64 responseCode);

    /// Stake the calling account's balance to another account.
    /// @param account The account to stake to; the zero address clears the staking target.
    /// @return responseCode 22 (SUCCESS) on success; otherwise a Hedera error code.
    function stakeToAccount(address account) external returns (int64 responseCode);

    /// Clear the calling account's staking target (no node / account staking).
    /// @return responseCode 22 (SUCCESS) on success; otherwise a Hedera error code.
    function unstake() external returns (int64 responseCode);

    /// Set the calling account's decline-staking-reward flag.
    /// @param decline Whether the account declines staking rewards.
    /// @return responseCode 22 (SUCCESS) on success; otherwise a Hedera error code.
    function setDeclineReward(bool decline) external returns (int64 responseCode);

    /// Stake the calling account to a consensus node and set its decline-reward flag in one call.
    /// @param nodeId The consensus node id to stake to; must be non-negative. To clear the staking
    ///        target, call unstake() instead.
    /// @param decline Whether the account declines staking rewards.
    /// @return responseCode 22 (SUCCESS) on success; otherwise a Hedera error code.
    function stakeToNodeAndDeclineReward(int64 nodeId, bool decline) external returns (int64 responseCode);

    /// Read the calling account's staking state.
    /// @return responseCode 22 (SUCCESS) on success; otherwise a Hedera error code.
    /// @return info The account's staking state.
    function getStakingInfo() external returns (int64 responseCode, StakingInfo memory info);
}
