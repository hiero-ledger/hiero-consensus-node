// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";
import "./IHederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract MixedMintTokenContract is HederaResponseCodes {

    address constant precompileAddress = address(0x167);

    function mintTokenCall(uint64 amount, address tokenAddress) public
    returns (bool success, bytes memory result) {
        (success, result) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.mintToken.selector,
                tokenAddress, amount, new bytes[](0)));
    }

    function mintTokenDelegateCall(uint64 amount, address tokenAddress, bytes[] memory metadata) public
    returns (bool success, bytes memory result) {
        (success, result) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.mintToken.selector,
                tokenAddress, amount, metadata));

        int mintResponse = success
            ? abi.decode(result, (int32))
            : (HederaResponseCodes.UNKNOWN);

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }
    }

    function mintTokenStaticCall(uint64 amount, address tokenAddress, bytes[] memory metadata) public
    returns (bool success, bytes memory result) {
        (success, result) = precompileAddress.staticcall(
            abi.encodeWithSelector(IHederaTokenService.mintToken.selector,
                tokenAddress, amount, metadata));

        int mintResponse = success
            ? abi.decode(result, (int32))
            : (HederaResponseCodes.UNKNOWN);

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }
    }


    function callCodeToContractWithoutAmount(address _addr, bytes calldata _customData) external returns (bytes32 output) {
        assembly {
            let x := mload(0x40) // Allocate memory for the calldata copy
            calldatacopy(x, _customData.offset, calldatasize()) // Copy calldata to memory

            let success := callcode(
                3000000, // gas
                _addr, // target address
                0, // no ether to be sent
                x, // calldata start
                calldatasize(), // size of calldata
                x, // where to store the return data
                0x20 // size of return data
            )

        // Check if the callcode was successful
            if eq(success, 0) {revert(add(0x20, "Token mint callcode failed "), 32)}
            output := mload(x) // Load the output
        }
    }

    fallback() external payable {}

    receive() external payable {}
}
