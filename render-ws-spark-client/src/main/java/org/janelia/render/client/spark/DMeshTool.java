package org.janelia.render.client.spark;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;

import mpicbg.util.Timer;

import org.apache.commons.io.IOUtils;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper to run DMesh tool/script for a pair of canvases and return the resulting point matches.
 *
 * @author Eric Trautman
 */
public class DMeshTool implements Serializable {

    private final String script;
    private final String matchParametersPath;
    private final boolean logToolOutput;

    public DMeshTool(final File scriptFile,
                     final File matchParametersFile,
                     final boolean logToolOutput)
            throws IllegalArgumentException {

        this.script = scriptFile.getAbsolutePath();
        this.matchParametersPath = matchParametersFile.getAbsolutePath();
        this.logToolOutput = logToolOutput;

        if (! scriptFile.canExecute()) {
            throw new IllegalArgumentException(this.script + " is not executable");
        }

        if (! matchParametersFile.canRead()) {
            throw new IllegalArgumentException(this.matchParametersPath + " is not readable");
        }
    }

    public CanvasMatches run(final CanvasId p,
                             final File pTileImage,
                             final RenderParameters pRenderParameters,
                             final CanvasId q,
                             final File qTileImage,
                             final RenderParameters qRenderParameters)
            throws IOException, InterruptedException, IllegalStateException {

        validateSizes(p, pRenderParameters, q, qRenderParameters);

        final Timer timer = new Timer();
        timer.start();

        // Build the unusual "z-tileId" parameter for the tool that has the following format:
        //   <pZ>.<pTileId>^<qZ>.<qTileId>

        // The z values are used to distinguish intra-layer tiles from inter-layer tiles.
        // A tileId value of -1 is used to identify special Janelia processing.
        final String zAndIdParameters = getTileZAndId(pRenderParameters) + "^" + getTileZAndId(qRenderParameters);

        final ProcessBuilder processBuilder =
                new ProcessBuilder(script,
                                   zAndIdParameters,
                                   "-ima=" + pTileImage.getAbsolutePath(),
                                   "-imb=" + qTileImage.getAbsolutePath(),
                                   "-prm=" + matchParametersPath,
                                   "-Ta=" + getAffineCoefficientsString(pRenderParameters),
                                   "-Tb=" + getAffineCoefficientsString(qRenderParameters),
                                   "-json").
                        redirectOutput(ProcessBuilder.Redirect.PIPE).
                        redirectError(ProcessBuilder.Redirect.PIPE);

        LOG.info("run: running {}", processBuilder.command());

        final CanvasMatches toolMatches;

        InputStream processStandardOut = null;
        InputStream processStandardError = null;
        try {
            final Process process = processBuilder.start();
            processStandardOut = process.getInputStream();
            processStandardError = process.getErrorStream();

            final String json = IOUtils.toString(processStandardOut);
            final String toolLog = IOUtils.toString(processStandardError);

            final int returnCode = process.waitFor();

            if (returnCode == 0) {

                if (logToolOutput) {
                    LOG.info("tool returned successfully, log is:\n{}", toolLog);
                }

                if ((json != null) && (json.length() > 0)) {
                    toolMatches = CanvasMatches.fromJson(json);
                } else {
                    toolMatches = new CanvasMatches(p.getGroupId(), p.getId(),
                                                    q.getGroupId(), q.getId(),
                                                    new Matches(new double[1][0], new double[1][0], new double[0]));
                }
            } else {
                final String errorMessage = "code " + returnCode +
                                            " returned from process command " + processBuilder.command();
                LOG.warn("{}\ntool log is:\n{}", errorMessage, toolLog);

                throw new IllegalStateException(errorMessage);
            }
        } finally {
            IOUtils.closeQuietly(processStandardOut);
            IOUtils.closeQuietly(processStandardError);
        }

        LOG.info("run: returning {} matches for {} and {}, elapsedTime={}s",
                 toolMatches.size(), p.getId(), q.getId(), (timer.stop() / 1000));

        return new CanvasMatches(p.getGroupId(), p.getId(),
                                 q.getGroupId(), q.getId(),
                                 toolMatches.getMatches());
    }

