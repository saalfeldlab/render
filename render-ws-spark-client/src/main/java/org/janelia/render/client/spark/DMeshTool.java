package org.janelia.render.client.spark;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

import mpicbg.util.Timer;

import org.apache.commons.io.IOUtils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
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

    public DMeshTool(final File scriptFile,
                     final File matchParametersFile)
            throws IllegalArgumentException {

        this.script = scriptFile.getAbsolutePath();
        this.matchParametersPath = matchParametersFile.getAbsolutePath();

        if (! scriptFile.canExecute()) {
            throw new IllegalArgumentException(this.script + " is not executable");
        }

        if (! matchParametersFile.canRead()) {
            throw new IllegalArgumentException(this.matchParametersPath + " is not readable");
        }
    }

    public CanvasMatches run(final CanvasId p,
                             final File pTileImage,
                             final String pTileDataUrl,
                             final CanvasId q,
                             final File qTileImage,
                             final String qTileDataUrl)
            throws IOException, InterruptedException, IllegalStateException {

        final Timer timer = new Timer();
        timer.start();

        final ProcessBuilder processBuilder =
                new ProcessBuilder(script,
                                   pTileImage.getAbsolutePath(),
                                   qTileImage.getAbsolutePath(),
                                   "-z=" + p.getGroupId() + "," + q.getGroupId(),
                                   "-matchparams_file=" + matchParametersPath,
                                   "-Ta=" + pTileDataUrl,
                                   "-Tb=" + qTileDataUrl,
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

    public static void main(final String[] args) {

        if (args.length != 10) {
            throw new IllegalArgumentException("Expected parameters are: script matchParametersPath pGroupId pId pImage pDataUrl qGroupId qId qImage qDataUrl");
        }

        final String script = args[0];
        final String matchParametersPath = args[1];

        final CanvasId p = new CanvasId(args[2], args[3]);
        final File pTileImage = new File(args[4]);
        final String pTileDataUrl = args[5];

        final CanvasId q = new CanvasId(args[6], args[7]);
        final File qTileImage = new File(args[8]);
        final String qTileDataUrl = args[9];

        final DMeshTool tool = new DMeshTool(new File(script), new File(matchParametersPath));
        try {
            final CanvasMatches canvasMatches = tool.run(p, pTileImage, pTileDataUrl, q, qTileImage, qTileDataUrl);
            LOG.info("result is:\n{}", canvasMatches.toJson());
        } catch (final Throwable t) {
            LOG.error("caught exception", t);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(DMeshTool.class);

}
