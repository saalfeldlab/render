package org.janelia.render.client.solver;

import java.io.Serializable;

import org.janelia.alignment.spec.TileSpec;

public class MinimalTileSpec implements Serializable
{
	private static final long serialVersionUID = 8519999698273174416L;

	final private String fileName;
	final private String fileNameMask;

	final private String tileId;

	final private int width, height, row, col;
	final private double z;

	final private boolean isRestart;

	public MinimalTileSpec( final TileSpec tileSpec )
	{
		this.fileName = tileSpec.getImagePath();
		this.fileNameMask = tileSpec.getMaskPath();

		this.tileId = tileSpec.getTileId();

		this.width = tileSpec.getWidth();
		this.height = tileSpec.getHeight();

		this.row = tileSpec.getLayout().getImageRow();
		this.col = tileSpec.getLayout().getImageCol();

		this.z = tileSpec.getZ();
		this.isRestart = tileSpec.hasLabel( "restart" );
	}

	public String getFileName() { return fileName; }
	public String getMaskFileName() { return fileNameMask; }
	public String getTileId() { return tileId; }
	public double getZ() { return z; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public int getImageRow() { return row; }
	public int getImageCol() { return col; }
	public boolean isRestart() { return isRestart; }
}

