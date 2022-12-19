package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Affine2D;

public class SolveSet
{
	final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > leftItems;
	final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rightItems;
	final int maxId;

	public SolveSet(
			final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > leftItems,
			final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rightItems )
	{
		this.leftItems = leftItems;
		this.rightItems = rightItems;

		int maxId = Integer.MIN_VALUE;

		if ( leftItems.size() > 0 )
			maxId = leftItems.get( 0 ).getId();

		if ( rightItems.size() > 0 )
			maxId = rightItems.get( 0 ).getId();

		for ( final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > sid : leftItems )
			maxId = Math.max( sid.getId(), maxId );

		for ( final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > sid : rightItems )
			maxId = Math.max( sid.getId(), maxId );

		this.maxId = maxId;
	}

	public int getMaxId() { return maxId; }

	public ArrayList< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allItems()
	{
		final ArrayList<SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > all = new ArrayList<>();
		all.addAll( leftItems );
		all.addAll( rightItems );

		return all;
	}

	@Override
	public String toString()
	{
		final int numSetsLeft = leftItems.size();

		String out = "";

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			out += leftItems.get( i ).getId() + ": " + leftItems.get( i ).minZ() + " >> " + leftItems.get( i ).maxZ() + " [#"+(leftItems.get( i ).maxZ()-leftItems.get( i ).minZ()+1) + "]";

			if ( i < numSetsLeft - 1 )
				out += "\n\t" + rightItems.get( i ).getId() + ": " + rightItems.get( i ).minZ() + " >> " + rightItems.get( i ).maxZ() + " [#"+(leftItems.get( i ).maxZ()-leftItems.get( i ).minZ()+1) + "]\n";
		}

		out += "\nmaxId = " + maxId;

		return out;
	}
}
