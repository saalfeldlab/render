package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.io.Serializable;

/**
 * Information about a scope data set.
 *
 * @author Eric Trautman
 */
public record ScopeDataSet(String host, @JsonGetter(value = "root_dat_path") String rootDatPath,
                           @JsonGetter(value = "root_keep_path") String rootKeepPath,
                           @JsonGetter(value = "data_set_id") String dataSetId,
                           @JsonGetter(value = "rows_per_z_layer") Integer rowsPerZLayer,
                           @JsonGetter(value = "columns_per_z_layer") Integer columnsPerZLayer,
                           @JsonGetter(value = "first_dat_name") String firstDatName,
                           @JsonGetter(value = "last_dat_name") String lastDatName,
                           @JsonGetter(value = "dat_x_and_y_nm_per_pixel") Integer datXAndYNmPerPixel,
                           @JsonGetter(value = "dat_z_nm_per_pixel") Integer datZNmPerPixel,
                           @JsonGetter(value = "dat_tile_overlap_microns") Integer datTileOverlapMicrons)
        implements Serializable {

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private ScopeDataSet() {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null);
    }
}
