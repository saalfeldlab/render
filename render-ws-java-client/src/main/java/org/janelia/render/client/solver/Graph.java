package org.janelia.render.client.solver;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import mpicbg.models.Tile;

/**
 * Tests if a set of tiles has cycles. Only then a full solve is necessary, otherwise calling
 * preAlign will solve the alignment problem.
 * 
 * Adapted and fixed from: https://www.geeksforgeeks.org/detect-cycle-undirected-graph/
 * 
 * @author spreibi
 *
 */
public class Graph
{
	private int v; // No. of vertices
	private LinkedList< Integer > adj[]; // Adjacency List Represntation

	public Graph( final int v )
	{
		this.v = v;
		this.adj = new LinkedList[ v ];
		for (int i = 0; i < v; ++i)
			this.adj[ i ] = new LinkedList();
	}

	// Function to add an edge into the graph
	public void addEdge( final int v, final int w )
	{
		// double adding of edges leads to cycles
		if ( adj[ v ].contains( w ) )
			return;

		adj[ v ].add( w );
		adj[ w ].add( v );
	}

	public Graph( final List< Tile< ? > > tiles ) // final HashMap< Tile< TranslationModel2D >, String > tileToId
	{
		this( tiles.size() );

		final HashMap< Tile< ? >, Integer > tileToId = new HashMap<>();

		for ( int i = 0; i < tiles.size(); ++i )
		{
			tileToId.put( tiles.get( i ), i );
			//System.out.println( "edge " + i + " == " + tileToTileId.get( tiles.get( i ) ) );
		}

		// add an edge for every set of connected tiles
		for ( int i = 0; i < tiles.size(); ++i )
			for ( final Tile< ? > connected : tiles.get( i ).getConnectedTiles() )
			{
				addEdge( i, tileToId.get( connected ) );
				//System.out.println( "Added edge " + i + "--" + tileToId.get( connected ) + ", " + tileToTileId.get( tiles.get( i ) ) + "--" + tileToTileId.get( connected ) );
			}
	}

	// A recursive function that uses visited[] and parent to detect
	// cycle in subgraph reachable from vertex v.
	boolean isCyclicUtil( final int v, final boolean visited[], final int parent )
	{
		// Mark the current node as visited
		visited[ v ] = true;

		// Recur for all the vertices adjacent to this vertex
		Iterator< Integer > it = adj[ v ].iterator();
		while ( it.hasNext() )
		{
			final int i = it.next();

			// If an adjacent is not visited, then recur for that
			// adjacent
			if ( !visited[ i ] )
			{
				if ( isCyclicUtil( i, visited, v ) )
					return true;
			}

			// If an adjacent is visited and not parent of current
			// vertex, then there is a cycle.
			else if ( i != parent )
				return true;
		}
		return false;
	}

	// Returns true if the graph contains a cycle, else false.
	public boolean isCyclic()
	{
		// Mark all the vertices as not visited and not part of
		// recursion stack
		boolean visited[] = new boolean[ v ];
		for (int i = 0; i < v; i++)
			visited[ i ] = false;

		// Call the recursive helper function to detect cycle in
		// different DFS trees
		for (int u = 0; u < v; u++)
			if ( !visited[ u ] ) // Don't recur for u if already visited
				if ( isCyclicUtil( u, visited, -1 ) )
					return true;

		return false;
	}

	// Driver method to test above methods
	public static void main( String args[] )
	{
		// Create a graph given in the above diagram
		Graph g1 = new Graph( 5 );
		g1.addEdge( 1, 0 );
		g1.addEdge( 2, 0 );
		g1.addEdge( 2, 1 );
		g1.addEdge( 0, 3 );
		g1.addEdge( 3, 4 );
		if ( g1.isCyclic() )
			System.out.println( "Graph contains cycle" );
		else
			System.out.println( "Graph doesn't contains cycle" );

		/*
Added edge 0--2, 19-02-09_112345_0-0-3.10000.0--19-02-09_112345_0-0-2.10000.0
Added edge 1--2, 19-02-09_112345_0-0-1.10000.0--19-02-09_112345_0-0-2.10000.0
Added edge 1--3, 19-02-09_112345_0-0-1.10000.0--19-02-09_112345_0-0-0.10000.0
Added edge 2--0, 19-02-09_112345_0-0-2.10000.0--19-02-09_112345_0-0-3.10000.0
Added edge 2--1, 19-02-09_112345_0-0-2.10000.0--19-02-09_112345_0-0-1.10000.0
Added edge 3--1, 19-02-09_112345_0-0-0.10000.0--19-02-09_112345_0-0-1.10000.0
		 */
		Graph g2 = new Graph(4); 
		g2.addEdge(0, 2); 
		g2.addEdge(1, 2);
		g2.addEdge(1, 3);
		g2.addEdge(2, 0);
		g2.addEdge(2, 1);
		g2.addEdge(3, 1);
		if ( g2.isCyclic() )
			System.out.println( "Graph contains cycle" );
		else
			System.out.println( "Graph doesn't contains cycle" );
	}
}