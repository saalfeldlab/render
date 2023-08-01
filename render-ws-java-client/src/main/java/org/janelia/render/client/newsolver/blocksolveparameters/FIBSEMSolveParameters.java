package org.janelia.render.client.newsolver.blocksolveparameters;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

/**
 * 
 * @author preibischs
 *
 * @param <B> the final block solve type (the result)
 * @param <S> the stitching-first type
 */
public class FIBSEMSolveParameters< B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends BlockDataSolveParameters< S >
{

}
