// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.9;
pragma experimental ABIEncoderV2;

contract MultiversionBurn {
    address precompileAddress;

    constructor(){
        precompileAddress = address(0x167);
    }

    function burnTokenV2(address _token, int64 _amount, int64[] memory _serialNumbers) external
    returns (int32 responseCode, uint64 newTotalSupply)
    {
        (bool success, bytes memory result) = precompileAddress.call(
            abi.encodeWithSignature('burnToken(address,int64,int64[])' /*IHederaTokenService.burnTokenV2.selector*/,
                _token, _amount, _serialNumbers));
        (responseCode, newTotalSupply) =
            success
                ? abi.decode(result, (int32, uint64))
                : (int32(0), 0);
        if(responseCode != 22) {
            revert();
        }
    }

    function burnTokenV1(address _token, uint64 _amount, int64[] memory _serialNumbers) external
    returns (int32 responseCode, uint64 newTotalSupply)
    {
        (bool success, bytes memory result) = precompileAddress.call(
            abi.encodeWithSignature('burnToken(address,uint64,int64[])' /*IHederaTokenService.burnTokenV1.selector*/,
                _token, _amount, _serialNumbers));
        (responseCode, newTotalSupply) =
            success
                ? abi.decode(result, (int32, uint64))
                : (int32(0), 0);
        if(responseCode != 22) {
            revert();
        }
    }

}
