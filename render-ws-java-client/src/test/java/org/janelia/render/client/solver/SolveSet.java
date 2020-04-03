package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public class SolveSet< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > >
{
	final List< SolveItem< G, B, S > > leftItems;
	final List< SolveItem< G, B, S > > rightItems;

	public SolveSet( final List< SolveItem< G, B, S > > leftItems, final List< SolveItem< G, B, S > > rightItems )
	{
		this.leftItems = leftItems;
		this.rightItems = rightItems;
	}

	public List< SolveItem< G, B, S > > allItems()
	{
		final ArrayList< SolveItem< G, B, S > > all = new ArrayList<>();
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
			out += leftItems.get( i ).getId() + ": " + leftItems.get( i ).minZ() + " >> " + leftItems.get( i ).maxZ();

			if ( i < numSetsLeft - 1 )
				out += "\n\t" + rightItems.get( i ).getId() + ": " + rightItems.get( i ).minZ() + " >> " + rightItems.get( i ).maxZ() + "\n";
		}

		return out;
	}
}
