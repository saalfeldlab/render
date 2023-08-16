package org.janelia.render.client.newsolver.assembly;

import java.util.List;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.AffineModel2D;

public class Affine2DZBlockAssembler extends Assembler< AffineModel2D, ZBlockFactory >
{

	public Affine2DZBlockAssembler( final List<BlockData<?, AffineModel2D, ?, ZBlockFactory>> blocks, final int startId)
	{
		super( blocks, startId );
	}

	@Override
	public void assemble(  )
	{
	}

	private static final Logger LOG = LoggerFactory.getLogger(Affine2DZBlockAssembler.class);
}
