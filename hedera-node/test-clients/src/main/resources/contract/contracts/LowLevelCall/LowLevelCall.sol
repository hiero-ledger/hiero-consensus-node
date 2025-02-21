// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;

contract LowLevelCall {
      function callRequested(
        address target, 
        bytes calldata payload, 
        uint256 gasAmount
      ) external returns (bool success, bytes memory result) {
        // Making the low-level call with specified gas amount and payload
        (success, result) = target.call{gas: gasAmount}(payload);

        // Optionally, revert the transaction if the call fails
        require(success);
    }

    function callRequestedAndIgnoreFailure(
        address target,
        bytes calldata payload,
        uint256 gasAmount
    ) external returns (bool success, bytes memory result) {
        (success, result) = target.call{gas: gasAmount}(payload);
    }

    function callRequestedAndRevertAfterIgnoringFailure(
        address target,
        bytes calldata payload,
        uint256 gasAmount
    ) external returns (bool success, bytes memory result) {
        (success, result) = target.call{gas: gasAmount}(payload);
        revert();
    }
}
