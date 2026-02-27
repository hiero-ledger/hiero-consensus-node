#!/bin/bash
time UseBonnevilleEVM=1 ./cliff.sh :test-clients:testRepeatable --rerun $@ > junkBEVM.log
time                    ./cliff.sh :test-clients:testRepeatable --rerun $@ > junkHEVM.log
echo Now diff junkBEVM.log and junkHEVM.log
