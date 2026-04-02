// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListCallerContract {

    event Result(uint256 response);

    function call(address target) public returns (uint256 response) {
        (bool success, bytes memory result) = target.call(abi.encodeWithSignature("execute()"));
        if (success) {
            response = abi.decode(result, (uint256));
            emit Result(response);
        } else {
            revert();
        }
    }

    function callDelegation() public returns (uint256 response) {
        (bool success, bytes memory result) = msg.sender.call(abi.encodeWithSignature("execute()"));
        if (success) {
            response = abi.decode(result, (uint256));
            emit Result(response);
        } else {
            revert();
        }
    }
}
