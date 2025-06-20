// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;

import "./HederaTokenService.sol";
import "./IHederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract FeeDistributor is HederaTokenService {
    function distributeFees(address tokenAddress, address feeCollector, address receiver) external {
        int response = HederaTokenService.transferToken(tokenAddress, feeCollector, receiver, 100);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Transfer of tokens failed");
        }
    }

    function distributeFeesStaticCall(address tokenAddress, address feeCollector, address receiver) external {
        (bool success, bytes memory result) = precompileAddress.call(
            abi.encodeWithSelector(IHederaTokenService.transferToken.selector,
            tokenAddress, feeCollector, receiver, 100));
        int responseCode = success ? abi.decode(result, (int32)) : HederaResponseCodes.UNKNOWN;
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Transfer of tokens failed");
        }
    }
}
