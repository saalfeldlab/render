/**
 *
 *	Copyright (C) 2008 Verena Kaynig.
 *	
 *	This program is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU General Public License
 *	as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
 *	
 *	This program is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU General Public License for more details.
 *	
 *	You should have received a copy of the GNU General Public License
 *	along with this program; if not, write to the Free Software
 *	Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 
 */

/* ****************************************************************  *
 * Representation of a non linear transform by explicit polynomial	 
 * kernel expansion.												
 * 																	
 * TODO:														
 * 	- make different kernels available
 * 	- inverse transform for visualization
 *  - improve image interpolation 				
 *  - apply and applyInPlace should use precalculated transform?
 *    (What about out of image range pixels?)
 *  																
 *  Author: Verena Kaynig						
 *  Kontakt: verena.kaynig@inf.ethz.ch	
 *  
 * ****************************************************************  */

package lenscorrection;

import Jama.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NonLinearTransform implements mpicbg.trakem2.transform.CoordinateTransform
{
    private static final Logger LOG = LoggerFactory.getLogger(NonLinearTransform.class);

	private double[][] beta = null;
	private double[] normMean = null;
	private double[] normVar = null;
	private double[][][] transField = null;
	private int dimension = 0;
	private int length = 0;
	private int width = 0;
	private int height = 0;

	public int getDimension(){ return dimension; }
	/** Deletes all dimension dependent properties */
	public void setDimension( final int dimension )
	{
		this.dimension = dimension;
		length = (dimension + 1)*(dimension + 2)/2;	

		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];

		for (int i=0; i < length; i++){
			normMean[i] = 0;
			normVar[i] = 1;
		}
		transField = null;
		precalculated = false;
	}

	private boolean precalculated = false;

	public int getMinNumMatches()
	{
		return length;
	}


	public void fit( final double x[][], final double y[][], final double lambda )
	{
		final double[][] expandedX = kernelExpandMatrixNormalize( x );

		final Matrix phiX = new Matrix( expandedX, expandedX.length, length );
		final Matrix phiXTransp = phiX.transpose();

		final Matrix phiXProduct = phiXTransp.times( phiX );

		final int l = phiXProduct.getRowDimension();
		final double lambda2 = 2 * lambda;

		for (int i = 0; i < l; ++i )
			phiXProduct.set( i, i, phiXProduct.get( i, i ) + lambda2 );

		final Matrix phiXPseudoInverse = phiXProduct.inverse();
		final Matrix phiXProduct2 = phiXPseudoInverse.times( phiXTransp );
		final Matrix betaMatrix = phiXProduct2.times( new Matrix( y, y.length, 2 ) );

		setBeta( betaMatrix.getArray() );
	}

	public void estimateDistortion( final double hack1[][], final double hack2[][], final double transformParams[][], final double lambda, final int w, final int h )
	{
		beta = new double[ length ][ 2 ];
		normMean = new double[ length ];
		normVar = new double[ length ];

		for ( int i = 0; i < length; i++ )
		{
			normMean[ i ] = 0;
			normVar[ i ] = 1;
		}

		width = w;
		height = h;

		/* TODO Find out how to keep some target points fixed (check fit method of NLT which is supposed to be exclusively forward) */
		final double expandedX[][] = kernelExpandMatrixNormalize( hack1 );
		final double expandedY[][] = kernelExpandMatrix( hack2 );

		final int s = expandedX[ 0 ].length;
		Matrix S1 = new Matrix( 2 * s, 2 * s );
		Matrix S2 = new Matrix( 2 * s, 1 );

		for ( int i = 0; i < expandedX.length; ++i )
		{
			final Matrix xk_ij = new Matrix( expandedX[ i ], 1 );
			final Matrix xk_ji = new Matrix( expandedY[ i ], 1 );

			final Matrix yk1a = xk_ij.minus( xk_ji.times( transformParams[ i ][ 0 ] ) );
			final Matrix yk1b = xk_ij.times( 0.0 ).minus( xk_ji.times( -transformParams[ i ][ 2 ] ) );
			final Matrix yk2a = xk_ij.times( 0.0 ).minus( xk_ji.times( -transformParams[ i ][ 1 ] ) );
			final Matrix yk2b = xk_ij.minus( xk_ji.times( transformParams[ i ][ 3 ] ) );

			final Matrix y = new Matrix( 2, 2 * s );
			y.setMatrix( 0, 0, 0, s - 1, yk1a );
			y.setMatrix( 0, 0, s, 2 * s - 1, yk1b );
			y.setMatrix( 1, 1, 0, s - 1, yk2a );
			y.setMatrix( 1, 1, s, 2 * s - 1, yk2b );

			final Matrix xk = new Matrix( 2, 2 * expandedX[ 0 ].length );
			xk.setMatrix( 0, 0, 0, s - 1, xk_ij );
			xk.setMatrix( 1, 1, s, 2 * s - 1, xk_ij );

			final double[] vals = { hack1[ i ][ 0 ], hack1[ i ][ 1 ] };
			final Matrix c = new Matrix( vals, 2 );

			final Matrix X = xk.transpose().times( xk ).times( lambda );
			final Matrix Y = y.transpose().times( y );

			S1 = S1.plus( Y.plus( X ) );

			final double trans1 = ( transformParams[ i ][ 2 ] * transformParams[ i ][ 5 ] - transformParams[ i ][ 0 ] * transformParams[ i ][ 4 ] );
			final double trans2 = ( transformParams[ i ][ 1 ] * transformParams[ i ][ 4 ] - transformParams[ i ][ 3 ] * transformParams[ i ][ 5 ] );
			final double[] trans = { trans1, trans2 };

			final Matrix translation = new Matrix( trans, 2 );
			final Matrix YT = y.transpose().times( translation );
			final Matrix XC = xk.transpose().times( c ).times( lambda );

			S2 = S2.plus( YT.plus( XC ) );
		}
		final Matrix regularize = Matrix.identity( S1.getRowDimension(), S1.getColumnDimension() );
		final Matrix beta = new Matrix( S1.plus( regularize.times( 0.001 ) ).inverse().times( S2 ).getColumnPackedCopy(), s );

		setBeta( beta.getArray() );
	}

	public NonLinearTransform(final double[][] b, final double[] nm, final double[] nv, final int d, final int w, final int h){
		beta = b;
		normMean = nm;
		normVar = nv;
		dimension = d;
		length = (dimension + 1)*(dimension + 2)/2;	
		width = w;
		height = h;
	}

	public NonLinearTransform(final int d, final int w, final int h){
		dimension = d;
		length = (dimension + 1)*(dimension + 2)/2;	

		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];

		for (int i=0; i < length; i++){
			normMean[i] = 0;
			normVar[i] = 1;
		}

		width = w;
		height = h;
	}

	public NonLinearTransform(){};

	public NonLinearTransform(final double[][] coeffMatrix, final int w, final int h){
		length = coeffMatrix.length;
		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];
		width = w;
		height = h;
		dimension = (int)(-1.5 + Math.sqrt(0.25 + 2*length));

		for(int i=0; i<length; i++){
			beta[i][0] = coeffMatrix[0][i];
			beta[i][1] = coeffMatrix[1][i];
			normMean[i] = coeffMatrix[2][i];
			normVar[i] = coeffMatrix[3][i];
		}
	}


	@Override
	public void init( final String data ) throws NumberFormatException{
		final String[] fields = data.split( " " );
		int c = 0;

		dimension = Integer.parseInt(fields[c]); c++;
		length = Integer.parseInt(fields[c]); c++;

		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];

		if ( fields.length == 4 + 4*length )
		{
			for (int i=0; i < length; i++){
				beta[i][0] = Double.parseDouble(fields[c]); c++;
				beta[i][1] = Double.parseDouble(fields[c]); c++;
			}

			//System.out.println("c: " + c); 

			for (int i=0; i < length; i++){
				normMean[i] = Double.parseDouble(fields[c]); c++;
			}

			//System.out.println("c: " + c); 

			for (int i=0; i < length; i++){
				normVar[i] = Double.parseDouble(fields[c]); c++;
			}

			width = Integer.parseInt(fields[c]); c++;				
			height = Integer.parseInt(fields[c]); c++;
			//System.out.println("c: " + c); 

		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	@Override
	public String toXML(final String indent){
		return new StringBuilder(indent).append("<ict_transform class=\"").append(this.getClass().getCanonicalName()).append("\" data=\"").append(toDataString()).append("\"/>").toString();
	}

	@Override
	public String toDataString(){
		String data = "";
		data += Integer.toString(dimension) + " ";
		data += Integer.toString(length) + " ";

		for (int i=0; i < length; i++){
			data += Double.toString(beta[i][0]) + " ";
			data += Double.toString(beta[i][1]) + " ";
		}

		for (int i=0; i < length; i++){
			data += Double.toString(normMean[i]) + " ";
		}

		for (int i=0; i < length; i++){
			data += Double.toString(normVar[i]) + " ";
		}
		data += Integer.toString(width) + " ";
		data += Integer.toString(height) + " ";

		return data;

	}

	//@Override
	@Override
	public String toString(){ return toDataString(); }

	@Override
	public float[] apply( final float[] location ){

		final double[] position = {(double) location[0], (double) location[1]};
		final double[] featureVector = kernelExpand(position);
		final double[] newPosition = multiply(beta, featureVector);

		final float[] newLocation = new float[2];
		newLocation[0] = (float) newPosition[0];
		newLocation[1] = (float) newPosition[1];

		return newLocation;
	}

	@Override
	public void applyInPlace( final float[] location ){
		final double[] position = {(double) location[0], (double) location[1]};
		final double[] featureVector = kernelExpand(position);
		final double[] newPosition = multiply(beta, featureVector);

		location[0] = (float) newPosition[0];
		location[1] = (float) newPosition[1];
	}

	void precalculateTransfom(){
		transField = new double[width][height][2];
		//double minX = width, minY = height, maxX = 0, maxY = 0;

		for (int x=0; x<width; x++){
			for (int y=0; y<height; y++){
				final double[] position = {x,y};
				final double[] featureVector = kernelExpand(position);
				final double[] newPosition = multiply(beta, featureVector);

				if ((newPosition[0] < 0) || (newPosition[0] >= width) ||
						(newPosition[1] < 0) || (newPosition[1] >= height))
				{
					transField[x][y][0] = -1;
					transField[x][y][1] = -1;
					continue;
				}

				transField[x][y][0] = newPosition[0];
				transField[x][y][1] = newPosition[1];

				//minX = Math.min(minX, x);
				//minY = Math.min(minY, y);
				//maxX = Math.max(maxX, x);
				//maxY = Math.max(maxY, y);

			}
		}

		precalculated = true;
	}

	public double[][] getCoefficients(){
		final double[][] coeffMatrix = new double[4][length];

		for(int i=0; i<length; i++){
			coeffMatrix[0][i] = beta[i][0];
			coeffMatrix[1][i] = beta[i][1];
			coeffMatrix[2][i] = normMean[i];
			coeffMatrix[3][i] = normVar[i];

		}
		return coeffMatrix;
	}

	public void setBeta(final double[][] b){
		beta = b;
		//FIXME: test if normMean and normVar are still valid for this beta
	}

//	public void print(){
//		System.out.println("beta:");
//		for (int i=0; i < beta.length; i++){
//			for (int j=0; j < beta[i].length; j++){
//				System.out.print(beta[i][j]);
//				System.out.print(" ");
//			}
//			System.out.println();
//		}
//
//		System.out.println("normMean:");
//		for (int i=0; i < normMean.length; i++){
//			System.out.print(normMean[i]);
//			System.out.print(" ");
//		}
//
//		System.out.println("normVar:");
//		for (int i=0; i < normVar.length; i++){
//			System.out.print(normVar[i]);
//			System.out.print(" ");
//		}
//
//		System.out.println("Image size:");
//		System.out.println("width: " + width + " height: " + height);
//
//		System.out.println();
//
//	}

	private double[] multiply(final double beta[][], final double featureVector[]){
		final double[] result = {0.0,0.0};

		if (beta.length != featureVector.length){
            LOG.warn("Dimension of TransformMatrix ({}) and featureVector ({}} do not match!",
                     beta.length, featureVector.length);
			return new double[2];
		}

		for (int i=0; i<featureVector.length; i++){
			result[0] = result[0] + featureVector[i] * beta[i][0];
			result[1] = result[1] + featureVector[i] * beta[i][1];
		}

		return result;
	}

	public double[] kernelExpand(final double position[]){
		final double expanded[] = new double[length];

		int counter = 0;
		for (int i=1; i<=dimension; i++){
			for (double j=i; j>=0; j--){
				final double val = Math.pow(position[0],j) * Math.pow(position[1],i-j);
				expanded[counter] = val;
				++counter;
			}
		}

		for (int i=0; i<length-1; i++){
			expanded[i] = expanded[i] - normMean[i];
			expanded[i] = expanded[i] / normVar[i];
		}

		expanded[length-1] = 100;

		return expanded;
	}


	public double[][] kernelExpandMatrixNormalize(final double positions[][]){
		normMean = new double[length];
		normVar = new double[length];

		for (int i=0; i < length; i++){
			normMean[i] = 0;
			normVar[i] = 1;
		}

		final double expanded[][] = new double[positions.length][length];

		for (int i=0; i < positions.length; i++){
			expanded[i] = kernelExpand(positions[i]);
		}

		for (int i=0; i < length; i++){
			double mean = 0;
			double var = 0;
			for (int j=0; j < expanded.length; j++){
				mean += expanded[j][i];
			}

			mean /= expanded.length;

			for (int j=0; j < expanded.length; j++){
				var += (expanded[j][i] - mean)*(expanded[j][i] - mean);
			}
			var /= (expanded.length -1);
			var = Math.sqrt(var);

			normMean[i] = mean;
			normVar[i] = var;
		}

		return kernelExpandMatrix(positions);

	}

	//this function uses the parameters already stored
	//in this object to normalize the positions given.
	public double[][] kernelExpandMatrix(final double positions[][]){


		final double expanded[][] = new double[positions.length][length];

		for (int i=0; i < positions.length; i++){
			expanded[i] = kernelExpand(positions[i]);
		}

		return expanded;

	}

	public void inverseTransform(final double range[][]){
		Matrix expanded = new Matrix(kernelExpandMatrix(range));
		final Matrix b = new Matrix(beta);	

		final Matrix transformed = expanded.times(b);
		expanded = new Matrix(kernelExpandMatrixNormalize(transformed.getArray()));

		final Matrix r = new Matrix(range);
		final Matrix invBeta = expanded.transpose().times(expanded).inverse().times(expanded.transpose()).times(r);
		setBeta(invBeta.getArray());
	}

	public int getWidth(){
		return width;
	}

	public int getHeight(){
		return height;
	}

	/**
	 * TODO Make this more efficient
	 */
	@Override
	final public NonLinearTransform copy()
	{
		final NonLinearTransform t = new NonLinearTransform();
		t.init( toDataString() );
		return t;
	}

	public void set( final NonLinearTransform nlt )
	{
		this.dimension = nlt.dimension;
		this.height = nlt.height;
		this.length = nlt.length;
		this.precalculated = nlt.precalculated;
		this.width = nlt.width;

		/* arrays by deep cloning */
		this.beta = new double[ nlt.beta.length ][];
		for ( int i = 0; i < nlt.beta.length; ++i )
			this.beta[ i ] = nlt.beta[ i ].clone();

		this.normMean = nlt.normMean.clone();
		this.normVar = nlt.normVar.clone();
		this.transField = new double[ nlt.transField.length ][][];

		for ( int a = 0; a < nlt.transField.length; ++a )
		{
			this.transField[ a ] = new double[ nlt.transField[ a ].length ][];
			for ( int b = 0; b < nlt.transField[ a ].length; ++b )
				this.transField[ a ][ b ] = nlt.transField[ a ][ b ].clone();
		}
	}
}
