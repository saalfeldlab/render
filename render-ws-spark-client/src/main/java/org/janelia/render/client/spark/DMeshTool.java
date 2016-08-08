package org.janelia.render.client.spark;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;

import mpicbg.util.Timer;

import org.apache.commons.io.IOUtils;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.TileSpec;
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

        validateEvenSize("width", p, pRenderParameters.getWidth());
        validateEvenSize("height", p, pRenderParameters.getHeight());
        validateEvenSize("width", q, qRenderParameters.getWidth());
        validateEvenSize("height", q, qRenderParameters.getHeight());

        final Timer timer = new Timer();
        timer.start();

        final ProcessBuilder processBuilder =
                new ProcessBuilder(script,
                                   pTileImage.getAbsolutePath(),
                                   qTileImage.getAbsolutePath(),
                                   "-z=" + p.getGroupId() + "," + q.getGroupId(),
                                   "-matchparams_file=" + matchParametersPath,
                                   "-Ta=" + getAffineCoefficientsString(pRenderParameters),
                                   "-Tb=" + getAffineCoefficientsString(qRenderParameters),
                                   "-pts_file=stdout").
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

    private void validateEvenSize(final String context,
                                  final CanvasId canvasId,
                                  final int value)
            throws IllegalStateException {
        if ((value % 2) == 1) {
            throw new IllegalStateException("canvas " + canvasId + " has odd " + context + " of " + value);
        }
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

    public static void main(final String[] args) {

        if (args.length != 10) {
            throw new IllegalArgumentException("Expected parameters are: script matchParametersPath pGroupId pId pImage pRenderParametersUrl qGroupId qId qImage qRenderParametersUrl");
        }

        final String script = args[0];
        final String matchParametersPath = args[1];

        final CanvasId p = new CanvasId(args[2], args[3]);
        final File pTileImage = new File(args[4]);
        final String pRenderParametersUrl = args[5];

        final CanvasId q = new CanvasId(args[6], args[7]);
        final File qTileImage = new File(args[8]);
        final String qRenderParametersUrl = args[9];

        final RenderParameters pRenderParameters = RenderParameters.loadFromUrl(pRenderParametersUrl);
        final RenderParameters qRenderParameters = RenderParameters.loadFromUrl(qRenderParametersUrl);

        final DMeshTool tool = new DMeshTool(new File(script), new File(matchParametersPath), true);
        try {
            final CanvasMatches canvasMatches = tool.run(p, pTileImage, pRenderParameters, q, qTileImage, qRenderParameters);
            LOG.info("result is:\n{}", canvasMatches.toJson());
        } catch (final Throwable t) {
            LOG.error("caught exception", t);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(DMeshTool.class);

}
