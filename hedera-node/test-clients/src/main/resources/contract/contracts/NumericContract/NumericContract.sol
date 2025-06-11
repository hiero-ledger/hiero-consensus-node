// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

contract NumericContract {

    int32 public constant SUCCESS_CODE = 22;

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*               Non-static Simple HTS functions              */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function updateNFTsMetadata(address nftToken, int64[] memory serialNumbers, bytes memory _newMetadata) public {
        (bool success, bytes memory result) = address(0x167)
            .call(abi.encodeWithSignature("updateNFTsMetadata(address,int64[],bytes)", nftToken, serialNumbers, _newMetadata));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function approveRedirect(address token, address account, uint256 amount) public {
        (bool success, bytes memory result) = address(token).call(abi.encodeWithSignature("approve(address,uint256)", account, amount));

        if (success == false) {
            revert();
        }
    }

    function approve(address token, address account, uint256 amount) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("approve(address,address,uint256)", token, account, amount));

        if (success == false) {
            revert();
        }
    }

    function approveNFT(address token, address account, uint256 amount) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("approveNFT(address,address,uint256)", token, account, amount));

        if (success == false) {
            revert();
        }
    }

    function burnTokenV1(address _token, uint64 _amount, int64[] memory _serialNumbers) external
    returns (int32 responseCode, uint64 newTotalSupply)
    {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("burnToken(address,uint64,int64[])",
            _token, _amount, _serialNumbers));
        (responseCode, newTotalSupply) =
            success
                ? abi.decode(result, (int32, uint64))
                : (int32(0), 0);
        require(responseCode == SUCCESS_CODE);
    }

    function burnTokenV2(address _token, int64 _amount, int64[] memory _serialNumbers) external
    returns (int32 responseCode, uint64 newTotalSupply)
    {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("burnToken(address,int64,int64[])",
            _token, _amount, _serialNumbers));
        (responseCode, newTotalSupply) =
            success
                ? abi.decode(result, (int32, uint64))
                : (int32(0), 0);
        require(responseCode == SUCCESS_CODE);
    }

    function mintTokenV1(address token, uint64 amount, bytes[] memory metadata) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("mintToken(address,uint64,bytes[])", token, amount, metadata));

        (int32 responseCode, uint64 newTotalSupply, int[] memory serialNumbers) = abi.decode(result, (int32, uint64, int[]));
        require(responseCode == SUCCESS_CODE);
    }

    function mintTokenV2(address token, int64 amount, bytes[] memory metadata) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("mintToken(address,int64,bytes[])", token, amount, metadata));

        (int32 responseCode, uint64 newTotalSupply, int[] memory serialNumbers) = abi.decode(result, (int32, uint64, int[]));
        require(responseCode == SUCCESS_CODE);
    }

    function wipeFungibleV1(address token, address account, uint32 amount) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("wipeTokenAccount(address,address,uint32)", token, account, amount));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function wipeFungibleV2(address token, address account, int64 amount) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("wipeTokenAccount(address,address,int64)", token, account, amount));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    function wipeNFT(address token, address account, int64[] memory serialNumbers) public {
        (bool success, bytes memory result) = address(0x167).call(abi.encodeWithSignature("wipeTokenAccountNFT(address,address,int64[])", token, account, serialNumbers));

        int32 responseCode = abi.decode(result, (int32));
        require(responseCode == SUCCESS_CODE);
    }

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*                    Static HTS functions                    */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function getTokenKey(address token, uint256 keyType) public view {
        (bool success, bytes memory result) = address(0x167).staticcall(abi.encodeWithSignature("getTokenKey(address,uint256)", token, keyType));

        if (success == false) {
            revert();
        }
    }

    function getNonFungibleTokenInfo(address token, int64 serialNumber) public view {
        (bool success, bytes memory result) = address(0x167).staticcall(abi.encodeWithSignature("getNonFungibleTokenInfo(address,int64)", token, serialNumber));

        if (success == false) {
            revert();
        }
    }

    function getApproved(address token, uint256 serialNumber) public view {
        (bool success, bytes memory result) = address(0x167).staticcall(abi.encodeWithSignature("getApproved(address,uint256)", token, serialNumber));

        if (success == false) {
            revert();
        }
    }

    function getApprovedERC(address token, uint256 serialNumber) public view {
        (bool success, bytes memory result) = token.staticcall(abi.encodeWithSignature("getApproved(uint256)", serialNumber));

        if (success == false) {
            revert();
        }
    }

    function tokenURI(address token, uint256 _tokenId) public view {
        (bool success, bytes memory result) = token.staticcall(abi.encodeWithSignature("tokenURI(uint256)", _tokenId));
        string memory resultString = abi.decode(result, (string));
        bytes memory expectedBad = "ERC721Metadata: URI query for nonexistent token";

        // tokenURI has different behaviour from the original ERC721 standard as it should revert when providing invalid
        // serialNumber, but instead it returns success with result describing the issue, that is why we compare the result.
        if (keccak256(expectedBad) == keccak256(bytes(resultString))) {
            revert();
        }
    }

    function ownerOf(address token, uint256 _tokenId) public view {
        (bool success, bytes memory result) = token.staticcall(abi.encodeWithSignature("ownerOf(uint256)", _tokenId));

        if (success == false) {
            revert();
        }
    }

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*                    HАS functions                    */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function hbarApproveProxy(address owner, address spender, int256 amount) external {
        (bool success, bytes memory result) = address(owner).call(abi.encodeWithSignature("hbarApprove(address,int256)", spender, amount));

        if (success == false) {
            revert();
        }
    }

    function hbarApprove(address owner, address spender, int256 amount) external {
        (bool success, bytes memory result) = address(0x16a).call(abi.encodeWithSignature("hbarApprove(address,address,int256)", owner, spender, amount));

        if (success == false) {
            revert();
        }
    }

    /*´:°•.°+.*•´.*:˚.°*.˚•´.°:°•.°•.*•´.*:˚.°*.˚•´.°:°•.°+.*•´.*:*/
    /*                    Exchange Rate functions                    */
    /*.•°:°.´+˚.*°.˚:*.´•*.+°.•°:´*.´•*.•°.•°:°.´:•˚°.*°.˚:*.´+°.•*/
    function convertTinycentsToTinybars(uint256 tinycents) external {
        (bool success, bytes memory result) = address(0x168).call(abi.encodeWithSignature("tinycentsToTinybars(uint256)", tinycents));

        if (success == false) {
            revert();
        }
    }

    function convertTinybarsToTinycents(uint256 tinybars) external {
        (bool success, bytes memory result) = address(0x168).call(abi.encodeWithSignature("tinybarsToTinycents(uint256)", tinybars));

        if (success == false) {
            revert();
        }
    }
}
