object "Contract" {
    code {
        datacopy(0, dataoffset("runtime"), datasize("runtime"))
        return(0, datasize("runtime"))
    }

    object "runtime" {
        code {
            mstore(0, 1)
            selfdestruct(shr(96, calldataload(0)))
            return(0, 32)
        }
    }
}
