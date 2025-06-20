// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

import "./NestedContract1.sol";
import "./NestedContract2.sol";

contract NestedTransferringContract {

    NestedContract1 nestedContract1;
    NestedContract2 nestedContract2;

    constructor(address payable _nestedContract1, address payable _nestedContract2) public payable {
        nestedContract1 = NestedContract1(_nestedContract1);
        nestedContract2 = NestedContract2(_nestedContract2);
    }

    function() external payable {}


    function transferFromDifferentAddressesToAddress(address payable _address, uint256 _amount) public payable {
        _address.transfer(_amount);
        nestedContract1.transferToAddress(_address, _amount/2);
        nestedContract2.transferToAddress(_address, _amount/2);
    }

    function transferFromAndToDifferentAddresses(address payable receiver1, address payable receiver2,
        address payable receiver3, uint256 _amount) public payable {
        receiver1.transfer(_amount);
        nestedContract1.transferToAddress(receiver1, _amount/2);
        nestedContract2.transferToAddress(receiver1, _amount/2);

        receiver2.transfer(_amount);
        nestedContract1.transferToAddress(receiver2, _amount/2);
        nestedContract2.transferToAddress(receiver2, _amount/2);

        receiver3.transfer(_amount);
        nestedContract1.transferToAddress(receiver3, _amount/2);
        nestedContract2.transferToAddress(receiver3, _amount/2);
    }

    function transferToContractFromDifferentAddresses(uint256 _amount) public payable {
        nestedContract1.transferToCaller(_amount);
        nestedContract2.transferToCaller(_amount);
    }

    function transferToCallerFromDifferentAddresses(uint256 _amount) public payable {
        (bool success1, bytes memory result1) = address(nestedContract1).delegatecall(abi.encodeWithSignature("transferToCaller(uint256)", _amount));
        (bool success2, bytes memory result2) = address(nestedContract2).delegatecall(abi.encodeWithSignature("transferToCaller(uint256)", _amount));

        if (!success1 || !success2) {
            revert("Delegate transfer call failed!");
        }
    }
}
