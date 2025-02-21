// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
import "@openzeppelin/contracts/token/ERC721/IERC721.sol";
import "@openzeppelin/contracts/token/ERC721/extensions/IERC721Metadata.sol";
import "@openzeppelin/contracts/token/ERC721/extensions/IERC721Enumerable.sol";

contract ERC721Contract {

    function name(address token) public view {
        IERC721Metadata(token).name();
    }

    function symbol(address token) public view {
        IERC721Metadata(token).symbol();
    }

    function tokenURI(address token, uint256 tokenId) public view {
        IERC721Metadata(token).tokenURI(tokenId);
    }

    function totalSupply(address token) public view {
        IERC721Enumerable(token).totalSupply();
    }

    function balanceOf(address token, address owner) public view {
        IERC721(token).balanceOf(owner);
    }

    function ownerOf(address token, uint256 tokenId) public view {
        IERC721(token).ownerOf(tokenId);
    }

    //Not supported operations - should return a failure

    function transferFrom(address token, address from, address to, uint256 tokenId) public {
        IERC721(token).transferFrom(from, to, tokenId);
    }

    function transferFromThenRevert(address token, address from, address to, uint256 tokenId) public {
        IERC721(token).transferFrom(from, to, tokenId);
        revert();
    }

    function approve(address token, address to, uint256 tokenId) public {
        IERC721(token).approve(to, tokenId);
    }

    function setApprovalForAll(address token, address operator, bool approved) public {
        IERC721(token).setApprovalForAll(operator, approved);
    }

    function getApproved(address token, uint256 tokenId) public view {
        IERC721(token).getApproved(tokenId);
    }

    function isApprovedForAll(address token, address owner, address operator) public view {
        IERC721(token).isApprovedForAll(owner, operator);
    }

    function safeTransferFrom(address token, address from, address to, uint256 tokenId) public {
        IERC721(token).safeTransferFrom(from, to, tokenId);
    }

    function safeTransferFromWithData(address token, address from, address to,
        uint256 tokenId, bytes calldata data) public {
        IERC721(token).safeTransferFrom(from, to, tokenId, data);
    }


    function tokenByIndex(address token, uint256 index) public view {
        IERC721Enumerable(token).tokenByIndex(index);
    }

    function tokenOfOwnerByIndex(address token, address owner, uint256 index) public view {
        IERC721Enumerable(token).tokenOfOwnerByIndex(owner, index);
    }
}
