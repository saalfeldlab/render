package org.janelia.render.client.solver;

import java.io.Serializable;

import org.janelia.alignment.spec.TileSpec;

public class MinimalTileSpec implements Serializable
{
	private static final long serialVersionUID = -618001545130332218L;

	final private String fileName;
	final private String fileNameMask;

	final private String tileId;

	final private int width, height;
	final private double z;

	public MinimalTileSpec( final TileSpec tileSpec )
	{
		this.fileName = tileSpec.getFirstMipmapEntry().getValue().getImageFilePath();
		this.fileNameMask = tileSpec.getFirstMipmapEntry().getValue().getMaskFilePath();

		this.tileId = tileSpec.getTileId();

		this.width = tileSpec.getWidth();
		this.height = tileSpec.getHeight();

		this.z = tileSpec.getZ();
	}

	public String getFileName() { return fileName; }
	public String getMaskFileName() { return fileNameMask; }
	public String getTileId() { return tileId; }
	public double getZ() { return z; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
}
