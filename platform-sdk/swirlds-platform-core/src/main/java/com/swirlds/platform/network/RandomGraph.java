// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;

/**
 * A connected undirected graph with N vertices, each of degree K (each has edges to exactly K others). The graph is
 * generated pseudorandomly from a seed S, so a given (S,N,K) triplet will always generate the same graph. Furthermore,
 * for any given S and K, it is designed to work so that the graph for N and the graph for N+1 are as similar as
 * possible.
 * <p>
 * This generates the matrix in O(N^4) expected time. On a laptop (2016 MacBook Pro), N=1000 took 0.08 seconds. The
 * timing curve from N=0 to N=1000 was very close to a cubic (R^2 value of 0.9994). The time to generate the graph of N
 * vertices (in seconds) was about:
 *
 * <pre>
 * 5.115057698588E-10 * N ^ 3
 * 		+ 5.558555628707E-08 * N ^ 2
 * 				+ 4.810047522830E-06 * N
 * 				- 7.749551593009E-05
 * </pre>
 * <p>
 * The current algorithm is to generate an N-vertex graph by first generating the clique of D+1 vertices, then adding
 * new vertices one at a time until there are N vertices. When there are V vertices (V>=D+1), vertex number V+1 is added
 * by creating the new vertex, randomly choosing D/2 disjoint edges from the existing graph (so no two of them share a
 * vertex), adding edges between the new vertex and all D of the vertices connected to those edges, and deleting those
 * edges. This ensures the new vertex has D edges. And each of the D modified vertices lose one edge and gain one edge,
 * which maintains their total degree at D. Furthermore, the graphs for N and for N+1 vertices are very similar, because
 * only D of the existing vertices are changed, and they are only changed by a single edge deletion and addition.
 * <p>
 * Does the algorithm always halt? Does there always exist a set of D/2 disjoint edges at each step? Yes, and yes. It
 * will not get stuck while trying to select D/2 disjoint edges. The proof is to consider selecting the edges one at a
 * time, and at each step marking the two vertices that the edge connects, and also marking every edge connected to
 * either of those two vertices. Even after D/2-1 edges have been selected, there will still exist at least one unmarked
 * edge to select. In fact, every unmarked vertex will have at least 2 unmarked edges. That is because, at this
 * penultimate step, only D-2 vertices have been marked, and so each unmarked vertex has at most D-2 edges marked (which
 * are those edges connecting to the D-2 marked vertices). Since each unmarked vertex has D edges, there must be at
 * least 2 that are unmarked. So there will certainly be edges available to choose, and so the algorithm will not become
 * stuck. A maximum independent set of edges can be found in polynomial time by the Blossom Algorithm, but the simple,
 * greedy algorithm used here solves an easier problem: find D independent edges in a regular graph of degree D.
 */
public class RandomGraph {
    public static final int EVEN_MOD = 2;
    /** the random number generator seed */
    private final long seed;
    /** the degree of each vertex (the number of other vertices it connects to */
    private final int degree;
    /** number of vertices in the graph */
    private int numVert;
    /** a deterministic PRNG (not cryptographically secure) used to generate graphs based on the seed passed in */
    private Random random;
    /** a deterministic PRNG used to choose random neighbors in randomNeighbor(), independent of the seed passed in */
    private final Random random2;
    /** a symmetric boolean adjacency matrix, with true in an element if those two vertices have an edge */
    private boolean[][] adjacent;
    /** adjacency list form of the graph. neighbor[i][j] is the jth neighbor of vertex i */
    private int[][] neighbor;

    /**
     * Pseudorandomly construct a random undirected graph with numVertices vertices, each of which has edges to
     * vertexDegree other vertices. The generation is pseudorandom, using the given seed. If degree>=numVert-1, then it
     * will be fully connected (so each vertex will actually have a degree of numVert-1).
     *
     * @param random  a source of randomness, does not need to be cryptographically secure. Used for choosing random
     *                neighbors, not for generating the graph.
     * @param numVert the number of vertices in the graph (must be nonnegative).
     * @param degree  the degree of each vertex in the graph (the number of other vertices connected to it by edges). It
     *                must be non-negative and even.
     * @param seed    the seed for the pseudorandom number generator used to generate graphs
     * @throws IllegalArgumentException if the degree constraints are not met
     */
    public RandomGraph(@NonNull final Random random, final int numVert, final int degree, final long seed) {
        if (degree < 0 || degree % EVEN_MOD != 0) {
            throw new IllegalArgumentException("degree must non-negative and even");
        }
        this.degree = degree;
        this.seed = seed;
        this.random = new Random(seed);
        // used to generate the graph, based on the given seed
        // used only for randomNeighbor(), which picks a neighbor at random, independent of the given seed
        this.random2 = random;
        setNumVert(numVert);
    }

    /**
     * Is vertex x adjacent to vertex y? Returns false if not both in the range 0 to numVert-1, inclusive.
     *
     * @param x index of a vertex. range 0 to numVert-1, inclusive.
     * @param y index of a vertex. range 0 to numVert-1, inclusive.
     * @return true if the two vertices are adjacent (have an edge connecting them)
     */
    public boolean isAdjacent(int x, int y) {
        if (x < 0 || y < 0 || x >= numVert || y >= numVert) {
            return false;
        }
        return adjacent[x][y];
    }

