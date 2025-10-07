object "Contract" {
    code {
        datacopy(0, dataoffset("runtime"), datasize("runtime"))
        return(0, datasize("runtime"))
    }

    object "runtime" {
        code {
            selfdestruct(0)
            return(0, 32)
        }
    }
}
