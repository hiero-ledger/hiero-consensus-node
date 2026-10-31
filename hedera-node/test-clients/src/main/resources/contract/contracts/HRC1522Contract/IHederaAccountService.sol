// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

interface IHederaAccountService {

    /// An account's staking state, mirroring the HAPI `StakingInfo` message. Because Solidity has no
    /// `oneof`, the protobuf's `staked_id` is flattened into `stakedNodeId` and `stakedAccountId`,
    /// carrying the same sentinels the mutating functions accept. At most one is ever set.
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

    /// Returns the amount of hbar that the spender has been authorized to spend on behalf of the owner.
    /// @param owner The account that has authorized the spender.
    /// @param spender The account that has been authorized by the owner.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return amount The amount of hbar that the spender has been authorized to spend on behalf of the owner.
    function hbarAllowance(address owner, address spender)
    external
    returns (int64 responseCode, int256 amount);

    /// Allows spender to withdraw hbars from the owner account multiple times, up to the value amount. If this
    /// function is called again it overwrites the current allowance with the new amount.
    /// @param owner The owner of the hbars.
    /// @param spender the account address authorized to spend.
    /// @param amount the amount of tokens authorized to spend.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function hbarApprove(
        address owner,
        address spender,
        int256 amount
    ) external returns (int64 responseCode);

    /// Returns the EVM address alias for the given Hedera account.
    /// @param accountNumAlias The Hedera account to get the EVM address alias for.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return evmAddressAlias The EVM address alias for the given Hedera account.
    function getEvmAddressAlias(address accountNumAlias) external
        returns (int64 responseCode, address evmAddressAlias);

    /// Returns the Hedera Account ID (as account num alias) for the given EVM address alias
    /// @param evmAddressAlias The EVM address alias to get the Hedera account for.
    /// @return responseCode The response code for the status of the request.  SUCCESS is 22.
    /// @return accountNumAlias The Hedera account's num for the given EVM address alias.
    function getHederaAccountNumAlias(address evmAddressAlias) external
        returns (int64 responseCode, address accountNumAlias);

    /// Returns true iff a Hedera account num alias or EVM address alias.
    /// @param addr Some 20-byte address.
    /// @return response true iff addr is a Hedera account num alias or an EVM address alias (and false otherwise).
    function isValidAlias(address addr) external returns (bool response);

    /// Determines if the signature is valid for the given message hash and account.
    /// It is assumed that the signature is composed of a single EDCSA or ED25519 key.
    /// @param account The account to check the signature against.
    /// @param messageHash The hash of the message to check the signature against.
    /// @param signature The signature to check.
    /// @return response True if the signature is valid, false otherwise.
    function isAuthorizedRaw(
        address account,
        bytes memory messageHash,
        bytes memory signature) external returns (bool response);

    /// Determines if the signature is valid for the given message  and account.
    /// It is assumed that the signature is composed of a possibly complex cryptographic key.
    /// @param account The account to check the signature against.
    /// @param message The message to check the signature against.
    /// @param signature The signature to check encoded as bytes.
    /// @return responseCode The response code for the status of the request.  SUCCESS is 22.
    /// @return response True if the signature is valid, false otherwise.
    function isAuthorized(
        address account,
        bytes memory message,
        bytes memory signature) external returns (int64 responseCode, bool response);

    /// Stake `account`'s balance to consensus node `nodeId`.
    /// @param account The account to configure.
    /// @param nodeId A non-negative consensus node id, or -1 to clear the staking target
    ///        (equivalent to unstake). Any other negative value is invalid.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function stakeToNode(address account, int64 nodeId) external returns (int64 responseCode);

    /// Stake `account`'s balance to another account.
    /// @param account The account to configure.
    /// @param stakedTo The account to stake to; the zero address clears the staking target.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function stakeToAccount(address account, address stakedTo) external returns (int64 responseCode);

    /// Clear `account`'s staking target (no node / account staking).
    /// @param account The account to configure.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function unstake(address account) external returns (int64 responseCode);

    /// Set `account`'s decline-staking-reward flag.
    /// @param account The account to configure.
    /// @param decline Whether the account declines staking rewards.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function setDeclineReward(address account, bool decline) external returns (int64 responseCode);

    /// Stake `account` to a consensus node and set its decline-reward flag in one call.
    /// @param account The account to configure.
    /// @param nodeId The consensus node id to stake to; must be non-negative. To clear the staking
    ///        target, call unstake instead.
    /// @param decline Whether the account declines staking rewards.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function stakeToNodeAndDeclineReward(address account, int64 nodeId, bool decline)
        external returns (int64 responseCode);

    /// Read `account`'s staking state. Requires no authorization: staking state is already public.
    /// @param account The account to read.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return info The account's staking state.
    function getStakingInfo(address account) external returns (int64 responseCode, StakingInfo memory info);
}
