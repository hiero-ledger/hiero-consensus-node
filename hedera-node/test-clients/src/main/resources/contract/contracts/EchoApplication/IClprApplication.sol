// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/**
 * Interface for CLPR application contracts.
 * Called during submitBundle processing to deliver cross-ledger messages
 * and response callbacks.
 */
interface IClprApplication {
    function onClprMessage(
        bytes32 channelId,
        bytes calldata sender,
        bytes calldata messageData
    ) external returns (bytes memory responseData);

    function onClprResponse(
        bytes32 channelId,
        uint64 messageId,
        uint8 status,
        bytes calldata responseData
    ) external;
}
