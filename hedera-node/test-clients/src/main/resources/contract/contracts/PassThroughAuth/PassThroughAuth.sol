// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

import "./IClprConnector.sol";

/**
 * Reference pass-through Connector contract.
 * Approves all messages unconditionally.
 */
contract PassThroughAuth is IClprConnector {
    constructor() payable {}

    fallback() external payable {}

    receive() external payable {}

    function authorizeOutboundMessage(
        bytes32,
        bytes calldata,
        bytes calldata,
        bytes calldata
    ) external pure override returns (bool) {
        return true;
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
