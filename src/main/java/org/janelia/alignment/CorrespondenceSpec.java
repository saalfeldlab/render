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

import java.util.List;

import mpicbg.models.PointMatch;

/**
 * 
 *
 * @author Seymour Knowles-Barley
 */
public class CorrespondenceSpec
{
	public String imageUrl1;
	public String imageUrl2;
	public List< PointMatch > correspondencePointPairs ;
	
	public CorrespondenceSpec(String url1, String url2, List< PointMatch > corr_data)
	{
		imageUrl1 = url1;
		imageUrl2 = url2;
		correspondencePointPairs = corr_data;
	}
}
