// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";

contract ERC20Contract {

    function name(address token) public view {
        IERC20Metadata(token).name();
    }

    function nameNTimes(address token, uint times) public view {
        for (uint i = 0; i < times; i++) {
            IERC20Metadata(token).name();
        }
    }

    function symbol(address token) public view {
        IERC20Metadata(token).symbol();
    }

    function decimals(address token) public view {
        IERC20Metadata(token).decimals();
    }

    function totalSupply(address token) public view {
        IERC20(token).totalSupply();
    }

    function balanceOf(address token, address account) public view {
        IERC20(token).balanceOf(account);
    }

    function transfer(address token, address recipient, uint256 amount) public {
        IERC20(token).transfer(recipient, amount);
    }

    function transferThenRevert(address token, address recipient, uint256 amount) public {
        IERC20(token).transfer(recipient, amount);
        revert();
    }

    function delegateTransfer(address token, address recipient, uint256 amount) public {
        (bool success, bytes memory result) = address(IERC20(token)).delegatecall(abi.encodeWithSignature("transfer(address,uint256)", recipient, amount));
    }

    //Not supported operations - should return a failure

    function transferFrom(address token, address sender, address recipient, uint256 amount) public {
        IERC20(token).transferFrom(sender, recipient, amount);
    }

    function transferFromThenRevert(address token, address sender, address recipient, uint256 amount) public {
        IERC20(token).transferFrom(sender, recipient, amount);
        revert();
    }

    function allowance(address token, address owner, address spender) public view {
        IERC20(token).allowance(owner, spender);
    }

    function approve(address token, address spender, uint256 amount) public {
        IERC20(token).approve(spender, amount);
    }
}

contract NestedERC20Contract {
}
