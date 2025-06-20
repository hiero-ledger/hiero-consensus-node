// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./HederaTokenService.sol";
import "@openzeppelin/contracts/token/ERC721/IERC721.sol";
import "@openzeppelin/contracts/token/ERC721/extensions/IERC721Metadata.sol";
import "@openzeppelin/contracts/token/ERC721/extensions/IERC721Enumerable.sol";

contract ERC721ContractWithHTSCalls is HederaTokenService{

    event ResponseCode(int responseCode);

    function balanceOf(address token, address owner) external view returns (uint256) {
        return IERC721(token).balanceOf(owner);
    }

    function ownerOf(address token, uint256 tokenId) external view returns (address) {
        return IERC721(token).ownerOf(tokenId);
    }

    function name(address token) public view returns (string memory) {
        return IERC721Metadata(token).name();
    }

    function symbol(address token) public view returns (string memory) {
        return IERC721Metadata(token).symbol();
    }

    function tokenURI(address token, uint256 tokenId) public view returns (string memory) {
        return IERC721Metadata(token).tokenURI(tokenId);
    }

    function totalSupply(address token) external view returns (uint256) {
        return IERC721Enumerable(token).totalSupply();
    }

    function ercSetApprovalForAll(address token, address operator, bool approved) external {
        IERC721(token).setApprovalForAll(operator, approved);
    }

    function ercIsApprovedForAll(address token, address owner, address operator) public view returns (bool) {
        return IERC721(token).isApprovedForAll(owner, operator);
    }

    function associateTokenPublic(address account, address token) public returns (int responseCode) {
        responseCode = HederaTokenService.associateToken(account, token);
        emit ResponseCode(responseCode);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }
    }
}
