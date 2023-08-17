package org.janelia.render.client.newsolver.assembly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.Model;
import net.imglib2.util.Pair;

public class AssemblyMaps< M extends Model< M > >
{
	final public HashMap< String, M > idToFinalModelGlobal = new HashMap<>();
	final public HashMap< String, TileSpec > idToTileSpecGlobal = new HashMap<>();
	final public HashMap< Integer, HashSet<String> > zToTileIdGlobal = new HashMap<>();
	final public HashMap< String, List< Pair< String, Double > > > idToErrorMapGlobal = new HashMap<>();
}
