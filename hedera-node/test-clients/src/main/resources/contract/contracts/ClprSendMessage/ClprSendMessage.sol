// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/**
 * Minimal connector contract that forwards a single `sendMessage` call to the CLPR
 * system contract precompile at 0x16e.
 *
 * Intended to be deployed once per ledger and registered as the connector_contract on
 * a CLPR Connector (see ClprCompleteConnector). When the wrapper invokes the precompile,
 * `msg.sender` is the wrapper address, which the precompile authorizes as the registered
 * connector for the given channelId.
 */
contract ClprSendMessage {
    address constant CLPR_PRECOMPILE = address(0x16e);

    /// Enqueue a cross-ledger message via the CLPR system contract.
    /// @param channelId       32-byte channel identifier
    /// @param connectorId        32-byte connector identifier
    ///                           (= keccak256(channelId || pubKey || salt))
    /// @param targetApplication  application address bytes on the peer ledger
    /// @param messageData        opaque application payload
    /// @return messageId         assigned message sequence number
    function sendMessage(
        bytes32 channelId,
        bytes32 connectorId,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external returns (uint64 messageId) {
        (bool success, bytes memory result) = CLPR_PRECOMPILE.call(
            abi.encodeWithSignature(
                "sendMessage(bytes32,bytes32,bytes,bytes)",
                channelId,
                connectorId,
                targetApplication,
                messageData
            )
        );
        require(success, "CLPR sendMessage failed");
        messageId = abi.decode(result, (uint64));
    }
}
