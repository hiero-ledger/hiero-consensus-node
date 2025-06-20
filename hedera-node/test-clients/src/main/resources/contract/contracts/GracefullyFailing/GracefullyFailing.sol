// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;

import "./HederaTokenService.sol";

contract GracefullyFailingContract is HederaTokenService {

    function performNonExistingServiceFunctionCall(address sender, address token) public {
        precompileAddress.delegatecall(abi.encodeWithSelector(FakeHederaTokenService.fakeFunction.selector, address(this)));
    }

    function performInvalidlyFormattedFunctionCall(address sender, address[] memory tokens) public {
        precompileAddress.delegatecall(abi.encodeWithSelector(FakeHederaTokenService.associateTokens.selector, sender));
    }

    function performLessThanFourBytesFunctionCall(address sender, address token) public {
        precompileAddress.delegatecall(abi.encode("0xcdcd"));
    }

    function performInvalidlyFormattedSingleFunctionCall(address sender) public {
        (bool success, bytes memory result) =
        precompileAddress.delegatecall(abi.encodeWithSelector(FakeHederaTokenService.associateTokens.selector, sender));
        if (!success) {
            revert("Invalidly Formatted Single Function Call failed!");
        }
    }
}

interface FakeHederaTokenService {
    function fakeFunction(address sender) external;

    function associateTokens(address sender) external returns (int responseCode);
}
