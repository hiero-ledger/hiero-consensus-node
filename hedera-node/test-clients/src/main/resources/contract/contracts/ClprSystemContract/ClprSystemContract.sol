// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./IClprSystemContract.sol";

contract ClprSystemContract {

    address constant CLPR_PRECOMPILE = address(0x16e);

    function sendMessage(
        bytes32 channelId,
        bytes32 connectorId,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external returns (uint64 messageId) {
        (bool success, bytes memory result) = CLPR_PRECOMPILE.call(
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
        external
        returns (uint64 receivedMessageId, uint64 ackedMessageId)
    {
        (bool success, bytes memory result) = CLPR_PRECOMPILE.staticcall(
            abi.encodeWithSelector(
                IClprSystemContract.getChannelQueueState.selector,
                channelId
            )
        );
        require(success, "CLPR getChannelQueueState failed");
        (receivedMessageId, ackedMessageId) = abi.decode(result, (uint64, uint64));
    }
}
