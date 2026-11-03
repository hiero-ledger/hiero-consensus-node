// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./IClprApplication.sol";
import "./IClprSystemContract.sol";

/**
 * Test CLPR application that sends N messages on a channel and tracks
 * incoming responses. Used in integration test suites to drive and observe
 * the full send → echo → response round-trip.
 *
 * Sending is performed by calling sendMessages(). Responses delivered via
 * onClprResponse() are stored and queried by the test driver.
 */
contract SourceApplication is IClprApplication {
    address private constant CLPR_PRECOMPILE = address(0x16e);

    bytes32 public channelId;
    bytes32 public connectorId;
    bytes  public targetApplication;

    uint256 public sentCount;
    uint256 public responseCount;
    mapping(uint64 => bytes) public responses;

    constructor(bytes32 _channelId, bytes32 _connectorId, bytes memory _targetApplication) {
        channelId    = _channelId;
        connectorId     = _connectorId;
        targetApplication = _targetApplication;
    }

    /**
     * Sends `count` messages on the configured channel. Each message payload
     * is `abi.encodePacked("msg-", sentCount+i)` so they are individually
     * distinguishable in the echo response.
     */
    function sendMessages(uint256 count) external {
        IClprSystemContract clpr = IClprSystemContract(CLPR_PRECOMPILE);
        for (uint256 i = 0; i < count; i++) {
            bytes memory payload = abi.encodePacked("msg-", sentCount + i);
            clpr.sendMessage(channelId, connectorId, targetApplication, payload);
        }
        sentCount += count;
    }

    function onClprMessage(
        bytes32,
        bytes calldata,
        bytes calldata
    ) external pure override returns (bytes memory) {
        // SourceApplication does not receive inbound messages.
        return new bytes(0);
    }

    function onClprResponse(
        bytes32,
        uint64 messageId,
        uint8,
        bytes calldata responseData
    ) external override {
        responses[messageId] = responseData;
        responseCount++;
    }

    function getResponse(uint64 messageId) external view returns (bytes memory) {
        return responses[messageId];
    }
}
