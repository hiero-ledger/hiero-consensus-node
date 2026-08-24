// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaAccountService.sol";
import "./IHederaAccountService.sol";
import "./HederaResponseCodes.sol";

contract HRC632Contract is HederaAccountService {

    function hbarAllowanceCall(address owner, address spender) external returns (int64 responseCode, int256 amount)
    {
        (responseCode, amount) = HederaAccountService.hbarAllowance(owner, spender);
        require(responseCode == HederaResponseCodes.SUCCESS, "Hbar allowance failed");
    }

    function hbarApproveCall(address owner, address spender, int256 amount) external returns (int64 responseCode)
    {
        responseCode = HederaAccountService.hbarApprove(owner, spender, amount);
        require(responseCode == HederaResponseCodes.SUCCESS, "Hbar approve failed");
    }

    function hbarApproveDelegateCall(address owner, address spender, int256 amount) external {
        (bool success, ) =
            precompileAddress.delegatecall(
                abi.encodeWithSignature("hbarApproveCall(address,address,int256)", owner, spender, amount));
        if (!success) {
            revert ("hbarApprove() Failed As Expected");
        }
    }

    function getEvmAddressAliasCall(address accountNumAlias) external
        returns (int64 responseCode, address evmAddressAlias) {
        (responseCode, evmAddressAlias) = HederaAccountService.getEvmAddressAlias(accountNumAlias);
        require(responseCode == HederaResponseCodes.SUCCESS, "getEvmAddressAlias failed");
    }

    function getHederaAccountNumAliasCall(address evmAddressAlias) external
        returns (int64 responseCode, address accountNumAlias) {
        (responseCode, accountNumAlias) = HederaAccountService.getHederaAccountNumAlias(evmAddressAlias);
        require(responseCode == HederaResponseCodes.SUCCESS, "getHederaAccountNumAlias failed");
    }

    function isValidAliasCall(address addr) external returns (bool response) {
        (response) = HederaAccountService.isValidAlias(addr);
    }

    function isAuthorizedRawCall(address account, bytes memory messageHash, bytes memory signature) external
        returns (bool result) {
        result = HederaAccountService.isAuthorizedRaw(account, messageHash, signature);
    }

    function isAuthorizedCall(address account, bytes memory message, bytes memory signature) external
    returns (bool result) {
        int64 responseCode;
        (responseCode, result) = HederaAccountService.isAuthorized(account, message, signature);
        require(responseCode == HederaResponseCodes.SUCCESS, "getHederaAccountNumAlias failed");
    }

    // --- HIP-1522 account staking configuration ----------------------------------------------------
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
