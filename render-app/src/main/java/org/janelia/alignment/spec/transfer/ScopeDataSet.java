package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

/**
 * Information about a scope data set.
 *
 * @author Eric Trautman
 */
public class ScopeDataSet {

    private final String host;
    private final String rootDatPath;
    private final String rootKeepPath;
    private final String dataSetId;
    private final Integer rowsPerZLayer;
    private final Integer columnsPerZLayer;
    private final String firstDatName;
    private final String lastDatName;
    private final Integer datXAndYNmPerPixel;
    private final Integer datZNmPerPixel;
    private final Integer datTileOverlapMicrons;

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

    public ScopeDataSet(final String host,
                        final String rootDatPath,
                        final String rootKeepPath,
                        final String dataSetId,
                        final Integer rowsPerZLayer,
                        final Integer columnsPerZLayer,
                        final String firstDatName,
                        final String lastDatName,
                        final Integer datXAndYNmPerPixel,
                        final Integer datZNmPerPixel,
                        final Integer datTileOverlapMicrons) {
        this.host = host;
        this.rootDatPath = rootDatPath;
        this.rootKeepPath = rootKeepPath;
        this.dataSetId = dataSetId;
        this.rowsPerZLayer = rowsPerZLayer;
        this.columnsPerZLayer = columnsPerZLayer;
        this.firstDatName = firstDatName;
        this.lastDatName = lastDatName;
        this.datXAndYNmPerPixel = datXAndYNmPerPixel;
        this.datZNmPerPixel = datZNmPerPixel;
        this.datTileOverlapMicrons = datTileOverlapMicrons;
    }

    public String getHost() {
        return host;
    }

    @JsonGetter(value = "root_dat_path")
    public String getRootDatPath() {
        return rootDatPath;
    }

    @JsonGetter(value = "root_keep_path")
    public String getRootKeepPath() {
        return rootKeepPath;
    }

    @JsonGetter(value = "data_set_id")
    public String getDataSetId() {
        return dataSetId;
    }

    @JsonGetter(value = "rows_per_z_layer")
    public Integer getRowsPerZLayer() {
        return rowsPerZLayer;
    }

    @JsonGetter(value = "columns_per_z_layer")
    public Integer getColumnsPerZLayer() {
        return columnsPerZLayer;
    }

    @JsonGetter(value = "first_dat_name")
    public String getFirstDatName() {
        return firstDatName;
    }

    @JsonGetter(value = "last_dat_name")
    public String getLastDatName() {
        return lastDatName;
    }

    @JsonGetter(value = "dat_x_and_y_nm_per_pixel")
    public Integer getDatXAndYNmPerPixel() {
        return datXAndYNmPerPixel;
    }

    @JsonGetter(value = "dat_z_nm_per_pixel")
    public Integer getDatZNmPerPixel() {
        return datZNmPerPixel;
    }

    @JsonGetter(value = "dat_tile_overlap_microns")
    public Integer getDatTileOverlapMicrons() {
        return datTileOverlapMicrons;
    }
}
