# this script tests the CSV log file to check whether the consensus results, and all 
# intermediate calculations, are exactly the same for both the HashgraphInfo.java code
# and the OCaml code that was generated from the Rocq proof. If they match, then this
# is evidence that the algorithm that Rocq proved is ABFT is the same as the algorithm
# implemented in the Java code.
#
# In order to use this, the executable 'test' must be in the logTest directory.
# It can be created by going into logTest and running ./recompile.sh
# To make that script run correctly, first do the steps listed in its comments. 

logTest/test log.csv