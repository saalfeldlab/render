package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;

/**
 * Parameters for mipmap generation.
 *
 * @author Eric Trautman
 */
public class MipmapParameters implements Serializable {

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for mipmaps (e.g. /nrs/flyTEM/rendered_mipmaps/FAFB00)",
            required = true)
    public String rootDirectory;

    @Parameter(
            names = "--minLevel",
            description = "Minimum mipmap level to generate"
    )
    public Integer minLevel = 1;

    @Parameter(
            names = "--maxLevel",
            description = "Maximum mipmap level to generate"
    )
    public Integer maxLevel = 6;

    @Parameter(
            names = "--format",
            description = "Format for mipmaps (tiff, jpg, png)"
    )
    public String format = Utils.TIFF_FORMAT;

    @Parameter(
            names = "--forceGeneration",
            description = "Regenerate mipmaps even if they already exist",
            arity = 0)
    public boolean forceGeneration = false;

    @Parameter(
            names = "--renderGroup",
            description = "Index (1-n) that identifies portion of layer to render (omit if only one job is being used)"
    )
    public Integer renderGroup = 1;

    @Parameter(
            names = "--numberOfRenderGroups",
            description = "Total number of parallel jobs being used to render this layer (omit if only one job is being used)"
    )
    public Integer numberOfRenderGroups = 1;

    @Parameter(
            names = "--removeAll",
            description = "Indicates that existing mipmaps should be removed (instead of generated)")
    public Boolean removeAll = false;

    public MipmapPathBuilder getMipmapPathBuilder()
            throws IOException {

        final File dir = new File(rootDirectory).getCanonicalFile();

        if (! dir.exists()) {
            throw new IOException("missing root directory " + rootDirectory);
        }

        if (! dir.canWrite()) {
            throw new IOException("not allowed to write to root directory " + rootDirectory);
        }

        String extension = format;
        // map 'tiff' format to 'tif' extension so that {@link ij.io.Opener#openURL(String)} method will work.
        if (Utils.TIFF_FORMAT.equals(format)) {
            extension = "tif";
        }

        return new MipmapPathBuilder(dir.getPath(), maxLevel, extension);
    }

}
