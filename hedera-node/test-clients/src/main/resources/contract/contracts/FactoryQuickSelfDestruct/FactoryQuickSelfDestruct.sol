// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract FactoryQuickSelfDestruct {
    event ChildCreated(address _address);

    function createAndDeleteChild() payable external {
        Child child = new Child();
        emit ChildCreated(address(child));

        child.del(payable(msg.sender));
    }
}

contract Child {
    event ChildDeleted();

    function del(address beneficiary) external {
        emit ChildDeleted();
        selfdestruct(payable(beneficiary));
    }

}
