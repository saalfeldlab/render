/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.reflect.TypeToken;

/**
 * Parameters for render operations.  Includes a collection of TileSpecs and
 * thus represents a `snapshot of the world'.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
@Parameters
public class RenderParameters {

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--tile_spec_url", description = "URL to JSON tile spec", required = false)
    private String tileSpecUrl;

    @Parameter(names = "--res", description = " Mesh resolution, specified by the desired size of a mesh cell (triangle) in pixels", required = false)
    private double meshCellSize;

    @Parameter(names = "--in", description = "Path to the input image if any", required = false)
    private String in;

    @Parameter(names = "--out", description = "Path to the output image", required = true)
    private String out;

    @Parameter(names = "--x", description = "Target image left coordinate", required = false)
    private double x;

    @Parameter(names = "--y", description = "Target image top coordinate", required = false)
    private double y;

    @Parameter(names = "--width", description = "Target image width", required = false)
    private int width;

    @Parameter(names = "--height", description = "Target image height", required = false)
    private int height;

    @Parameter(names = "--scale", description = "scale factor applied to the target image", required = false)
    private double scale;

    @Parameter(names = "--area_offset", description = "add bounding box offset", required = false)
    private boolean areaOffset;

    @Parameter(names = "--gray", description = "convert output to gray scale image", required = false)
    private boolean convertToGray;

    @Parameter(names = "--quality", description = "JPEG quality float [0, 1]", required = false)
    private float quality;

    @Parameter(names = "--threads", description = "Number of threads to be used", required = false )
    public int numberOfThreads;

    @Parameter(names = "--skip_interpolation", description = "enable sloppy but fast rendering by skipping interpolation", required = false)
    private boolean skipInterpolation;

    @Parameter(names = "--parameters_url", description = "URL to base JSON parameters file (to be applied to any unspecified or default parameters)", required = false)
    private final String parametersUrl;

    @Parameter(names = "--do_filter", description = "ad hoc filters to support alignment", required = false)
    private boolean doFilter;

    /** List of tile specifications parsed from --tileSpecUrl or deserialized directly from json. */
    private List<TileSpec> tileSpecs;


    private transient JCommander jCommander;
    private transient URI outUri;
    private transient boolean initialized;

	    public RenderParameters() {
        this(null,
             DEFAULT_X_AND_Y,
             DEFAULT_X_AND_Y,
             DEFAULT_HEIGHT_AND_WIDTH,
             DEFAULT_HEIGHT_AND_WIDTH,
             DEFAULT_SCALE);
    }

    public RenderParameters(final String tileSpecUrl,
                            final double x,
                            final double y,
                            final int width,
                            final int height,
                            final double scale) {
        this.tileSpecUrl = tileSpecUrl;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scale = scale;

        this.help = false;
        this.meshCellSize = DEFAULT_MESH_CELL_SIZE;
        this.in = null;
        this.out = null;
        this.areaOffset = false;
        this.convertToGray = false;
        this.quality = DEFAULT_QUALITY;
        this.numberOfThreads = DEFAULT_NUMBER_OF_THREADS;
        this.skipInterpolation = false;
        this.doFilter = false;
        this.parametersUrl = null;

        this.tileSpecs = new ArrayList<TileSpec>();

        this.jCommander = null;
        this.outUri = null;
        this.initialized = false;
    }

    /**
     * @param  args  arguments to parse.
     *
     * @return parameters instance populated by parsing the specified arguments.
     *
     * @throws IllegalArgumentException
     *   if any invalid arguments are specified.
     */
    public static RenderParameters parseCommandLineArgs(final String[] args) throws IllegalArgumentException {
        final RenderParameters parameters = new RenderParameters();
        parameters.setCommander();
        try {
            parameters.jCommander.parse(args);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse command line arguments", t);
        }

        parameters.applyBaseParameters();
        parameters.initializeDerivedValues();

        return parameters;
    }

    /**
     * @param  jsonText  text to parse.
     *
     * @return parameters instance populated by parsing the specified json text.
     *
     * @throws IllegalArgumentException
     *   if the json cannot be parsed.
     */
    public static RenderParameters parseJson(final String jsonText) throws IllegalArgumentException {
        RenderParameters parameters;
        try {
            parameters = JsonUtils.GSON.fromJson(jsonText, RenderParameters.class);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse json text", t);
        }
        return parameters;
    }

    /**
     * @param  jsonReader  reader to parse.
     *
     * @return parameters instance populated by parsing the specified json reader's stream.
     *
     * @throws IllegalArgumentException
     *   if the json cannot be parsed.
     */
    public static RenderParameters parseJson(final Reader jsonReader) throws IllegalArgumentException {
        RenderParameters parameters;
        try {
            parameters = JsonUtils.GSON.fromJson(jsonReader, RenderParameters.class);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse json reader stream", t);
        }
        return parameters;
    }

    /**
     * @param  jsonFile  reader to parse.
     *
     * @return parameters instance populated by parsing the specified json reader's stream.
     *
     * @throws IllegalArgumentException
     *   if the json cannot be parsed.
     */
    public static RenderParameters parseJson(final File jsonFile) throws IllegalArgumentException {

        if (! jsonFile.exists()) {
            throw new IllegalArgumentException("render parameters json file " + jsonFile.getAbsolutePath() +
                                               " does not exist");
        }

        if (! jsonFile.canRead()) {
            throw new IllegalArgumentException("render parameters json file " + jsonFile.getAbsolutePath() +
                                               " is not readable");
        }

        FileReader parametersReader;
        try {
            parametersReader = new FileReader(jsonFile);
        } catch (final FileNotFoundException e) {
            throw new IllegalArgumentException("render parameters json file " + jsonFile.getAbsolutePath() +
                                               " does not exist", e);
        }

        RenderParameters parameters;
        try {
            parameters = RenderParameters.parseJson(parametersReader);
        } finally {
            try {
                parametersReader.close();
            } catch (final IOException e) {
                LOG.warn("failed to close reader for " + jsonFile.getAbsolutePath() + ", ignoring error", e);
            }
        }

        return parameters;
    }

    /**
     * Initialize derived parameter values.
     */
    public void initializeDerivedValues() {
        if (! initialized) {
            parseTileSpecs();
            initialized = true;
        }
    }

    public boolean displayHelp() {
        return help;
    }

    public double getRes() {
        return meshCellSize;
    }

    public String getOut() {
        return out;
    }

    public URI getOutUri() throws IllegalArgumentException {
        if ((outUri == null) && (out != null)) {
            try {
                outUri = new URI(out);
            } catch (final URISyntaxException e) {
                throw new IllegalArgumentException("failed to create uniform resource identifier for '" + out + "'", e);
            }
        }
        return outUri;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public double getScale() {
        return scale;
    }

    public boolean isAreaOffset() {
        return areaOffset;
    }

    public boolean isConvertToGray() {
        return convertToGray;
    }

    public float getQuality() {
        return quality;
    }

    public int getNumberOfThreads() {
        return numberOfThreads;
    }

    public boolean skipInterpolation() {
        return skipInterpolation;
    }

    public boolean hasTileSpecs() {
        return ((tileSpecs != null) && (tileSpecs.size() > 0));
    }

    public int numberOfTileSpecs() {
        int count = 0;
        if (tileSpecs != null) {
            count = tileSpecs.size();
        }
        return count;
    }

    public List<TileSpec> getTileSpecs() {
        return tileSpecs;
    }

    public void addTileSpec(final TileSpec tileSpec) {
        tileSpecs.add(tileSpec);
    }

    public void flattenTransforms() {
        for (final TileSpec spec : tileSpecs) {
            spec.flattenTransforms();
        }
    }

    /**
     * Displays command usage information on the console (standard-out).
     */
    public void showUsage() {
        if (jCommander == null) {
            setCommander();
        }
        jCommander.usage();
    }

    /**
     * @throws IllegalArgumentException
     *   if this set of parameters is invalid.
     *
     * @throws IllegalStateException
     *   if the derived parameters have not been initialized.
     */
    public void validate() throws IllegalArgumentException, IllegalStateException {

        // validate specified out parameter is a valid URI
        getOutUri();

        if (! initialized) {
            throw new IllegalStateException("derived parameters have not been initialized");
        }

        for (final TileSpec tileSpec : tileSpecs) {
            tileSpec.validate();
        }
    }

    /**
     * Opens the target/input image specified by these parameters or
     * creates a new (in-memory) image if no input image was specified.
     *
     * @return {@link BufferedImage} representation of the image.
     */
    public BufferedImage openTargetImage() {

        BufferedImage targetImage = null;

        if (in != null) {
            targetImage = Utils.openImage(in);
        }

        if (targetImage == null) {
            final double derivedScale = getScale();
            final int targetWidth = (int) (derivedScale * width);
            final int targetHeight = (int) (derivedScale * height);
            targetImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        }

        return targetImage;
    }

    /**
     * @return string representation of these parameters (only non-default values are included).
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append('{');

        if (readTileSpecsFromUrl()) {
            sb.append("tileSpecUrl='").append(tileSpecUrl).append("', ");
        } else if (tileSpecs != null) {
            sb.append("tileSpecs=[").append(tileSpecs.size()).append(" items], ");
        }

        if (x != DEFAULT_X_AND_Y) {
            sb.append("x=").append(x).append(", ");
        }

        if (y != DEFAULT_X_AND_Y) {
            sb.append("y=").append(y).append(", ");
        }

        if (width != DEFAULT_HEIGHT_AND_WIDTH) {
            sb.append("width=").append(width).append(", ");
        }

        if (height != DEFAULT_HEIGHT_AND_WIDTH) {
            sb.append("height=").append(height).append(", ");
        }

        if (scale != DEFAULT_SCALE) {
            sb.append("scale=").append(scale).append(", ");
        }

        if (meshCellSize != DEFAULT_MESH_CELL_SIZE) {
            sb.append("res=").append(meshCellSize).append(", ");
        }

        if (quality != DEFAULT_QUALITY) {
            sb.append("quality=").append(quality).append(", ");
        }

        if (areaOffset) {
            sb.append("areaOffset=true, ");
        }

        if (convertToGray) {
            sb.append("convertToGray=true, ");
        }
        
        if (doFilter) {
            sb.append("filter=true, ");
        }

        if (in != null) {
            sb.append("in='").append(in).append("', ");
        }

        if (out != null) {
            sb.append("out='").append(out).append("', ");
        }

        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2); // trim last ", "
        }

        sb.append('}');

        return sb.toString();
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -jar render.jar");
    }

    private void parseTileSpecs()
            throws IllegalArgumentException {

        if (readTileSpecsFromUrl()) {

            final URI uri = Utils.convertPathOrUriStringToUri(tileSpecUrl);

            final URL urlObject;
            try {
                urlObject = uri.toURL();
            } catch (final Throwable t) {
                throw new IllegalArgumentException("failed to convert URI '" + uri +
                                                   "' built from tile specification URL parameter '" + tileSpecUrl + "'", t);
            }

            InputStream urlStream = null;
            try {
                try {
                    urlStream = urlObject.openStream();
                } catch (final Throwable t) {
                    throw new IllegalArgumentException("failed to load tile specification from " + urlObject, t);
                }

                final Reader reader = new InputStreamReader(urlStream);
                final Type collectionType = new TypeToken<List<TileSpec>>() {}.getType();
                try {
                    tileSpecs = JsonUtils.GSON.fromJson(reader, collectionType);
                } catch (final Throwable t) {
                    throw new IllegalArgumentException(
                            "failed to parse tile specification loaded from " + urlObject, t);
                }
            } finally {
                if (urlStream != null) {
                    try {
                        urlStream.close();
                    } catch (final IOException e) {
                        LOG.warn("failed to close " + uri + ", ignoring error", e);
                    }
                }
            }
        }
    }

    private boolean readTileSpecsFromUrl() {
        return ((tileSpecUrl != null) && (! hasTileSpecs()));
    }

    /**
     * If a parametersUrl has been specified, load those values and apply them to any default or unset values in
     * this set of values.  This is a little messy because many of the values are primitives instead of objects
     * which makes it impossible to definitively identify unset values.
     * For now, if a primitive value is set to its default it will be overridden by the parametersUrl value.
     *
     * @throws IllegalArgumentException
     */
    private void applyBaseParameters() throws IllegalArgumentException {

        if (parametersUrl != null) {
            final RenderParameters baseParameters = loadParametersUrl();

            tileSpecUrl = mergedValue(tileSpecUrl, baseParameters.tileSpecUrl);
            in = mergedValue(in, baseParameters.in);
            out = mergedValue(out, baseParameters.out);
            meshCellSize = mergedValue(meshCellSize, baseParameters.meshCellSize, DEFAULT_MESH_CELL_SIZE);
            x = mergedValue(x, baseParameters.x, DEFAULT_X_AND_Y);
            y = mergedValue(y, baseParameters.y, DEFAULT_X_AND_Y);
            width = mergedValue(width, baseParameters.width, DEFAULT_HEIGHT_AND_WIDTH);
            height = mergedValue(height, baseParameters.height, DEFAULT_HEIGHT_AND_WIDTH);
            scale = mergedValue(scale, baseParameters.scale, DEFAULT_SCALE);
            areaOffset = mergedValue(areaOffset, baseParameters.areaOffset, false);
            convertToGray = mergedValue(convertToGray, baseParameters.convertToGray, false);
            numberOfThreads = mergedValue(numberOfThreads, baseParameters.numberOfThreads, DEFAULT_NUMBER_OF_THREADS);
            skipInterpolation = mergedValue(skipInterpolation, baseParameters.skipInterpolation, false);

            if (quality == DEFAULT_QUALITY) {
                quality = baseParameters.quality;
            }

            doFilter = mergedValue(doFilter, baseParameters.doFilter, false);
            
            tileSpecs.addAll(baseParameters.tileSpecs);
        }
    }

    private RenderParameters loadParametersUrl() throws IllegalArgumentException {

        final URI uri = Utils.convertPathOrUriStringToUri(parametersUrl);

        final URL urlObject;
        try {
            urlObject = uri.toURL();
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to convert URI '" + uri + "'", t);
        }

        RenderParameters parameters;
        InputStream urlStream = null;
        try {
            try {
                urlStream = urlObject.openStream();
            } catch (final Throwable t) {
                throw new IllegalArgumentException("failed to load render parameters from " + urlObject, t);
            }

            parameters = parseJson(new InputStreamReader(urlStream));

        } finally {
            if (urlStream != null) {
                try {
                    urlStream.close();
                } catch (final IOException e) {
                    LOG.warn("failed to close " + uri + ", ignoring error", e);
                }
            }
        }

        return parameters;
    }

    private String mergedValue(final String currentValue,
                               final String baseValue) {
        String value = currentValue;
        if (currentValue == null) {
            value = baseValue;
        }
        return value;
    }

    private int mergedValue(final int currentValue,
                            final int baseValue,
                            final int defaultValue) {
        int value = currentValue;
        if (currentValue == defaultValue) {
            value = baseValue;
        }
        return value;
    }

    private double mergedValue(final double currentValue,
                               final double baseValue,
                               final double defaultValue) {
        double value = currentValue;
        if (currentValue == defaultValue) {
            value = baseValue;
        }
        return value;
    }

    private boolean mergedValue(final boolean currentValue,
                                final boolean baseValue,
                                final boolean defaultValue) {
        boolean value = currentValue;
        if (currentValue == defaultValue) {
            value = baseValue;
        }
        return value;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParameters.class);

    private static final double DEFAULT_MESH_CELL_SIZE = 64;
    private static final double DEFAULT_X_AND_Y = 0;
    private static final int DEFAULT_HEIGHT_AND_WIDTH = 256;
    private static final Double DEFAULT_SCALE = 1.0;
    private static final float DEFAULT_QUALITY = 0.85f;
    private static final int DEFAULT_NUMBER_OF_THREADS = 1;

	public boolean doFilter()
	{
		return doFilter;
	}

    public void setDoFilter(final Boolean filter) {
        doFilter = filter != null && filter;
    }
}
