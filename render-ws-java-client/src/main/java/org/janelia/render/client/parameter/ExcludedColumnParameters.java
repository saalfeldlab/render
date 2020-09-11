package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;
import java.util.List;

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
    public Double minZForExcludedColumns;

    @Parameter(
            names = "--maxZForExcludedColumns",
            description = "Maximum Z value for excluded columns")
    public Double maxZForExcludedColumns;

    public boolean isDefined() {
        return columnNumbers != null;
    }

    public boolean isExcludedColumn(final int column,
                                    final double z) {
        return ((minZForExcludedColumns == null) || (z >= minZForExcludedColumns)) &&
               ((maxZForExcludedColumns == null) || (z <= maxZForExcludedColumns)) &&
               ((columnNumbers != null) && columnNumbers.contains(column));
    }
}
