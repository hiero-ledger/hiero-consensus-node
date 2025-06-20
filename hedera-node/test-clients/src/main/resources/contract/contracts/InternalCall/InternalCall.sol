// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract InternalCall is HederaTokenService{
    address constant HTS_PRECOMPILE_ADDRESS = address(0x167);

      function isATokenWithCall(address token) external payable returns (bool success, bool isToken) {
        (bool callSuccess, bytes memory result) = HTS_PRECOMPILE_ADDRESS.call{value: msg.value}(
            abi.encodeWithSignature("isToken(address)", token));
        require(callSuccess);

        (int64 responseCode, bool isTokenFlag) = abi.decode(result, (int32, bool));
        success = responseCode == 22; // success
        isToken = success && isTokenFlag;

    }
}
