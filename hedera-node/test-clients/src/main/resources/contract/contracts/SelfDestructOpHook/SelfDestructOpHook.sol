// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';
import './IHieroHook.sol';

/// A hook that attempts to self-destruct when invoked
contract SelfDestructOpHook is IHieroAccountAllowanceHook {
    // The canonical hook address used during hook execution
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory /*proposedTransfers*/
    ) external payable override returns (bool) {
        // Ensure this is only used through the hook dispatch
        require(address(this) == HOOK_ADDR, 'Only callable as a hook');

        // Attempt to self-destruct, sending any balance to the hook owner
        selfdestruct(payable(context.owner));

        // Unreachable in practice if selfdestruct executes; kept for completeness
        return true;
    }
}