    /**
     * Return a boolean array, giving whether each vertex is a neighbor of the given vertex
     *
     * @param vertex the vertex to andClause
     * @return a boolean array whose index v element is true if there is an edge between v and vertex
     */
    public boolean[] getAdjacent(int vertex) {
        return adjacent[vertex];
    }

    /**
     * Randomly choose a vertex adjacent to x. The returned vertex will not be x, and an edge will exist between it and
     * x. If this graph is degree 0, or has 0 vertices, or x is not in the range {@code 0<=x<numVert } then 0 is
     * returned. The neighbor is chosen pseudorandomly, not according to the seed passed in to the graph when it was
     * generated, but according to the seed Java creates in <code>new Random()</code>.
     *
     * @param x the vertex whose neighbor is needed
     * @return a neighbor of x, chosen randomly and uniformly
     */
    public int randomNeighbor(int x) {
        if (numVert == 0 || degree == 0 || x < 0 || x >= numVert) {
            return 0;
        }
        return neighbor[x][random2.nextInt(neighbor[x].length)];
    }

    /**
     * get a copy of an array that gives all the neighbors of vertex x
     *
     * @param x the vertex whose neighbors are needed
     * @return an int[] containing all the neighbors
     */
    public int[] getNeighbors(int x) {
        return neighbor[x].clone();
    }

    /**
     * create the graph with numVert vertices, with recursion, without creating adjacency lists
     */
    private void setNumVertRecursive(int numVert) {
        /* the new adjacency matrix under construction */
        boolean[][] adj;
        /* number of vertices not yet connected to the new vertex (other than itself) */
        int numUnused;
        /* when adding one new row, the indices of vertices not yet connected to the new one */
        int[] unused;

        if (numVert == 0) {
            // base case: erase everything
            this.numVert = 0;
            this.adjacent = new boolean[0][0];
            this.random = new Random(seed);
            return;
        }
        if (numVert == this.numVert) {
            // nothing changes, so do nothing
            return;
        }
        if (degree >= numVert) {
            // the requested degree can't be achieved, so make it fully connected
            this.numVert = numVert;
            adjacent = new boolean[numVert][numVert];
            for (int i = 0; i < numVert; i++) {
                for (int j = 0; j < numVert; j++) {
                    adjacent[i][j] = (i != j);
                }
            }
            return;
        }
        if (numVert != this.numVert + 1) {
            // if this isn't an increment by one, then count up from 0 (only recurses one level deep)
            for (int i = 0; i < numVert; i++) {
                setNumVertRecursive(i);
            }
        }
        // increase the number of vertices from numVert-1 to numVert
        this.numVert = numVert;
        adj = new boolean[numVert][numVert];
        for (int i = 0; i < numVert - 1; i++) {
            System.arraycopy(adjacent[i], 0, adj[i], 0, numVert - 1);
        }
        // if it's fully connected, then add every edge and return
        if (degree >= numVert - 1) {
            for (int i = 0; i < numVert - 1; i++) {
                adj[i][numVert - 1] = true;
                adj[numVert - 1][i] = true;
            }
            adjacent = adj;
            return;
        }

        numUnused = numVert - 1; // everyone (other than self)
        unused = new int[numUnused]; // treat this as a list of possible neighbors that haven't been used yet
        for (int i = 0;
                i < numUnused;
                i++) { // initially, all neighbors are unused, and so are candidates for using them
            unused[i] = i;
        }
        for (int i = 0; i < degree / 2; i++) {
            // delete 1 old edge (between x and y), and add 2 new edges, from the new vertex to both x and y
            while (true) {
                int xi = random.nextInt(numUnused);
                int yi = random.nextInt(numUnused);
                int x = unused[xi];
                int y = unused[yi];
                if (adj[x][y]) {
                    // it must be true that x != y, since there is no edge from a vertex to itself
                    adj[x][y] = false;
                    adj[y][x] = false;
                    adj[x][numVert - 1] = true;
                    adj[y][numVert - 1] = true;
                    adj[numVert - 1][x] = true;
                    adj[numVert - 1][y] = true;
                    if (xi < yi) { // sort so yi < xi <= (numUnused - 1)
                        int t = xi;
                        xi = yi;
                        yi = t;
                    }
                    unused[xi] = unused[numUnused - 1]; // remove element xi from the list
                    unused[yi] = unused[numUnused - 2]; // remove element yi from the resulting list
                    numUnused -= 2; // the list is 2 elements shorter now
                    break;
                }
            }
        }
        adjacent = adj;
    }

    /** Set the number of vertices, and change the graph to be appropriate for that number */
    private void setNumVert(int numVert) {
        // create the adjacency matrix
        setNumVertRecursive(numVert);

        // create the adjacency lists
        int numNeighbors = numVert == 0 ? 0 : degree >= numVert ? numVert - 1 : degree;
        neighbor = new int[numVert][numNeighbors];
        for (int i = 0; i < numVert; i++) {
            int j = 0;
            for (int k = 0; k < numVert; k++) {
                if (adjacent[i][k]) {
                    neighbor[i][j] = k;
                    j++;
                }
            }
        }
    }

    /**
     * The current adjacency matrix represented as a string with # for an edge and . for no edge and \n at the end of
     * each row.
     */
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < numVert; i++) {
            for (int j = 0; j < numVert; j++) {
                result.append(adjacent[i][j] ? " # " : " . ");
            }
            result.append("   ");
            for (int j = 0; j < neighbor[i].length; j++) {
                result.append(" ").append(neighbor[i][j]);
            }
            result.append("\n");
        }
        return result.toString();
    }
}
