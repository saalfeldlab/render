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

import mpicbg.models.CoordinateTransform;
import mpicbg.models.IdentityModel;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class Transform
{
	public String className;
	public String dataString;
	
	final public CoordinateTransform createTransform()
	{
		try
		{
			final mpicbg.trakem2.transform.CoordinateTransform ct;
			ct = ( mpicbg.trakem2.transform.CoordinateTransform )Class.forName( className ).newInstance();
			ct.init( dataString );
			return ct;
		}
		catch ( final Exception e )
		{
			return new IdentityModel();
		}
	}
}
