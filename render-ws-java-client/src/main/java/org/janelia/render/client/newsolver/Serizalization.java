package org.janelia.render.client.newsolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Serizalization {

	public static void serialize(final List<? extends BlockData<?, ?>> allItems, final File path)
	{
		try
		{
			for (final BlockData<?, ?> data : allItems)
			{
				// Saving of object in a file
				final File file = new File(path.getAbsoluteFile(), "id_" + data.getId() + ".obj");
				final FileOutputStream fileStream = new FileOutputStream(file);
				final ObjectOutputStream out = new ObjectOutputStream(fileStream);

				// Method for serialization of object
				out.writeObject(data);

				out.close();
				fileStream.close();

				LOG.info("Object " + data.getId() + " has been serialized to " + file.getAbsolutePath());
			}

			LOG.info("SUCCESS.");
		} catch (IOException ex) {
			LOG.info("IOException is caught: " + ex);
			ex.printStackTrace();
		}
	}

	public static ArrayList<BlockData<?, ?>> deSerialize( final File path )
	{
		String[] files = path.list( new FilenameFilter() {
			
			@Override
			public boolean accept(File dir, String name)
			{
				if ( name.endsWith(".obj") )
					return true;
				else
					return false;
			}
		});

		Arrays.sort( files );

		LOG.info("Found " + files.length + " serialized objects" );

		if ( files.length < 3 )
		{
			LOG.info("Not sufficient, stopping." );
			System.exit( 0 );
		}

		final ArrayList<BlockData<?, ?>> allItems = new ArrayList<>();

		for (final String filename : files) {
			try {
				// Reading the object from a file
				FileInputStream file = new FileInputStream(new File(path, filename));
				ObjectInputStream in = new ObjectInputStream(file);

				// Method for deserialization of object
				BlockData<?, ?> solveItem = (BlockData<?, ?>) in.readObject();

				allItems.add(solveItem);

				in.close();
				file.close();

				System.out.println("Object has been deserialized " + solveItem.getId());
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(0);
			}
		}

		LOG.info("Deserialization complete.");

		return allItems;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Serizalization.class);
}
