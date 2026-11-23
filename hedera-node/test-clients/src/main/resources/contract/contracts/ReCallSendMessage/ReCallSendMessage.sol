// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

import "./IClprConnector.sol";

interface IClprSystemContractForReentry {
    function sendMessage(
        bytes32 channelId,
        bytes32 connectorId,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external returns (uint64 messageId);
}

contract ReCallSendMessage is IClprConnector {
    address constant CLPR_PRECOMPILE = address(0x16e);

    bytes32 public reentryChannelId;
    bytes32 public reentryConnectorId;

    constructor() payable {}

    function setReentryTarget(bytes32 channelId, bytes32 connectorId) external {
        reentryChannelId = channelId;
        reentryConnectorId = connectorId;
    }

    function authorizeOutboundMessage(
        bytes32,
        bytes calldata targetApplication,
        bytes calldata,
        bytes calldata messageData
    ) external override returns (bool authorized) {
        IClprSystemContractForReentry(CLPR_PRECOMPILE).sendMessage(
            reentryChannelId, reentryConnectorId, targetApplication, messageData
        );
        return true;
    }

    function payForExecution(uint256 amount) external override {
        (bool success,) = msg.sender.call{value: amount}("");
        require(success, "payForExecution failed");
    }

    function onInboundMessage(bytes32, uint64, bytes calldata, bytes calldata, bytes calldata) external override {}
}
