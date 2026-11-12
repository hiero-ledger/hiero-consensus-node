// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./IClprSystemContract.sol";

contract ClprSystemContract {

    address constant CLPR_PRECOMPILE = address(0x16e);

    /// Sends a message, attaching this contract's balance (including any value sent with this call) to cover the
    /// CLPR system contract's sendMessage fee; the system contract collects only that fee.
    function sendMessage(
        bytes32 channelId,
        bytes32 connectorId,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external payable returns (uint64 messageId) {
        (bool success, bytes memory result) = CLPR_PRECOMPILE.call{value: address(this).balance}(
            abi.encodeWithSelector(
                IClprSystemContract.sendMessage.selector,
                channelId,
                connectorId,
                targetApplication,
                messageData
            )
        );
        require(success, "CLPR sendMessage failed");
        messageId = abi.decode(result, (uint64));
    }

    function getChannelQueueState(bytes32 channelId)
        external payable
        returns (uint64 receivedMessageId, uint64 ackedMessageId)
    {
        (bool success, bytes memory result) = CLPR_PRECOMPILE.call{value: msg.value}(
            abi.encodeWithSelector(
                IClprSystemContract.getChannelQueueState.selector,
                channelId
            )
        );
        require(success, "CLPR getChannelQueueState failed");
        (receivedMessageId, ackedMessageId) = abi.decode(result, (uint64, uint64));
    }
}