    private void validateSizes(final CanvasId pCanvasId,
                               final RenderParameters pRenderParameters,
                               final CanvasId qCanvasId,
                               final RenderParameters qRenderParameters)
            throws IllegalStateException {

        final int pWidth = pRenderParameters.getWidth();
        if ((pWidth % 2) == 1) {
            throw new IllegalStateException("canvas " + pCanvasId + " has odd width " + pWidth);
        }

        final int pHeight = pRenderParameters.getHeight();
        if ((pHeight % 2) == 1) {
            throw new IllegalStateException("canvas " + pCanvasId + " has odd height " + pHeight);
        }

        final int qWidth = qRenderParameters.getWidth();
        if (pWidth != qWidth) {
            throw new IllegalStateException("canvas " + pCanvasId + " width (" + pWidth +
                                            ") differs from canvas " + qCanvasId + " width (" + qWidth + ")");
        }

        final int qHeight = qRenderParameters.getHeight();
        if (pHeight != qHeight) {
            throw new IllegalStateException("canvas " + pCanvasId + " height (" + pHeight +
                                            ") differs from canvas " + qCanvasId + " height (" + qHeight + ")");
        }

    }

    private String getTileZAndId(final RenderParameters renderParameters)
            throws IllegalStateException {
        final int integralZ;
        final List<TileSpec> tileSpecs = renderParameters.getTileSpecs();
        if (tileSpecs.size() == 1) {
            integralZ = tileSpecs.get(0).getZ().intValue();
        } else {
            throw new IllegalStateException("Render parameters must have one and only one tile spec.  " +
                                            "Render parameters are " + renderParameters);
        }

        final String magicTileIdForJaneliaProcessing = ".-1";
        return integralZ + magicTileIdForJaneliaProcessing;
    }

    private String getAffineCoefficientsString(final RenderParameters renderParameters)
            throws IllegalArgumentException {

        final List<TileSpec> tileSpecs = renderParameters.getTileSpecs();
        final TileSpec theOnlyTileSpec;
        if (tileSpecs.size() == 1) {
            theOnlyTileSpec = tileSpecs.get(0);
        } else {
            throw new IllegalArgumentException(
                    "To derive affine coefficients, the render parameters must contain one and only one tile spec.");
        }

        final LayoutData layout = theOnlyTileSpec.getLayout();
        if (layout == null) {
            throw new IllegalArgumentException(
                    "To derive affine coefficients, the spec for tile '" + theOnlyTileSpec.getTileId() +
                    "' must contain layout data.");
        }

        return  "1.0,0.0," + layout.getStageX() + ",0.0,1.0," + layout.getStageY();
    }

    public static void main(final String[] args) throws Exception {

        if (args.length != 4) {
            throw new IllegalArgumentException("Expected parameters are: script matchParametersPath pRenderParametersUrl qRenderParametersUrl");
        }

        final String script = args[0];
        final String matchParametersPath = args[1];

        RenderParameters pRenderParameters = null;
        CanvasId pCanvasId = null;
        File pImageFile = null;

        RenderParameters qRenderParameters = null;
        CanvasId qCanvasId = null;
        File qImageFile = null;

        for (int i = 2; i < 4; i++) {

            final RenderParameters renderParameters = RenderParameters.loadFromUrl(args[i]);
            final TileSpec tileSpec = renderParameters.getTileSpecs().get(0);
            final CanvasId canvasId = new CanvasId(tileSpec.getLayout().getSectionId(), tileSpec.getTileId());

            final File renderedTileFile = new File(tileSpec.getTileId() + ".png");
            final BufferedImage targetImage = renderParameters.openTargetImage();
            Render.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
            Utils.saveImage(targetImage,
                            renderedTileFile.getAbsolutePath(),
                            Utils.PNG_FORMAT,
                            false,
                            0.85f);

            if (i == 2) {
                pRenderParameters = renderParameters;
                pCanvasId = canvasId;
                pImageFile = renderedTileFile;
            } else {
                qRenderParameters = renderParameters;
                qCanvasId = canvasId;
                qImageFile = renderedTileFile;
            }
        }

        final DMeshTool tool = new DMeshTool(new File(script), new File(matchParametersPath), true);
        try {
            final CanvasMatches canvasMatches = tool.run(pCanvasId, pImageFile, pRenderParameters,
                                                         qCanvasId, qImageFile, qRenderParameters);
            LOG.info("result is:\n{}", canvasMatches.toJson());
        } catch (final Throwable t) {
            LOG.error("caught exception", t);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(DMeshTool.class);

}
