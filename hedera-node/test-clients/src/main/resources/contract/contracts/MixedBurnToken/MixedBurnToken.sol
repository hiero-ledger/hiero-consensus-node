// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";
import "./HederaTokenService.sol";

contract MixedBurnToken is HederaTokenService {

    event BurnedTokenInfo(uint64 indexed totalSupply) anonymous;
    address tokenAddress;

    constructor(address _tokenAddress) public {
        tokenAddress = _tokenAddress;
    }

   function burnToken(uint64 amount, int64[] memory serialNumbers) public {
        (int response, uint64 newTotalSupply) = HederaTokenService.burnToken(tokenAddress, amount, serialNumbers);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

   function burnTokenWithEvent(uint64 amount, int64[] memory serialNumbers) public {
        (int response, uint64 newTotalSupply) = HederaTokenService.burnToken(tokenAddress, amount, serialNumbers);

        emit BurnedTokenInfo(newTotalSupply);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
   }

    function burnTokenDelegateCall(uint64 amount, address tokenAddress, int64[] memory serialNumbers) public
    returns (bool success, bytes memory result) {
        (success, result) = precompileAddress.delegatecall(
            abi.encodeWithSelector(IHederaTokenService.burnToken.selector,
                tokenAddress, amount, serialNumbers));

        int burnResponse = success
            ? abi.decode(result, (int32))
            : (HederaResponseCodes.UNKNOWN);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }
    }
}
