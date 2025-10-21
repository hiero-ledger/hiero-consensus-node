// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

/// A degenerate hook useful for basic HIP-1195 testing
contract StorageMappingHook is IHieroAccountAllowanceHook {
    mapping(address => bool) public allowedList;
    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) override external payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        return allowedList[msg.sender];
    }
}
