package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.Affine1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;


/**
 * A tile that contains a grid of sub-tiles, each of which has a model that can be fitted and applied. This acts as a
 * convenience class for handling the fitting and applying of the models of the sub-tiles necessary for intensity
 * correction. The encapsulated sub-tiles also potentially speed up the optimization process by reducing the overhead
 * of parallelizing the optimization of the sub-tiles.
 * <p>
 * This class doesn't derive from {@link Tile} because most of the methods there are tagged final, so they cannot be
 * overridden. This class should only be used in the context of intensity correction.
 */
class IntensityTile {

	final private int nSubTilesPerDimension;
	final private int nFittingCycles;
	final private List<Tile<? extends Affine1D<?>>> subTiles;

	private double distance = 0;
	private final Set<IntensityTile> connectedTiles = new HashSet<>();

	/**
	 * Creates a new intensity tile with the specified number of sub-tiles per dimension and the number of fitting
	 * cycles to perform within one fit of the intensity tile.
	 * @param modelSupplier supplies instances of the model to use for the sub-tiles
	 * @param nSubTilesPerDimension the number of sub-tiles per side of the tile
	 * @param nFittingCycles the number of fitting cycles
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public IntensityTile(
			final Supplier<? extends Affine1D<?>> modelSupplier,
			final int nSubTilesPerDimension,
			final int nFittingCycles
	) {
		this.nSubTilesPerDimension = nSubTilesPerDimension;
		this.nFittingCycles = nFittingCycles;
		final int N = nSubTilesPerDimension * nSubTilesPerDimension;
		this.subTiles = new ArrayList<>(N);

		for (int i = 0; i < N; i++) {
			final Affine1D<?> model = modelSupplier.get();
			this.subTiles.add(new Tile<>((Model) model));
		}
	}

	public Tile<? extends Affine1D<?>> getSubTile(final int i) {
		return this.subTiles.get(i);
	}

	public Tile<? extends Affine1D<?>> getSubTile(final int i, final int j) {
		return this.subTiles.get(i * this.nSubTilesPerDimension + j);
	}

	public int nSubTiles() {
		return this.subTiles.size();
	}

	public int nFittingCycles() {
		return nFittingCycles;
	}

	public double getDistance() {
		return distance;
	}

	/**
	 * Updates the distance of this tile. The distance is the maximum distance of all sub-tiles.
	 */
	public void updateDistance() {
		distance = 0;
		for (final Tile<?> subTile : this.subTiles) {
			subTile.updateCost();
			distance = Math.max(distance, subTile.getDistance());
		}
	}

	public Set<IntensityTile> getConnectedTiles() {
		return connectedTiles;
	}

	/**
	 * Connects this tile to another tile. In contrast to the connect method of the Tile class, this method also
	 * connects the other tile to this tile.
	 * @param otherTile the tile to connect to (bidirectional connection)
	 */
	public void connectTo(final IntensityTile otherTile) {
		connectedTiles.add(otherTile);
		otherTile.connectedTiles.add(this);
	}

	/**
	 * Fits the model of all sub-tiles as often as specified by the nFittingCycles parameter. After fitting the model,
	 * the model is immediately applied to the sub-tile.
	 * @param damp the damping factor to apply to the model
	 * @throws NotEnoughDataPointsException if there are not enough data points to fit the model
	 * @throws IllDefinedDataPointsException if the data points are such that the model cannot be fitted
	 */
	public void fitAndApply(final double damp) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final List<Tile<? extends Affine1D<?>>> shuffledTiles = new ArrayList<>(this.subTiles);
		for (int i = 0; i < nFittingCycles; i++) {
			Collections.shuffle(shuffledTiles);
			for (final Tile<? extends Affine1D<?>> subTile : shuffledTiles) {
				subTile.fitModel();
				subTile.apply(damp);
			}
		}
	}

	/**
	 * Applies the model of all sub-tiles.
	 */
	public void apply() {
		for (final Tile<? extends Affine1D<?>> subTile : this.subTiles) {
			subTile.apply();
		}
	}
}
