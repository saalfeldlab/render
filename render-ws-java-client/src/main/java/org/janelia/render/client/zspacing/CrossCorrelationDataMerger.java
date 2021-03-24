package org.janelia.render.client.zspacing;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.janelia.alignment.util.FileUtil;

/**
 * Tool to serialize batched cross correlation data into a single JSON file.
 *
 * @author Eric Trautman
 */
public class CrossCorrelationDataMerger {

    public static void main(final String[] args)
            throws IOException {

        if (args.length < 1) {
            System.out.println("USAGE: java " + CrossCorrelationDataMerger.class.getName() +
                               " <cc batches dir> [out file]");
            System.exit(1);
        }

        final Path batchDirPath = Paths.get(args[0]);
        final Path outputPath;
        if (args.length > 1) {
            outputPath = Paths.get(args[1]);
        } else {
            outputPath = Paths.get(batchDirPath.getParent().toString(), "merged_cc_data.json.gz");
        }

        final List<CrossCorrelationData> ccDataList =
                CrossCorrelationData.loadCrossCorrelationDataFiles(batchDirPath,
                                                                   CrossCorrelationData.DEFAULT_DATA_FILE_NAME,
                                                                   2);

        final CrossCorrelationData ccData = CrossCorrelationData.merge(ccDataList);

        FileUtil.saveJsonFile(outputPath.toString(), ccData);
    }

}
