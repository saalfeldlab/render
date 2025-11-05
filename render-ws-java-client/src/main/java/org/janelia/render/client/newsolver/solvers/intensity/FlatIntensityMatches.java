package org.janelia.render.client.newsolver.solvers.intensity;


/**
 * A flat collection of intensity matches with weights.
 */
public class FlatIntensityMatches {
    // Package-private fields for convenience reasons
    final double[] p;
    final double[] q;
    final double[] w;
    int nItems;


    /**
     * Create a new intensity match collection with the specified capacity.
     * @param capacity the number of matches to allocate space for
     */
    public FlatIntensityMatches(final int capacity) {
        this.p = new double[capacity];
        this.q = new double[capacity];
        this.w = new double[capacity];
        this.nItems = 0;
    }

    /**
     * Add a new intensity match to the collection.
     * @param p the intensity value in the first tile
     * @param q the intensity value in the second tile
     * @param w the weight of the match
     */
    public void put(final double p, final double q, final double w) {
        if (nItems >= this.p.length) {
            throw new IllegalStateException("Cannot add more items than allocated: " + this.p.length);
        }
        this.p[nItems] = p;
        this.q[nItems] = q;
        this.w[nItems] = w;
        this.nItems++;
    }

    /**
     * Get the number of matches in the collection.
     * @return the number of matches
     */
    public int size() {
        return nItems;
    }

    /**
     * Check whether the collection is empty.
     * @return true if the collection is empty, false otherwise
     */
    public boolean isEmpty() {
        return nItems == 0;
    }
}
