package org.janelia.render.client.newsolver.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Converts many R's to a Z, e.g. by interpolating between the values
 *
 * @author preibischs
 *
 * @param <R> - many inputs
 * @param <Z> - output
 */
public interface CombiningConsumer< R, Z > extends BiConsumer< R, Z >
{
	public void accept( final List< R > input, final List< Double > weights, final Z output );

	@Override
	default void accept( final R input, final Z output )
	{
		accept( new ArrayList<>( Arrays.asList( input ) ), new ArrayList<>( Arrays.asList( 1.0 ) ), output );
	}
}
