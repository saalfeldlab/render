package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Parameters for specifying columns to be excluded from a tile based operation.
 *
 * @author Eric Trautman
 */
public class ExcludedColumnParameters
        implements Serializable {

    @Parameter(
            names = "--excludedColumn",
            description = "Exclude all tiles with this column number",
            variableArity = true)
    public List<Integer> columnNumbers;

    @Parameter(
            names = "--minZForExcludedColumns",
            description = "Minimum Z value for excluded columns")
    public Double minZ;

    @Parameter(
            names = "--maxZForExcludedColumns",
            description = "Maximum Z value for excluded columns")
    public Double maxZ;

    @Parameter(
            names = "--excludedColumnsJson",
            description = "Path to json file containing array of excluded column information (overrides explicit values)")
    public String excludedColumnsJson;

    public boolean isExcludedColumn(final int column,
                                    final double z) {
        return ((minZ == null) || (z >= minZ)) &&
               ((maxZ == null) || (z <= maxZ)) &&
               ((columnNumbers != null) && columnNumbers.contains(column));
    }

    public ExcludedColumnList toList()
            throws FileNotFoundException {
        final ExcludedColumnList list;
        if (excludedColumnsJson == null) {
            list = new ExcludedColumnList(Collections.singletonList(this));
        } else {
            final JsonUtils.Helper<ExcludedColumnParameters> helper =
                    new JsonUtils.Helper<>(ExcludedColumnParameters.class);
            list = new ExcludedColumnList(helper.fromJsonArray(new FileReader(excludedColumnsJson)));
        }
        return list;
    }

    public static class ExcludedColumnList {
        private final List<ExcludedColumnParameters> list;

        public ExcludedColumnList(final List<ExcludedColumnParameters> list) {
            this.list = list;
        }

        public boolean isDefined() {
            return (list.size() > 0) && (list.get(0).columnNumbers != null);
        }

        public boolean isExcludedColumn(final int column,
                                        final double z) {
            boolean isExcluded = false;
            for (final ExcludedColumnParameters p : list) {
                if (p.isExcludedColumn(column, z)) {
                    isExcluded = true;
                    break;
                }
            }
            return isExcluded;
        }
    }

}
