/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import java.util.TreeMap;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class TileSpec
{
	final static private class IntegerStringComparator implements java.util.Comparator< String >
	{
		@Override
		public int compare( final String o1, final String o2 )
		{
			final int a = Integer.parseInt( o1 );
			final int b = Integer.parseInt( o2 );
			return  ( a == b ) ? 0 : ( ( a < b ) ? -1 : 1 );
		}
	}
	
	final private TreeMap< String, ImageAndMask > mipmapLevels = new TreeMap< String, ImageAndMask >( new IntegerStringComparator() );
	public int width = -1;
	public int height = -1;
	public double minIntensity = 0;
	public double maxIntensity = 255;
	public Transform[] transforms = null;
	
	final public CoordinateTransformList< CoordinateTransform > createTransformList()
	{
		final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
		
		if ( transforms != null )
			for ( final Transform t : transforms )
				ctl. add( t.createTransform() );
		
		return ctl;
	}
	
	final public TreeMap< String, ImageAndMask > getMipmapLevels()
	{
		final TreeMap< String, ImageAndMask > a = new TreeMap< String, ImageAndMask >( new IntegerStringComparator() );
		a.putAll( mipmapLevels );
		return a;
	}
}
