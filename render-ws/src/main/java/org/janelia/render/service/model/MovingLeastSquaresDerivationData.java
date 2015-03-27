package org.janelia.render.service.model;

/**
 * Identifies align and montage stacks to use in derivation of moving least squares stack.
 *
 * @author Eric Trautman
 */
public class MovingLeastSquaresDerivationData {

    private String alignStack;
    private String montageStack;
    private Double alpha;

    @SuppressWarnings("UnusedDeclaration")
    public MovingLeastSquaresDerivationData() {
        this(null, null, null);
    }

    public MovingLeastSquaresDerivationData(String alignStack,
                                            String montageStack,
                                            Double alpha) {
        this.alignStack = alignStack;
        this.montageStack = montageStack;
        this.alpha = alpha;
    }

    public String getAlignStack() {
        return alignStack;
    }

    public String getMontageStack() {
        return montageStack;
    }

    public Double getAlpha() {
        return alpha;
    }

    @Override
    public String toString() {
        return "{alignStack='" + alignStack + '\'' +
               ", montageStack='" + montageStack + '\'' +
               ", alpha=" + alpha +
               '}';
    }
}
