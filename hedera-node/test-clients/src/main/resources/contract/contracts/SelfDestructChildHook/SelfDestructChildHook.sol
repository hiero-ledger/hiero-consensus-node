// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "solidity/IHieroAccountAllowanceHook.sol";

/// A hook may create and delete an ordinary child even though it cannot delete the hook execution address.
contract SelfDestructChildHook is IHieroAccountAllowanceHook {
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory
    ) external payable override returns (bool) {
        require(address(this) == address(uint160(0x16d)), "Only callable as a hook");
        new EphemeralHookChild(payable(context.owner));
        return true;
    }
}

contract EphemeralHookChild {
    constructor(address payable beneficiary) {
        selfdestruct(beneficiary);
    }
}
