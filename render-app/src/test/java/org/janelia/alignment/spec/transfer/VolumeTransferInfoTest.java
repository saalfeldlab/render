package org.janelia.alignment.spec.transfer;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the {@link VolumeTransferInfo} class.
 *
 * @author Eric Trautman
 */
public class VolumeTransferInfoTest {

    @Test
    public void testJsonProcessing() {

        final VolumeTransferInfo volumeTransferInfo = VolumeTransferInfo.fromJson(TRANSFER_INFO_JSON);
        assertNotNull(volumeTransferInfo, "json parse returned null spec");

        final ScopeDataSet scopeDataSet = volumeTransferInfo.scopeDataSet();
        assertNotNull(scopeDataSet, "scopeDataSet is null");
        assertEquals(FIRST_DAT_NAME, scopeDataSet.firstDatName(), "invalid first_dat_name parsed");

        final ClusterRootPaths clusterRootPaths = volumeTransferInfo.clusterRootPaths();
        assertNotNull(clusterRootPaths, "clusterRootPaths is null");
        assertEquals(ALIGN_H5, clusterRootPaths.getAlignH5(), "invalid align_h5 parsed");

        final Integer maxMipmapLevel = volumeTransferInfo.maxMipmapLevel();
        assertNotNull(maxMipmapLevel, "maxMipmapLevel is null");
        assertEquals(MAX_MIPMAP_LEVEL, maxMipmapLevel.intValue(), "invalid maxMipmapLevel parsed");

        final RenderDataSet renderDataSet = volumeTransferInfo.renderDataSet();
        assertNotNull(renderDataSet, "renderDataSet is null");
        assertEquals(STACK, renderDataSet.stack(), "invalid stack parsed");

        final Integer maskWidth = renderDataSet.maskWidth();
        assertNotNull(maskWidth, "maskWidth is null");
        assertEquals(MASK_WIDTH, maskWidth.intValue(), "invalid mask_width parsed");

        final Connect connect = renderDataSet.connect();
        assertNotNull(connect, "connect is null");
        assertEquals(HOST, connect.host(), "invalid host parsed");

        final List<TransferTask> transferTasks = volumeTransferInfo.transferTasks();
        assertNotNull(transferTasks, "transfer_tasks is null");
        assertEquals(7, transferTasks.size(), "invalid number of transfer tasks parsed");
    }

    private static final String FIRST_DAT_NAME = "Merlin-6049_24-05-09_000312_0-0-0.dat";
    private static final String ALIGN_H5 = "/nrs/fibsem/data/jrc_celegans-20240415/align";
    private static final int MAX_MIPMAP_LEVEL = 7;
    private static final String STACK = "v1_acquire";
    private static final int MASK_WIDTH = 100;
    private static final String HOST = "renderer-dev";

    private static final String TRANSFER_INFO_JSON =
            "{\n" +
            "    \"transfer_id\": \"fibsem::jrc_celegans-20240415::jeiss5\",\n" +
            "    \"scope_data_set\": {\n" +
            "        \"host\": \"jeiss5.hhmi.org\",\n" +
            "        \"root_dat_path\": \"/cygdrive/e/Images/Celegans\",\n" +
            "        \"root_keep_path\": \"/cygdrive/d/UploadFlags\",\n" +
            "        \"data_set_id\": \"jrc_celegans-20240415\",\n" +
            "        \"rows_per_z_layer\": 1,\n" +
            "        \"columns_per_z_layer\": 1,\n" +
            "        \"first_dat_name\": \"" + FIRST_DAT_NAME + "\",\n" +
            "        \"last_dat_name\": null,\n" +
            "        \"dat_x_and_y_nm_per_pixel\": 6,\n" +
            "        \"dat_z_nm_per_pixel\": 6,\n" +
            "        \"dat_tile_overlap_microns\": 2\n" +
            "    },\n" +
            "    \"cluster_root_paths\": {\n" +
            "        \"raw_dat\": \"/groups/fibsem/fibsem/data/jrc_celegans-20240415/dat\",\n" +
            "        \"raw_h5\": \"/groups/fibsem/fibsem/data/jrc_celegans-20240415/raw\",\n" +
            "        \"align_h5\": \"" + ALIGN_H5 + "\"\n" +
            "    },\n" +
            "    \"archive_root_paths\": {\n" +
            "        \"raw_h5\": \"/nearline/fibsem/data/jrc_celegans-20240415/raw\"\n" +
            "    },\n" +
            "    \"max_mipmap_level\": " + MAX_MIPMAP_LEVEL + ",\n" +
            "    \"render_data_set\": {\n" +
            "        \"owner\": \"fibsem\",\n" +
            "        \"project\": \"jrc_celegans_20240415\",\n" +
            "        \"stack\": \"" + STACK + "\",\n" +
            "        \"restart_context_layer_count\": 1,\n" +
            "        \"mask_width\": " + MASK_WIDTH + ",\n" +
            "        \"connect\": {\n" +
            "            \"host\": \"" + HOST + "\",\n" +
            "            \"port\": 8080,\n" +
            "            \"web_only\": true,\n" +
            "            \"validate_client\": false,\n" +
            "            \"client_scripts\": \"/groups/flyTEM/flyTEM/render/bin\",\n" +
            "            \"memGB\": \"1G\"\n" +
            "        }\n" +
            "    },\n" +
            "    \"transfer_tasks\": [\n" +
            "        \"COPY_SCOPE_DAT_TO_CLUSTER\",\n" +
            "        \"GENERATE_CLUSTER_H5_RAW\",\n" +
            "        \"GENERATE_CLUSTER_H5_ALIGN\",\n" +
            "        \"REMOVE_DAT_AFTER_H5_CONVERSION\",\n" +
            "        \"ARCHIVE_H5_RAW\",\n" +
            "        \"IMPORT_H5_ALIGN_INTO_RENDER\",\n" +
            "        \"APPLY_FIBSEM_CORRECTION_TRANSFORM\"\n" +
            "    ],\n" +
            "    \"cluster_job_project_for_billing\": \"shroff\",\n" +
            "    \"number_of_dats_converted_per_hour\": 15\n" +
            "}";

}
