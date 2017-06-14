package org.janelia.acquire.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.render.client.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Input parameters for montage tools.
 *
 * See <a href="https://github.com/khaledkhairy/EM_aligner/blob/master/matlab_compiled/sample_montage_input.json">
 *     https://github.com/khaledkhairy/EM_aligner/blob/master/matlab_compiled/sample_montage_input.json
 * </a>
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class MontageParameters {

    private AlignmentRenderCollection source_collection;
    private AlignmentRenderCollection target_collection;
    private Map<String, Object> target_point_match_collection;
    private Map<String, Object> SURF_options;
    private Map<String, Object> solver_options;
    private String image_filter;
    private int ncpus;
    private String renderer_client;
    private String scratch;
    /** The z value used for render web service API calls. */
    private Double section_number;
    private final Integer verbose;

    public MontageParameters() {
        this.verbose = 1;
    }

    public AlignmentRenderCollection getSourceCollection() {
        return source_collection;
    }

    public void setSourceCollection(final AlignmentRenderCollection sourceCollection) {
        this.source_collection = sourceCollection;
    }

    public AlignmentRenderCollection getTargetCollection() {
        return target_collection;
    }

    public void setTargetCollection(final AlignmentRenderCollection targetCollection) {
        this.target_collection = targetCollection;
    }

    public void setSectionNumber(final Double sectionNumber) {
        this.section_number = sectionNumber;
    }

    public static MontageParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static MontageParameters load(final String montageParametersFile)
            throws IOException {

        final Path path = Paths.get(montageParametersFile).toAbsolutePath();

        LOG.info("load: entry, path={}", path);

        final MontageParameters montageParameters;
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            montageParameters = MontageParameters.fromJson(reader);
        }
        LOG.info("load: exit");

        return montageParameters;
    }

    public void save(final String toFile)
            throws IOException {

        final Path path = Paths.get(toFile).toAbsolutePath();

        LOG.info("save: entry");

        try (final Writer writer = FileUtil.DEFAULT_INSTANCE.getExtensionBasedWriter(path.toString())) {
            JsonUtils.MAPPER.writeValue(writer, this);
        } catch (final Throwable t) {
            throw new IOException("failed to write " + path, t);
        }

        LOG.info("save: exit, wrote {}", path);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MontageParameters.class);

    private static final JsonUtils.Helper<MontageParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MontageParameters.class);

}
