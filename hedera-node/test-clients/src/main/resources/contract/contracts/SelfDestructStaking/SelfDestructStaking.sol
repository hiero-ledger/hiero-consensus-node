// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/// Exercises staking reward redirection after committed and reverted SELFDESTRUCTs.
contract SelfDestructStaking {
    constructor() payable {}

    function destroy(address payable beneficiary) external {
        selfdestruct(beneficiary);
    }

    function destroyAndCatch(
        SelfDestructStaking target,
        address payable beneficiary,
        address payable revertedBeneficiary
    ) external {
        target.destroy(beneficiary);
        try this.destroyAndRevert(target, revertedBeneficiary) {} catch {}
    }

    function destroyAndRevert(SelfDestructStaking target, address payable beneficiary) external {
        target.destroy(beneficiary);
        revert("Revert beneficiary change");
    }
}
