// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/// Exercises committed, repeated, and reverted SELFDESTRUCT operations.
contract SelfDestructLifecycle {
    uint public completedDestructions;

    function destroyChildTo(address payable beneficiary) external payable {
        DestructibleChild child = new DestructibleChild{value: msg.value}();
        child.destroy(beneficiary);
        // A finalization failure must roll back this write and the balance transfer above.
        completedDestructions++;
    }

    function destroyChildren(uint count, bool repeat, bool revertAfter) external payable {
        for (uint i = 0; i < count; i++) {
            DestructibleChild child = new DestructibleChild{value: msg.value / count}();
            child.destroy(payable(msg.sender));
            if (repeat) {
                child.destroy(payable(msg.sender));
            }
        }
        require(!revertAfter, "Revert after SELFDESTRUCT");
        completedDestructions += count;
    }

    function catchRevertedDestruction() external {
        try this.destroyChildren(1, false, true) {} catch {}
    }

    function preserveCommittedBeneficiary() external {
        DestructibleChild child = new DestructibleChild();
        child.destroy(payable(msg.sender));
        // The factory is a distinct beneficiary, but this attempt is rolled back.
        try this.destroyAndRevert(child) {} catch {}
    }

    function destroyAndRevert(DestructibleChild child) external {
        child.destroy(payable(address(this)));
        revert("Revert beneficiary change");
    }
}

contract DestructibleChild {
    constructor() payable {}

    function destroy(address payable beneficiary) external {
        selfdestruct(beneficiary);
    }
}
