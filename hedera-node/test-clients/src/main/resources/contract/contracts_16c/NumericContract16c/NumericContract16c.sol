// SPDX-Licence-Identifier: Apache02.0
pragma solidity >=0.5.9 < 0.9.0;
pragma experimental ABIEncoderV2;

contract NumericContract16c {

    int32 public constant SUCCESS_CODE = 22;

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*               Non-static Simple HTS functions              */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function updateNFTsMetadata(address nftToken, int64[] memory serialNumbers, bytes memory _newMetadata) public {
        (bool success, bytes memory result) = address(0x16c)
            .call(abi.encodeWithSignature("updateNFTsMetadata(address,int64[],bytes)", nftToken, serialNumbers, _newMetadata));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*                    Static HTS functions                    */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function getTokenKey(address token, uint keyType) public view {
        (bool success, bytes memory result) = address(0x16c).staticcall(abi.encodeWithSignature("getTokenKey(address,uint256)", token, keyType));

        if (success == false) {
            revert();
        }
    }

}