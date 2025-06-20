// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract FactorySelfDestructConstructor {
    event ChildCreated(address _address);

    constructor() payable {
        Child child = new Child();
        emit ChildCreated(address(child));

        selfdestruct(payable(msg.sender));
    }
}

contract Child {

}
