// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.18;

contract NewOpcodes {

    function opShl(uint _one, uint _two) public pure returns (uint _resp){
        assembly {
            _resp := shl(_one, _two)
        }
    }

    function opShr(uint _one, uint _two) public pure returns (uint _resp){
        assembly {
            _resp := shr(_one, _two)
        }
    }

    function opSar(uint _one, uint _two) public pure returns (uint _resp){
        assembly {
            _resp := sar(_one, _two)
        }
    }

    function opExtCodeHash(address _addr) public view returns (bytes32 _resp){
        assembly {
            _resp := extcodehash(_addr)
        }
    }

    function opPush0() public pure returns (uint _resp) {
        assembly {
        _resp := add(msize(), 0x5f)
//      _resp := add(verbatim_0i_1o(hex"5f"), 0x5f)
        }
    }

}
