package org.janelia.alignment;

import ij.ImagePlus;
import ij.io.Opener;
import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.SpringMesh;
import mpicbg.models.TranslationModel2D;



public class Utils {
	
	/**
	 * If a URL starts with "file:", replace "file:" with "" because ImageJ wouldn't understand it otherwise
	 * @return
	 */
	final static private String imageJUrl( final String urlString )
	{
		return urlString.replace( "^file:", "" );
	}
	
	final static public ImagePlus openImagePlus( final String pathString )
	{
		final ImagePlus imp = new Opener().openImage( pathString );
		return imp;
	}
	
	final static public ImagePlus openImagePlusUrl( final String urlString )
	{
		final ImagePlus imp = new Opener().openURL( imageJUrl( urlString ) );
		return imp;
	}

	final static public AbstractModel< ? > createModel( final int modelIndex )
	{
		switch ( modelIndex )
		{
		case 0:
			return new TranslationModel2D();
		case 1:
			return new RigidModel2D();
		case 2:
			return new SimilarityModel2D();
		case 3:
			return new AffineModel2D();
		case 4:
			return new HomographyModel2D();
		default:
			return null;
		}
	}
	
	public static SpringMesh getMesh( int imWidth, int imHeight, float layerScale,
			int resolutionSpringMesh, float stiffnessSpringMesh, float dampSpringMesh, float maxStretchSpringMesh )
	{
		final int meshWidth = ( int )Math.ceil( imWidth * layerScale );
		final int meshHeight = ( int )Math.ceil( imHeight * layerScale );
		
		final SpringMesh mesh = new SpringMesh(
						resolutionSpringMesh,
						meshWidth,
						meshHeight,
						stiffnessSpringMesh,
						maxStretchSpringMesh * layerScale,
						dampSpringMesh );
		
		return mesh;
	}
	
}
