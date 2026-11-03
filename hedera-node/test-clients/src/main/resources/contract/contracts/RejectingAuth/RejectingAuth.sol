// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

import "./IClprConnector.sol";

/**
 * Connector contract that rejects all outbound messages.
 * Used for negative testing.
 */
contract RejectingAuth is IClprConnector {
    constructor() payable {}

    fallback() external payable {}

    receive() external payable {}

    function authorizeOutboundMessage(
        bytes32,
        bytes calldata,
        bytes calldata,
        bytes calldata
    ) external pure override returns (bool) {
        revert("Not authorized");
    }

    function payForExecution(uint256 amount) external override {
        (bool success, ) = msg.sender.call{value: amount}("");
        require(success, "CLPR execution payment failed");
    }

    function onInboundMessage(
        bytes32,
        uint64,
        bytes calldata,
        bytes calldata,
        bytes calldata
    ) external override {}
}
