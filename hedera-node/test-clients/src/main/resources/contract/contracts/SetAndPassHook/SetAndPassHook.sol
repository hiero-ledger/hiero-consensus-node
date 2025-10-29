// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';
import './IHieroAccountAllowancePrePostHook.sol';

interface Target {
    function believeIn(uint32 no) external;
}

contract SetAndPassHook is IHieroAccountAllowanceHook, IHieroAccountAllowancePrePostHook {
    address constant HOOK_ADDR = address(uint160(0x16d));

    /// A value set by data passed in a hook execution
    uint32 v;

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory
    ) external override payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        (uint32 value, address target) = abi.decode(context.data, (uint32, address));
        v = value;
        Target(target).believeIn(value);
        return true;
    }

    function allowPre(
        IHieroHook.HookContext calldata context,
        IHieroAccountAllowanceHook.ProposedTransfers memory
    ) override external payable returns (bool){
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        (uint32 value, address target) = abi.decode(context.data, (uint32, address));
        v = value;
        Target(target).believeIn(value);
        return true;
    }

    function allowPost(
        IHieroHook.HookContext calldata context,
        IHieroAccountAllowanceHook.ProposedTransfers memory
    ) override external payable returns (bool){
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        (uint32 value, address target) = abi.decode(context.data, (uint32, address));
        v = value;
        Target(target).believeIn(value);
        return true;
    }
}
