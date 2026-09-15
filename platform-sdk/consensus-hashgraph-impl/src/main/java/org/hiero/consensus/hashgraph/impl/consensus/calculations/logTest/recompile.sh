# install OCaml before running this to compile the test program:
#     brew install opam
#     opam switch create my_switch 5.4.1
#     eval $(opam env)

ocamlc -w -20 extraction.ml extractionNoOptimization.ml memoized.ml test.ml -o test
