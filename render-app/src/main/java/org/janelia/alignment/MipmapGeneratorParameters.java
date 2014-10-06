package org.janelia.alignment;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.reflect.TypeToken;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Parameters for mipmap generation.
 *
 * @author Eric Trautman
 */
@Parameters
public class MipmapGeneratorParameters {

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--root", description = "Root directory path for all generated mipmaps", required = true)
    private String rootDirectoryPath;

    @Parameter(names = "--level", description = "Highest scale level of mipmaps to be generated (default is 3)", required = false)
    private int mipmapLevel;

    @Parameter(names = "--format", description = "Mipmap file format (jpg is default)", required = false)
    private String format;

    @Parameter(names = "--quality", description = "JPEG quality float [0, 1] (default is 0.85)", required = false)
    private float quality;

    @Parameter(names = "--url", description = "URL referencing input tile spec data (JSON)", required = false)
    private String url;

    @Parameter(names = "--out", description = "Output file for updated JSON tile spec data ", required = false)
    private String outputFileName;

    @Parameter(names = "--consolidate_masks", description = "Consolidate equivalent zipped TrakEM2 mask files", required = false)
    private boolean consolidateMasks;

    @Parameter(names = "--force_box", description = "Force calculation of tile bounding box attributes", required = false)
    private boolean forceBoxCalculation;

    /** List of tile specifications parsed from --url or deserialized directly from json. */
    private List<TileSpec> tileSpecs;

    private transient JCommander jCommander;
    private transient boolean initialized;
    private transient File rootDirectory;
    private transient File outputFile;

    public MipmapGeneratorParameters() {
        this(null);
    }

    public MipmapGeneratorParameters(String rootDirectoryPath) {
        this.help = false;
        this.rootDirectoryPath = rootDirectoryPath;
        this.mipmapLevel = DEFAULT_MIPMAP_LEVEL;
        this.format = Utils.JPEG_FORMAT;
        this.quality = DEFAULT_QUALITY;
        this.url = null;
        this.outputFileName = null;
        this.consolidateMasks = false;
        this.forceBoxCalculation = false;

        this.tileSpecs = new ArrayList<TileSpec>();

        this.jCommander = null;
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
    public static MipmapGeneratorParameters parseCommandLineArgs(String[] args) throws IllegalArgumentException {
        MipmapGeneratorParameters parameters = new MipmapGeneratorParameters();
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
     * @param  jsonReader  reader to parse.
     *
     * @return parameters instance populated by parsing the specified json reader's stream.
     *
     * @throws IllegalArgumentException
     *   if the json cannot be parsed.
     */
    public static MipmapGeneratorParameters parseJson(Reader jsonReader) throws IllegalArgumentException {
        MipmapGeneratorParameters parameters;
        try {
            parameters = JsonUtils.GSON.fromJson(jsonReader, MipmapGeneratorParameters.class);
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
    public static MipmapGeneratorParameters parseJson(File jsonFile) throws IllegalArgumentException {

        if (! jsonFile.exists()) {
            throw new IllegalArgumentException("mipmap generator parameters json file " + jsonFile.getAbsolutePath() +
                                               " does not exist");
        }

        if (! jsonFile.canRead()) {
            throw new IllegalArgumentException("mipmap generator parameters json file " + jsonFile.getAbsolutePath() +
                                               " is not readable");
        }

        FileReader parametersReader;
        try {
            parametersReader = new FileReader(jsonFile);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("mipmap generator parameters json file " + jsonFile.getAbsolutePath() +
                                               " does not exist", e);
        }

        MipmapGeneratorParameters parameters;
        try {
            parameters = MipmapGeneratorParameters.parseJson(parametersReader);
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

    public File getRootDirectory() {
        if (rootDirectory == null) {
            rootDirectory = getCanonicalFile(rootDirectoryPath);
        }
        return rootDirectory;
    }

    public int getMipmapLevel() {
        return mipmapLevel;
    }

    public String getFormat() {
        return format;
    }

    public float getQuality() {
        return quality;
    }

    public File getOutputFile() {
        if (outputFile == null) {
            outputFile = getCanonicalFile(outputFileName);
        }
        return outputFile;
    }

    public boolean consolidateMasks() {
        return consolidateMasks;
    }

    public boolean forceBoxCalculation() {
        return forceBoxCalculation;
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

        final File rootDirectory = getRootDirectory();
        if (! rootDirectory.exists()) {
            throw new IllegalArgumentException("missing root mipmap directory " + rootDirectory.getAbsolutePath());
        }

        if (! rootDirectory.canWrite()) {
            throw new IllegalArgumentException("write access denied for root mipmap directory " + rootDirectory.getAbsolutePath());
        }

        if ((mipmapLevel < 1) || (mipmapLevel > 10)) {
            throw new IllegalArgumentException("mipmap level (" + mipmapLevel + ") should be between 1 and 10");
        }

        if ((quality < 0.0) || (quality > 1.0)) {
            throw new IllegalArgumentException("quality (" + quality + ") should be between 0.0 and 1.0");
        }

        final File outputFile = getOutputFile();
        if (outputFile.exists()) {
            if (! rootDirectory.canWrite()) {
                throw new IllegalArgumentException("write access denied for output file " + outputFile.getAbsolutePath());
            }
        } else {
            final File outputDirectory = outputFile.getParentFile();
            if (outputDirectory != null) {
                if (! outputDirectory.canWrite()) {
                    throw new IllegalArgumentException("write access denied for output directory " + outputDirectory.getAbsolutePath());
                }
            }
        }

        if (! initialized) {
            throw new IllegalStateException("derived parameters have not been initialized");
        }

        for (TileSpec tileSpec : tileSpecs) {
            tileSpec.validate();
        }
    }

    /**
     * @return string representation of these parameters (only non-default values are included).
     */
    @Override
    public String toString() {
        return "MipmapGeneratorParameters{" +
               "rootDirectoryPath='" + rootDirectoryPath + '\'' +
               ", mipmapLevel=" + mipmapLevel +
               ", format='" + format + '\'' +
               ", quality=" + quality +
               ", url='" + url + '\'' +
               ", outputFileName='" + outputFileName + '\'' +
               ", consolidateMasks=" + consolidateMasks +
               ", numberOfTileSpecs=" + numberOfTileSpecs() +
               ", initialized=" + initialized +
               '}';
    }

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp render-app.jar " + MipmapGenerator.class.getName());
    }

    private File getCanonicalFile(String name) {
        File file = new File(name);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            LOG.warn("failed to derive canonical file for '" + name + "', ignoring error", e);
        }
        return file;
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
                tileSpecs = JsonUtils.GSON.fromJson(reader, collectionType);
            } catch (Throwable t) {
                throw new IllegalArgumentException("failed to parse tile specification loaded from " + urlObject, t);
            }
        }
    }

    private boolean readTileSpecsFromUrl() {
        return ((url != null) && (! hasTileSpecs()));
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapGeneratorParameters.class);

    private static final int DEFAULT_MIPMAP_LEVEL = 3;
    private static final float DEFAULT_QUALITY = 0.85f;
}
