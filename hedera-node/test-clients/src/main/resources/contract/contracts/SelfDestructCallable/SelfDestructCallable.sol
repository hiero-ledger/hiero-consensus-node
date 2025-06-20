// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract SelfDestructCallable {
    constructor() payable {
    }

    function destroy() public payable {
        selfdestruct(payable(msg.sender));
    }

    function destroyExplicitBeneficiary(address beneficiary) public payable {
        selfdestruct(payable(beneficiary));
    }
}
