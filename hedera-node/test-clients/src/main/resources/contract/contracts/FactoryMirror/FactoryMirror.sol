// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/**
 * Child contract of parent
 * Supports receiving of funds and ability to spend and report its balance
 *
 */
contract Child {
    receive() external payable {
        emit Transfer(msg.sender, address(this), msg.value);
    }

    event Transfer(address indexed _from, address indexed _to, uint256 amount);
}

/**
 * Contract to showcase a parent child contract relationship
 * On creation parent creates one sub child contract. Parent can create at most one more child for simplicity.
 * Parent supports a payable constructor and donate function to supply additional funds to children.
 * On creation of second child a transfer amount cna be specified to fund second child contract
 * Parent contract supports retrieval of address and balance of child contracts
 *
 */
contract FactoryMirror {
    address[2] childAddresses;

    constructor() payable {
        Child firstChild = new Child();
        childAddresses[0] = address(firstChild);
        emit ParentActivityLog("Created first child contract");
    }

    function createChild(uint256 amount) public returns (address) {
        childAddresses[1] = address(new Child());
        emit ParentActivityLog("Created second child contract");

        if (amount > 0) {
            payable(childAddresses[1]).transfer(amount);
        }

        return childAddresses[1];
    }

    function getAccountBalance() external view returns (uint) {
        return childAddresses[1].balance;
    }

    function getSender() external view returns (address) {
        return msg.sender;
    }

    function multiplySimpleNumbers() public pure returns (uint) {
        return 2 * 2;
    }

    function identifier() public pure returns (bytes4) {
        return msg.sig;
    }

    receive() external payable {}

    event ParentActivityLog(string message);
}
