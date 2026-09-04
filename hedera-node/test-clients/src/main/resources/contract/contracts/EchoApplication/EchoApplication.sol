// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./IClprApplication.sol";

/**
 * Reference CLPR application contract that echoes back the message data.
 * Used for integration testing.
 */
contract EchoApplication is IClprApplication {
    function onClprMessage(
        bytes32,
        bytes calldata,
        bytes calldata messageData
    ) external pure override returns (bytes memory responseData) {
        return messageData;
    }

    function onClprResponse(
        bytes32,
        uint64,
        uint8,
        bytes calldata
    ) external pure override {
        // No-op for testing — response delivered successfully
    }
}
