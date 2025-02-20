// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.7.0;

contract NestedChildren {
    event ChildCreated(address _address);
    constructor() {
        Child child = new Child();
        emit ChildCreated(address(child));
    }

    function callCreate() public {
        Child child = new Child();
        emit ChildCreated(address(child));
    }
}

contract Child {
    event AnotherChild(address _address);
    constructor() {
        ThirdChild child = new ThirdChild();
        emit AnotherChild(address(child));
    }
}

contract ThirdChild {

}
