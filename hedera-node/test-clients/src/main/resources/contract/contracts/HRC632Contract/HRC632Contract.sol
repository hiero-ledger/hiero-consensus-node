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
}
