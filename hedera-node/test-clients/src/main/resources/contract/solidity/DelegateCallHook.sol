// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";
import "./IERC20.sol";

contract DelegateCallHook is IHieroAccountAllowanceHook {
    // HIP-1195 special hook address
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        // Ensure we're actually running as a hook (0x16d)
        require(address(this) == HOOK_ADDR, "Only callable as a hook");
        (bool success, bytes memory result) = address(this).delegatecall(abi.encodeWithSignature("_something()"));
        require(success, "Delegate call failed");
        return true;
    }

    function _something() external{}
}

