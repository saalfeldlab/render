package org.janelia.render.client.parameter;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

import com.beust.jcommander.Parameter;

/**
 * Parameters for specifying cells to be excluded from a tile based operation.
 *
 * @author Eric Trautman
 */
public class ExcludedCellParameters
        implements Serializable {

    @Parameter(
            names = "--excludedCell",
            description = "Exclude all tiles with this cell [row, column]",
            variableArity = true,
            converter = CellId.StringConverter.class)
    public List<CellId> cellIds;

    @Parameter(
            names = "--minZForExcludedCells",
            description = "Minimum Z value for excluded cells")
    public Double minZ;

    @Parameter(
            names = "--maxZForExcludedCells",
            description = "Maximum Z value for excluded cells")
    public Double maxZ;

    @Parameter(
            names = "--excludedCellsJson",
            description = "Path to json file containing array of excluded cell information (overrides explicit values)")
    public String excludedCellsJson;

    public boolean isExcludedCell(final CellId cell,
                                  final double z) {
        return ((minZ == null) || (z >= minZ)) &&
               ((maxZ == null) || (z <= maxZ)) &&
               ((cellIds != null) && cellIds.contains(cell));
    }

    public ExcludedCellList toList()
            throws FileNotFoundException {
        final ExcludedCellList list;
        if (excludedCellsJson == null) {
            list = new ExcludedCellList(Collections.singletonList(this));
        } else {
            final JsonUtils.Helper<ExcludedCellParameters> helper =
                    new JsonUtils.Helper<>(ExcludedCellParameters.class);
            list = new ExcludedCellList(helper.fromJsonArray(new FileReader(excludedCellsJson)));
        }
        return list;
    }

    public static class ExcludedCellList {
        private final List<ExcludedCellParameters> list;

        public ExcludedCellList(final List<ExcludedCellParameters> list) {
            this.list = list;
        }

        public boolean isDefined() {
            return (list.size() > 0) && (list.get(0).cellIds != null);
        }

        public boolean isExcludedCell(final CellId cell,
                                      final double z) {
            boolean isExcluded = false;
            for (final ExcludedCellParameters p : list) {
                if (p.isExcludedCell(cell, z)) {
                    isExcluded = true;
                    break;
                }
            }
            return isExcluded;
        }
    }

}
