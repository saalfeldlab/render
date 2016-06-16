package org.janelia.acquire.client;

/**
 * Solver options for alignment tools.
 *
 * See <a href="https://github.com/khaledkhairy/EM_aligner/blob/master/matlab_compiled/sample_montage_input.json">
 *     https://github.com/khaledkhairy/EM_aligner/blob/master/matlab_compiled/sample_montage_input.json
 * </a>
 *
 * <p>
 *     For "montage" runs:
 *     <ul>
 *         <li>
 *             These parameters will always stay unchanged:
 *               solver, nbrs, xs_weight, translation fac, stvec_flag, conn_comp, distributed.
 *         </li>
 *         <li>
 *             These parameters can be experimented with, but normally shouldn't change:
 *               min_tiles, outlier_lambda, min_points, small_region, small_region_lambda, calc_confidence.
 *         </li>
 *         <li>
 *             These parameters need to be specified for each run:
 *               lambda, edge_lambda.
 *         </li>
 *     </ul>
 *
 * </p>
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class SolverOptions {

    private final Integer min_tiles;
    private final Integer degree;
    private final Double outlier_lambda;
    private final String solver;
    private final Integer min_points;
    private final Integer nbrs;
    private final Double xs_weight;
    private final Integer stvec_flag;
    private final Integer conn_comp;
    private final Integer distributed;
    private final Double lambda;
    private final Double edge_lambda;
    private final Double small_region_lambda;
    private final Integer small_region;
    private final Integer calc_confidence;
    private final Integer translation_fac;

    public SolverOptions() {
        this.min_tiles = 3;
        this.degree = 1;
        this.outlier_lambda = 1000.0;
        this.solver = "backslash";
        this.min_points = 5;
        this.nbrs = 1;
        this.xs_weight = 0.05;
        this.stvec_flag = 0;
        this.conn_comp = 1;
        this.distributed = 0;
        this.lambda = 0.01;
        this.edge_lambda = 0.01;
        this.small_region_lambda = 10.0;
        this.small_region = 5;
        this.calc_confidence = 1;
        this.translation_fac = 1;
    }
}
