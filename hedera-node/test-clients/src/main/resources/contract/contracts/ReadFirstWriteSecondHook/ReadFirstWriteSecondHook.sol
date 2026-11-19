// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

/// A hook that only reads its first storage slot, and writes its second
contract ReadFirstWriteSecondHook is IHieroAccountAllowanceHook {
    address constant HOOK_ADDR = address(uint160(0x16d));

    /// Only read by the hook, at storage slot 0x00
    uint256 first;
    /// Set to one more than the first slot, at storage slot 0x01
    uint256 second;

    function allow(
        IHieroHook.HookContext calldata,
        ProposedTransfers memory
    ) external override payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        second = first + 1;
        return true;
    }
}
