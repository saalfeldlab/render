package org.janelia.render.client.solver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a distributed solve and serializes the result of all workers, does not run a global optimization
 * 
 * @author preibischs
 */
public class DistributedSolveSerializer
{
	final File path;

	public DistributedSolveSerializer( final File path )
	{
		this.path = path;
	}

	public void serialize( final List< ? extends SolveItemData< ?, ?, ? > > allItems  )
	{
        try
        {
        	for ( final SolveItemData< ?, ?, ? > data : allItems )
        	{
	            //Saving of object in a file 
        		final File file = new File( path.getAbsoluteFile(), "id_" + data.getId() + ".obj" );
	            final FileOutputStream fileStream = new FileOutputStream( file ); 
	            final ObjectOutputStream out = new ObjectOutputStream( fileStream ); 
	              
	            // Method for serialization of object 
	            out.writeObject(data); 
	              
	            out.close(); 
	            fileStream.close(); 
	              
	            LOG.info( "Object " + data.getId() + " has been serialized to " + file.getAbsolutePath() ); 
        	}

        	LOG.info( "SUCCESS." );
        }
        catch(IOException ex) 
        { 
            LOG.info("IOException is caught: " + ex );
            ex.printStackTrace();
        }
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveSerializer.class);
}
