// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

contract ThreePassesHook is IHieroAccountAllowanceHook {
    address constant HOOK_ADDR = address(uint160(0x16d));

    /// The value used for the first pass
    uint32 a;
    /// The value used for the second pass
    uint32 b;
    /// The value used for the third pass
    uint32 c;

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory
    ) external override payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        (uint32 value) = abi.decode(context.data, (uint32));
        require(value != 0);
        if (a == 0) {
            a = value;
            return true;
        }
        if (b == 0) {
            b = value;
            return true;
        }
        if (c == 0) {
            c = value;
            return true;
        }
        return false;
    }
}
