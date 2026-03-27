// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListTargetContract {

    uint public x = 3;
    uint public y = 4;

    function execute() public view returns (uint256) {
        return x + y;
    }
}
