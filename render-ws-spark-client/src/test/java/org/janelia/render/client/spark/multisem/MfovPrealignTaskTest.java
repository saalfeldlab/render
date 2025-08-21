package org.janelia.render.client.spark.multisem;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.MFOVAsTileStackClient;
import org.janelia.render.client.parameter.MFOVAsTileParameters;
import org.janelia.render.client.parameter.TileRenderParameters;

public class MfovPrealignTaskTest {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) throws Exception {

        final String mfovRootDirectory = "/nrs/hess/data/hess_wafers_60_61/tiles_mfov";
        if (! Files.exists(Paths.get(mfovRootDirectory))) {
            throw new RuntimeException("The mfovRootDirectory " + mfovRootDirectory + " does not exist " +
                                       "but it is needed when rendering the MFOV-as-tile images later in this test. " +
                                       "You may need to mount /nrs locally.");
        }

        final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        final StackId rawSfovStackId = new StackId("hess_wafers_60_61",
                                                   "w60_serial_360_to_369",
                                                   "w60_s360_r00_gc");
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String prealignedStackSuffix  = "_test_prealign_" + sdf.format(new Date());
        final StackId prealignedStackId = rawSfovStackId.withStackSuffix(prealignedStackSuffix);

        final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                 rawSfovStackId.getOwner(),
                                                                 rawSfovStackId.getProject());
        final StackMetaData rawStackMetaData = dataClient.getStackMetaData(rawSfovStackId.getStack());
        dataClient.setupDerivedStack(rawStackMetaData, prealignedStackId.getStack());

        final LayerMFOV z17LayerMfov = new LayerMFOV(17.0, "0399_m0035");
        final MfovPrealignTask z17Task =
                new MfovPrealignTask(baseDataUrl,
                                     rawSfovStackId,
                                     prealignedStackId,
                                     z17LayerMfov);
        z17Task.run();

        final LayerMFOV z18LayerMfov = new LayerMFOV(18.0, "0399_m0035");
        final MfovPrealignTask z18Task =
                new MfovPrealignTask(baseDataUrl,
                                     rawSfovStackId,
                                     prealignedStackId,
                                     z18LayerMfov);
        z18Task.run();

        dataClient.setStackState(prealignedStackId.getStack(), StackMetaData.StackState.COMPLETE);

        final StackWithZValues prealignedStackWithAllZ = new StackWithZValues(prealignedStackId, List.of(17.0, 18.0));
        final MFOVAsTileParameters mfovAsTile = new MFOVAsTileParameters(0.2,
                                                                         mfovRootDirectory,
                                                                         prealignedStackSuffix,
                                                                         "_mat",
                                                                         "_render",
                                                                         "_align",
                                                                         "_rough");
        MFOVAsTileStackClient.buildOneMFOVAsTileStack(prealignedStackWithAllZ,
                                                      dataClient,
                                                      mfovAsTile.getMfovRenderScale(),
                                                      mfovAsTile.getDynamicMfovStackSuffix());

        final StackId dynamicMfovStackId = prealignedStackId.withStackSuffix(mfovAsTile.getDynamicMfovStackSuffix());
        final StackId renderedMfovStackId = dynamicMfovStackId.withStackSuffix(mfovAsTile.getRenderedMfovStackSuffix());
        final String runTimestamp = new TileRenderParameters().getRunTimestamp();

        final MFOVASTileClient.JavaRenderTilesClientInfo z17Info =
                new MFOVASTileClient.JavaRenderTilesClientInfo(baseDataUrl,
                                                               dynamicMfovStackId,
                                                               z17LayerMfov,
                                                               mfovAsTile,
                                                               runTimestamp);
        z17Info.setupHackStackAndStorage();
        z17Info.renderTiles();

        final MFOVASTileClient.JavaRenderTilesClientInfo z18Info =
                new MFOVASTileClient.JavaRenderTilesClientInfo(baseDataUrl,
                                                               dynamicMfovStackId,
                                                               z18LayerMfov,
                                                               mfovAsTile,
                                                               runTimestamp);
        z18Info.renderTiles();


        dataClient.setStackState(renderedMfovStackId.getStack(), StackMetaData.StackState.COMPLETE);
    }


}
