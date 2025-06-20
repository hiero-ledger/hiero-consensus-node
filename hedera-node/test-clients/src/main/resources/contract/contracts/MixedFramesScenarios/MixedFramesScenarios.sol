// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
pragma experimental ABIEncoderV2;

import "./IHederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract MixedFramesScenarios is HederaResponseCodes {

    address constant precompileAddress = address(0x167);
    address mixedMintTokenContractAddress;

    constructor(address _mixedMintTokenContractAddress) public {
        mixedMintTokenContractAddress = _mixedMintTokenContractAddress;
    }

   function burnCallAfterNestedMintCallWithPrecompileCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.call(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnDelegateCallAfterNestedMintCallWithPrecompileCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.call(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnDelegateCallAfterNestedMintDelegateCallWithPrecompileCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.delegatecall(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnCallAfterNestedMintDelegateCallWithPrecompileCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.delegatecall(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnCallAfterNestedMintCallWithPrecompileDelegateCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.call(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenDelegateCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnDelegateCallAfterNestedMintCallWithPrecompileDelegateCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.call(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenDelegateCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnDelegateCallAfterNestedMintDelegateCallWithPrecompileDelegateCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.delegatecall(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenDelegateCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnCallAfterNestedMintDelegateCallWithPrecompileDelegateCall(uint64 amount, address tokenAddress) public {
        (bool mintSuccess, bytes memory mintResult) = mixedMintTokenContractAddress.delegatecall(abi.encodeWithSelector
        (MixedMintTokenContract.mintTokenDelegateCall.selector, amount, tokenAddress));

        (int mintResponse, uint64 newTotalSupply, int[] memory serialNumbers) =
            mintSuccess
                ? abi.decode(mintResult, (int32, uint64, int[]))
                : (HederaResponseCodes.UNKNOWN, 0, new int[](0));

        if (mintResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token mint failed");
        }

        (bool burnSuccess, bytes memory burnResult) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
            tokenAddress, amount, new int64[](0)));
        (int burnResponse, uint64 newTotalSupplyAfterBurn) =
            burnSuccess
                ? abi.decode(burnResult, (int32, uint64))
                : (HederaResponseCodes.UNKNOWN, 0);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }
}

contract MixedMintTokenContract is HederaResponseCodes {

    address constant precompileAddress = address(0x167);

    function mintTokenCall(uint64 amount, address tokenAddress) public
        returns (bool success, bytes memory result) {
    (success, result) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.mintToken.selector,
            tokenAddress, amount, new bytes[](0)));
    }

    function mintTokenDelegateCall(uint64 amount, address tokenAddress) public
        returns (bool success, bytes memory result) {
    (success, result) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.mintToken.selector,
            tokenAddress, amount, new bytes[](0)));
    }
}
