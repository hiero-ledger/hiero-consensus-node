// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract NestedContract1 {

    constructor() public payable {}

    function() external payable {}

    function transferToAddress(address payable _address, uint256 _amount) public payable {
        _address.transfer(_amount);
    }

    function transferToCaller(uint256 _amount) public payable {
        msg.sender.transfer(_amount);
    }
}
