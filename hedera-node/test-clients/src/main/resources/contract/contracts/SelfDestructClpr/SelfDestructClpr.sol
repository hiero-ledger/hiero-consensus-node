// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/// Creates and destroys a child during a mutable, prepaid CLPR application callback.
contract SelfDestructClpr {
    uint public deliveries;

    function onClprMessage(bytes32, bytes calldata, bytes calldata) external returns (bytes memory) {
        new SelfDestructClprChild(payable(address(this)));
        deliveries++;
        return bytes("");
    }
}

contract SelfDestructClprChild {
    constructor(address payable beneficiary) {
        selfdestruct(beneficiary);
    }
}
