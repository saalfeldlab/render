package org.janelia.alignment.trakem2;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlElementRefs;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMesh;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Utility to convert TrakEM2 XML project patch (tile) data into
 * a JSON format suitable for use by the Render Web Service.
 *
 * @author Eric Trautman
 */
public class Converter {

    public static void main(final String[] args) {

        boolean validateConvertedTileSpecs = false;
        boolean useTitleForTileId = false;
        if (args.length > 2) {

            final File xmlFile = new File(args[0]);
            final String rawProjectPath = args[1];
            final File jsonFile = new File(args[2]);
            if (args.length > 3) {
                validateConvertedTileSpecs = Boolean.valueOf(args[3]);
            }
            if (args.length >4) {
            	useTitleForTileId = Boolean.valueOf(args[4]);
            }

            Converter.xmlToJson(xmlFile, rawProjectPath, jsonFile, validateConvertedTileSpecs,useTitleForTileId);

        } else {

            System.err.println("\nUSAGE ERROR: missing parameters\n\nSYNTAX: java " + Converter.class +
                               " <input XML file> <project base path> <output JSON file> [validate tile spec flag] [use_title_for_tileId]");

        }
    }

    public static void xmlToJson(final File trakEM2XmlFile,
            final String projectPath,
            final File jsonFile,
            final boolean validateConvertedTileSpecs) {
    	xmlToJson(trakEM2XmlFile,projectPath,jsonFile,validateConvertedTileSpecs,false);
    }
    /**
     * Converts the specified TrakEM2 XML project file data into Render Service JSON.
     *
     * @param  trakEM2XmlFile              the TrakEM2 project file to parse.
     *
     * @param  projectPath                 the root path for the TrakEM2 project used to generate full
     *                                     paths for all images and masks
     *                                     (e.g. /groups/saalfeld/saalfeldlab/fly-bock-63-elastic/intensity-corrected/)
     *
     * @param  jsonFile                    the file for storing the resulting JSON output.
     *
     * @param  validateConvertedTileSpecs  indicates whether tile specs should be validated
     *                                     (only set this to true if conversion process has access
     *                                     to image and mask filesystem).
     */
    public static void xmlToJson(final File trakEM2XmlFile,
                                 final String projectPath,
                                 final File jsonFile,
                                 final boolean validateConvertedTileSpecs,
                                 final boolean useTitleForTileId) {
        FileInputStream trakEm2XmlStream = null;
        FileOutputStream jsonStream = null;
        try {
            trakEm2XmlStream = new FileInputStream(trakEM2XmlFile);
            jsonStream = new FileOutputStream(jsonFile);
            LOG.info("xmlToJson: reading TrakEM2 XML from " + trakEM2XmlFile.getAbsolutePath());
            Converter.xmlToJson(trakEm2XmlStream, projectPath, jsonStream, validateConvertedTileSpecs);
            LOG.info("xmlToJson: wrote JSON to " + jsonFile.getAbsolutePath());
        } catch (final Throwable t) {
            LOG.error("failed to convert " + trakEM2XmlFile.getAbsolutePath(), t);
        } finally {
            if (trakEm2XmlStream != null) {
                try {
                    trakEm2XmlStream.close();
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
            if (jsonStream != null) {
                try {
                    jsonStream.close();
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void xmlToJson(final InputStream trakEM2XmlStream,
            final String rawProjectPath,
            final OutputStream jsonStream,
            final boolean validateConvertedTileSpecs)
     throws JAXBException, IOException, ParserConfigurationException, SAXException {
    	xmlToJson(trakEM2XmlStream,rawProjectPath,jsonStream,validateConvertedTileSpecs,false);	
    }
    
    /**
     * Converts the specified TrakEM2 XML project file data into Render Service JSON.
     *
     * @param  trakEM2XmlStream            the TrakEM2 project data stream to parse.
     *
     * @param  rawProjectPath              the root path for the TrakEM2 project used to generate full
     *                                     paths for all images and masks
     *                                     (e.g. /groups/saalfeld/saalfeldlab/fly-bock-63-elastic/intensity-corrected/)
     *
     * @param  jsonStream                  the stream for storing the resulting JSON output.
     *
     * @param  validateConvertedTileSpecs  indicates whether tile specs should be validated
     *                                     (only set this to true if conversion process has access
     *                                     to image and mask filesystem).
     * @throws JAXBException
     *   if the the specified XML stream cannot be parsed.
     *
     * @throws IOException
     *   if JSON data cannot be written to the specified output stream.
     */
    public static void xmlToJson(final InputStream trakEM2XmlStream,
                                 final String rawProjectPath,
                                 final OutputStream jsonStream,
                                 final boolean validateConvertedTileSpecs,
                                 final boolean useTitleForTileId)
            throws JAXBException, IOException, ParserConfigurationException, SAXException {

        final File baseProjectDirectory = new File(rawProjectPath).getCanonicalFile();
        final String projectPath = baseProjectDirectory.getAbsolutePath().replace('\\','/') + '/';

        final JAXBContext ctx = JAXBContext.newInstance(TrakEM2.class);
        final Unmarshaller unmarshaller = ctx.createUnmarshaller();

        // patch notification callback
        final T2Layer.Listener patchListener = new T2Layer.Listener() {

            @Override
            public void handlePatch(final String baseMaskPath,
                                    final T2Layer layer,
                                    final T2Patch patch,
                                    final boolean isFirstPatch) {

                final StringBuilder json = new StringBuilder(16 * 1024);
                if (! isFirstPatch) {
                    json.append(",\n");
                }

                final TileSpec tileSpec = patch.getTileSpec(projectPath, baseMaskPath, layer.z,useTitleForTileId);
                if (validateConvertedTileSpecs) {
                    tileSpec.validate();
                }

                json.append(tileSpec.toJson());

                try {
                    jsonStream.write(json.toString().getBytes());
                } catch (final IOException e) {
                    throw new RuntimeException("failed to write to JSON stream", e);
                }
            }
        };

        // install the callback on all patch instances
        unmarshaller.setListener(new Unmarshaller.Listener() {

            private final int patchBatchSize = 200;
            private final int layerBatchSize = 10;

            private String baseMaskPath = "";
            private long layerSetStartTime;

            private long layerCount = 0;
            private long layerSetPatchCount = 0;
            private long layerPatchCount = 0;
            private long layerStartTime;

            @Override
            public void beforeUnmarshal(final Object target, final Object parent) {

                if (target instanceof T2Patch) {

                    layerSetPatchCount++;
                    layerPatchCount++;

                } else if (target instanceof T2Layer) {

                    final T2Layer layer = (T2Layer) target;
                    final boolean isFirstLayer = (layerCount == 0);
                    layer.addPatchListener(patchListener, baseMaskPath, isFirstLayer);

                    layerCount++;
                    layerPatchCount = 0;
                    layerStartTime = System.currentTimeMillis();

                } else if (target instanceof T2LayerSet) {

                    layerSetStartTime = System.currentTimeMillis();

                }

            }

            @Override
            public void afterUnmarshal(final Object target, final Object parent) {

                if (target instanceof T2Patch) {

                    if (layerPatchCount % patchBatchSize == 0) {
                        logStats("Patch", layerStartTime, layerPatchCount, false);
                    }

                } else if (target instanceof T2Layer) {

                    final T2Layer layer = (T2Layer) target;
                    layer.removePatchListener();
                    if ((layerCount == 1) || (layerCount % layerBatchSize == 0)) {
                        logStats("Layer", layerStartTime, layerPatchCount, false);
                    }

                } else if (target instanceof T2LayerSet) {

                    logStats("LayerSet", layerSetStartTime, layerSetPatchCount, true);

                    layerSetPatchCount = 0;
                    layerCount = 0;

                } else if (target instanceof Project) {

                    baseMaskPath = ((Project) target).getBaseMaskPath(baseProjectDirectory);
                    LOG.info("afterUnmarshal (Project): set baseMaskPath to " + baseMaskPath);

                }
            }

            private void logStats(final String elementName,
                                  final long startTime,
                                  final long patchCount,
                                  final boolean isTotalStats) {
                final long elapsedTime = System.currentTimeMillis() - startTime;
                final long patchesPerSecond = (long) (patchCount / (elapsedTime / 1000.0));
                final StringBuilder sb = new StringBuilder(128);
                sb.append("afterUnmarshal (").append(elementName).append("): converted ").append(patchCount);
                sb.append(" patches in ");
                if (isTotalStats) {
                    sb.append(layerCount).append(" layers, total ");
                } else {
                    sb.append("layer ").append(layerCount).append(", layer ");
                }
                sb.append("patch conversion rate is ").append(patchesPerSecond).append(" patches per second");
                LOG.info(sb.toString());
            }
        });

        // create a new XML parser
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        final XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setContentHandler(unmarshaller.getUnmarshallerHandler());

        jsonStream.write("[\n".getBytes());

        final InputSource inputSource = new InputSource(trakEM2XmlStream);
        reader.parse(inputSource);

        jsonStream.write("]\n".getBytes());
    }

    private interface Transformable {
        void addToList(List<TransformSpec> list);
        void addToCoordinateTransformList(CoordinateTransformList<CoordinateTransform> list)
                throws Exception;
    }

    @XmlRootElement(name = "ict_transform")
    private static class IctTransform implements Transformable {
        @XmlAttribute(name = "class") public String className;
        @XmlAttribute(name = "data")  public String data;
        @Override
        public void addToList(final List<TransformSpec> list) {
            list.add(new LeafTransformSpec(className, data));
        }
        @Override
        public void addToCoordinateTransformList(final CoordinateTransformList<CoordinateTransform> list)
                throws Exception {
            final CoordinateTransform ct = (CoordinateTransform) Class.forName(className).newInstance();
            ct.init(data);
            list.add(ct);
        }
    }

    @XmlRootElement(name = "iict_transform")
    private static class IictTransform extends IctTransform {
    }

    @XmlRootElement(name = "ict_transform_list")
    private static class IctTransformList implements Transformable {

        @XmlElementRefs({
                                @XmlElementRef(type = IctTransform.class),
                                @XmlElementRef(type = IictTransform.class),
                                @XmlElementRef(type = IctTransformList.class)
                        })
        public List<Transformable> ictList;
        @Override
        public void addToList(final List<TransformSpec> list) {
            for (final Transformable t : ictList) {
                t.addToList(list);
            }
        }
        @Override
        public void addToCoordinateTransformList(final CoordinateTransformList<CoordinateTransform> list)
                throws Exception {
            for (final Transformable t : ictList) {
                t.addToCoordinateTransformList(list);
            }
        }
    }

    @XmlRootElement(name = "t2_patch")
    private static class T2Patch {
        @XmlAttribute(name = "oid")              public String oid;
        @XmlAttribute(name = "transform")        public String transform;
        @XmlAttribute(name = "file_path")        public String filePath;
        @XmlAttribute(name = "min")              public Double min;
        @XmlAttribute(name = "max")              public Double max;
        @XmlAttribute(name = "o_width")          public Double oWidth;
        @XmlAttribute(name = "o_height")         public Double oHeight;
        @XmlAttribute(name = "mres")             public Integer meshResolution;
        @XmlAttribute(name = "alpha_mask_id")    public String alphaMaskId;
        @XmlAttribute(name = "title")			 public String title;
        @XmlElement(name = "ict_transform_list") public IctTransformList transforms;

        private AffineTransform fullCoordinateTransform;

        // projectPath  = '/groups/saalfeld/saalfeldlab/fly-bock-63-elastic/intensity-corrected/'
        // baseMaskPath = '/groups/saalfeld/saalfeldlab/fly-bock-63-elastic/intensity-corrected/trakem2.1396292726179.1972921220.95381465/trakem2.masks/'
        public TileSpec getTileSpec(final String projectPath,
                final String baseMaskPath,
                final double z) {
        	return getTileSpec(projectPath,baseMaskPath,z,false);
        }
        public TileSpec getTileSpec(final String projectPath,
                                    final String baseMaskPath,
                                    final double z,
                                    final boolean useTitleForTileId ) {

            final String imageUrl;
            // TODO: make sure we don't need to check for Windows paths
            if (filePath.startsWith("/")) {
                imageUrl = "file:" + filePath;
            } else {
                imageUrl = "file:" + projectPath + filePath;
            }

            String maskUrl = null;
            if (alphaMaskId != null) {
                maskUrl = "file:" + baseMaskPath + createIdPath(alphaMaskId, oid, ".zip");
            }

            final List<TransformSpec> transformList = new ArrayList<>();

            if (transforms != null) {
                // add all nested ict transforms
                transforms.addToList(transformList);
            }

            // add final world coordinate transform
            final AffineModel2D affine = new AffineModel2D();
            affine.set(getFullCoordinateTransform());
            final LeafTransformSpec worldCoordinateTransform =
                    new LeafTransformSpec(affine.getClass().getCanonicalName(), affine.toDataString());
            transformList.add(worldCoordinateTransform);

            // convert to tile spec (which can ultimately be serialized as json)
            final TileSpec tileSpec = new TileSpec();
            if (useTitleForTileId) tileSpec.setTileId(title);
            else tileSpec.setTileId(oid);
            tileSpec.setZ(z);

            tileSpec.setWidth(oWidth);
            tileSpec.setHeight(oHeight);

            // TODO: determine why these min and max values differ from what beanshell script pulls
            tileSpec.setMinIntensity(min); // xml: 48.0, beanshell: 0.0
            tileSpec.setMaxIntensity(max); // xml: 207.0, beanshell: 255.0

            tileSpec.putMipmap(0, new ImageAndMask(imageUrl, maskUrl));
            tileSpec.addTransformSpecs(transformList);

            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            return tileSpec;
        }

        // Adapted from getFullCoordinateTransform method in TrakEM2 Patch class implementation.
        // See https://github.com/fiji/TrakEM2/blob/6cb318e3ca077e217444158090ab607223cf921c/TrakEM2_/src/main/java/ini/trakem2/display/Patch.java#L1389-L1412
        private AffineTransform getFullCoordinateTransform() {

            if (fullCoordinateTransform == null) {

                final double[] d = getPatchTransformData();
                fullCoordinateTransform = new AffineTransform(d[0], d[1], d[2], d[3], d[4], d[5]);

                if (transforms != null) {
                    final Rectangle box;
                    try {
                        final CoordinateTransformList<CoordinateTransform> ctList = new CoordinateTransformList<>();
                        transforms.addToCoordinateTransformList(ctList);
                        final TransformMesh mesh = new TransformMesh(ctList,
                                                                     meshResolution,
                                                                     oWidth,
                                                                     oHeight);
                        box = mesh.getBoundingBox();
                    } catch (final Exception e) {
                        throw new RuntimeException("failed to build ct list for patch " + oid, e);
                    }
                    fullCoordinateTransform.translate(-box.x, -box.y);
                }
            }

            return fullCoordinateTransform;
        }

        // Extracts data value array from TrakEM2 transform attributes that have the form:
        //      transform="matrix(1.0,0.0,0.0,1.0,0.0,0.0)"
        private double[] getPatchTransformData() {
            final int beginIndex = transform.indexOf('(') + 1;
            final int endIndex = transform.indexOf(')');
            final String csvValue = transform.substring(beginIndex, endIndex);
            final String[] stringValues = CSV_PATTERN.split(csvValue, 0);
            return new double[] {
                    Double.parseDouble(stringValues[0]),
                    Double.parseDouble(stringValues[1]),
                    Double.parseDouble(stringValues[2]),
                    Double.parseDouble(stringValues[3]),
                    Double.parseDouble(stringValues[4]),
                    Double.parseDouble(stringValues[5])
            };
        }

        // Copied from createIdPath method in TrakEM2 FSLoader class implementation.
        // See https://github.com/fiji/TrakEM2/blob/6cb318e3ca077e217444158090ab607223cf921c/TrakEM2_/
        //                        src/main/java/ini/trakem2/persistence/FSLoader.java
        private String createIdPath(final String sid,
                                    final String filename,
                                    final String ext) {
            final StringBuilder sf = new StringBuilder(((sid.length() * 3) / 2) + 1);
            final int len = sid.length();
            for (int i=1; i<=len; i++) {
                sf.append(sid.charAt(i-1));
                if (0 == i % 2 && len != i) {
                    sf.append('/');
                }
            }
            return sf.append('.').append(filename).append(ext).toString();
        }
    }

    @XmlRootElement(name = "t2_layer")
    private static class T2Layer {
        @XmlAttribute(name = "z")  public Double z;

        // This list is not a real list; instead it notifies a listener whenever JAXB has unmarshalled the next patch.
        @XmlElementRef()
        public List<T2Patch> patches;

        public long numberOfPatches = 0;

        public void addPatchListener(final Listener l,
                                     final String baseMaskPath,
                                     final boolean isFirstLayer) {
            patches = new ArrayList<T2Patch>() {
                @Override
                public boolean add(final T2Patch patch) {
                    final boolean isFirstPatch = isFirstLayer && (numberOfPatches == 0);
                    l.handlePatch(baseMaskPath, T2Layer.this, patch, isFirstPatch);
                    numberOfPatches++;
                    return false;
                }
            };
        }

        public void removePatchListener() {
            patches = null;
        }

        // This listener is invoked every time a new patch is unmarshalled.
        public interface Listener {
            void handlePatch(String baseMaskPath,
                             T2Layer layer,
                             T2Patch patch,
                             boolean isFirstPatch);
        }

    }

    @SuppressWarnings("UnusedDeclaration")
    @XmlRootElement(name = "t2_layer_set")
    private static class T2LayerSet {
        // TODO: confirm it is okay to ignore layer set transform
        // @XmlAttribute(name = "transform")  public String transform;
        @XmlElementRef() public List<T2Layer> layers;
    }

    @XmlRootElement(name = "project")
    private static class Project {
        //@XmlAttribute(name = "title") public String title;
        @XmlAttribute(name = "unuid") public String unuid;
        // ignore project mesh_resolution attribute because it gets explicitly set for each patch

        public String getBaseMaskPath(final File baseProjectDirectory) {
            final File projectUnuidDirectory = new File(baseProjectDirectory, "trakem2." + unuid);
            final File baseMaskDirectory = new File(projectUnuidDirectory, "trakem2.masks");
            return baseMaskDirectory.getAbsolutePath().replace('\\','/') + '/';
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    @XmlRootElement(name = "trakem2")
    private static class TrakEM2 {
        @XmlElementRef() public Project project;
        @XmlElementRef() public T2LayerSet layerSet;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Converter.class);

    private static final Pattern CSV_PATTERN = Pattern.compile(",");
}
