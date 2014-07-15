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

import java.awt.image.BufferedImage;
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

    @Parameter(names = "--threads", description = "Number of threads to be used", required = false)
    private int numThreads;

    @Parameter(names = "--scale", description = "scale factor applied to the target image (overrides --mipmap_level)", required = false)
    private Double scale;

    @Parameter(names = "--mipmap_level", description = "scale level in a mipmap pyramid", required = false)
    private int mipmapLevel;

    @Parameter(names = "--area_offset", description = "scale level in a mipmap pyramid", required = false)
    private boolean areaOffset;

    @Parameter(names = "--quality", description = "JPEG quality float [0, 1]", required = false)
    private float quality;

    /** List of tile specifications parsed from --url or deserialized directly from json. */
    private List<TileSpec> tileSpecs;

    private transient JCommander jCommander;
    private transient URI outUri;
    private transient boolean initialized;

    public RenderParameters() {
        this.help = false;
        this.url = null;
        this.res = 64;
        this.in = null;
        this.out = null;
        this.x = 0;
        this.y = 0;
        this.width = 256;
        this.height = 256;
        this.numThreads = Runtime.getRuntime().availableProcessors();
        this.scale = null;
        this.mipmapLevel = 0;
        this.areaOffset = false;
        this.quality = 0.85f;

        this.tileSpecs = new ArrayList<TileSpec>();
        this.jCommander = new JCommander(this);
        this.jCommander.setProgramName(PROGRAM_NAME);

        this.outUri = null;
        this.initialized = false;
    }

    public static RenderParameters parse(String[] args) throws IllegalArgumentException {
        RenderParameters parameters = new RenderParameters();
        parameters.jCommander.parse(args);
        parameters.initializeDerivedValues();
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

    @Override
    public String toString() {
        return "{" +
               "help=" + help +
               ", url='" + url + '\'' +
               ", res=" + res +
               ", in='" + in + '\'' +
               ", out='" + out + '\'' +
               ", x=" + x +
               ", y=" + y +
               ", width=" + width +
               ", height=" + height +
               ", numThreads=" + numThreads +
               ", scale=" + scale +
               ", mipmapLevel=" + mipmapLevel +
               ", areaOffset=" + areaOffset +
               ", quality=" + quality +
               '}';
    }

    public String toJson() {
        return DEFAULT_GSON.toJson(this);
    }

    private void parseTileSpecs()
            throws IllegalArgumentException {

        if ((url != null) && ((tileSpecs == null) || (tileSpecs.size() == 0))) {
            final URL urlObject;
            try {
                urlObject = new URL(url);
            } catch (Throwable t) {
                throw new IllegalArgumentException("failed to parse tile specification URL '" + url + "'", t);
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

    private static final String PROGRAM_NAME = "java -jar render.jar " + Render.class.getCanonicalName();
}
