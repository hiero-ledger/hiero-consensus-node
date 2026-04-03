// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListCallerContract {

    function call(address target) public returns (uint256 response) {
        (bool success, bytes memory result) = target.call(abi.encodeWithSignature("execute()"));
        if (success) {
            response = abi.decode(result, (uint256));
        } else {
            revert();
        }
    }

    function callDelegation() public returns (uint256 response) {
        (bool success, bytes memory result) = msg.sender.call(abi.encodeWithSignature("executeDelegation(uint256,uint256)", 12, 13));
        if (success) {
            response = abi.decode(result, (uint256));
        } else {
            revert();
        }
    }
}
