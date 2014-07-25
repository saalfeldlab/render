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

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Parameters for render operations.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
@Parameters
public class RenderParameters {

    /** Default GSON instance used to de-serialize tile specifications. */
    public static final Gson DEFAULT_GSON = new GsonBuilder().setPrettyPrinting().create();

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--url", description = "URL to JSON tile spec", required = true)
    private String url;

    @Parameter( names = "--res", description = " Mesh resolution, specified by the desired size of a triangle in pixels", required = false)
    private int res;

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

    @Parameter(names = "--scale", description = "scale factor applied to the target image (overrides --mipmap_level)", required = false)
    private Double scale;

    @Parameter(names = "--mipmap_level", description = "scale level in a mipmap pyramid", required = false)
    private int mipmapLevel;

    @Parameter(names = "--area_offset", description = "add bounding box offset", required = false)
    private boolean areaOffset;

    @Parameter(names = "--quality", description = "JPEG quality float [0, 1]", required = false)
    private float quality;

    /** List of tile specifications parsed from --url or deserialized directly from json. */
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
             DEFAULT_MIPMAP_LEVEL);
    }

    public RenderParameters(String url,
                            double x,
                            double y,
                            int width,
                            int height,
                            int mipmapLevel) {
        this.url = url;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mipmapLevel = mipmapLevel;

        this.help = false;
        this.res = DEFAULT_RESOLUTION;
        this.in = null;
        this.out = null;
        this.scale = null;
        this.areaOffset = false;
        this.quality = DEFAULT_QUALITY;

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
    public static RenderParameters parseCommandLineArgs(String[] args) throws IllegalArgumentException {
        RenderParameters parameters = new RenderParameters();
        parameters.setCommander();
        try {
            parameters.jCommander.parse(args);
        } catch (Throwable t) {
            throw new IllegalArgumentException("failed to parse command line arguments", t);
        }
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
    public static RenderParameters parseJson(String jsonText) throws IllegalArgumentException {
        RenderParameters parameters;
        try {
            parameters = DEFAULT_GSON.fromJson(jsonText, RenderParameters.class);
        } catch (Throwable t) {
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
    public static RenderParameters parseJson(Reader jsonReader) throws IllegalArgumentException {
        RenderParameters parameters;
        try {
            parameters = DEFAULT_GSON.fromJson(jsonReader, RenderParameters.class);
        } catch (Throwable t) {
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
    public static RenderParameters parseJson(File jsonFile) throws IllegalArgumentException {

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
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("render parameters json file " + jsonFile.getAbsolutePath() +
                                               " does not exist", e);
        }

        RenderParameters parameters;
        try {
            parameters = RenderParameters.parseJson(parametersReader);
        } finally {
            try {
                parametersReader.close();
            } catch (IOException e) {
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

    public int getRes() {
        return res;
    }

    public String getOut() {
        return out;
    }

    public URI getOutUri() throws IllegalArgumentException {
        if ((outUri == null) && (out != null)) {
            try {
                outUri = new URI(out);
            } catch (URISyntaxException e) {
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

    public double getScale() {
        if (scale == null) {
            scale = 1.0 / (1L << mipmapLevel);
        }
        return scale;
    }

    public boolean isAreaOffset() {
        return areaOffset;
    }

    public float getQuality() {
        return quality;
    }

    public List<TileSpec> getTileSpecs() {
        return tileSpecs;
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

        for (TileSpec tileSpec : tileSpecs) {
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
            sb.append("url='").append(url).append("', ");
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

        if (scale != null) {
            sb.append("scale=").append(scale).append(", ");
        }

        if (mipmapLevel != DEFAULT_MIPMAP_LEVEL) {
            sb.append("mipmapLevel=").append(mipmapLevel).append(", ");
        }

        if (res != DEFAULT_RESOLUTION) {
            sb.append("res=").append(res).append(", ");
        }

        if (quality != DEFAULT_QUALITY) {
            sb.append("quality=").append(quality).append(", ");
        }

        if (areaOffset) {
            sb.append("areaOffset=true, ");
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
        return DEFAULT_GSON.toJson(this);
    }

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -jar render.jar");
    }

    private void parseTileSpecs()
            throws IllegalArgumentException {

        if (readTileSpecsFromUrl()) {

            final URI uri = Utils.convertPathOrUriStringToUri(url);

            final URL urlObject;
            try {
                urlObject = uri.toURL();
            } catch (Throwable t) {
                throw new IllegalArgumentException("failed to convert URI '" + uri +
                                                   "' built from tile specification URL parameter '" + url + "'", t);
            }

            final InputStream urlStream;
            try {
                urlStream = urlObject.openStream();
            } catch (Throwable t) {
                throw new IllegalArgumentException("failed to load tile specification from " + urlObject, t);
            }

            final Reader reader = new InputStreamReader(urlStream);
            final Type collectionType = new TypeToken<List<TileSpec>>(){}.getType();
            try {
                tileSpecs = DEFAULT_GSON.fromJson(reader, collectionType);
            } catch (Throwable t) {
                throw new IllegalArgumentException("failed to parse tile specification loaded from " + urlObject, t);
            }
        }
    }

    private boolean readTileSpecsFromUrl() {
        return ((url != null) && ((tileSpecs == null) || (tileSpecs.size() == 0)));
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParameters.class);

    private static final int DEFAULT_RESOLUTION = 64;
    private static final int DEFAULT_X_AND_Y = 0;
    private static final int DEFAULT_HEIGHT_AND_WIDTH = 256;
    private static final int DEFAULT_MIPMAP_LEVEL = 0;
    private static final float DEFAULT_QUALITY = 0.85f;
}
