package org.janelia.alignment.spec.transfer;

/**
 * FIBSEM data transfer tasks.
 *
 * @author Eric Trautman
 */
public enum TransferTask {
    COPY_SCOPE_DAT_TO_CLUSTER,
    GENERATE_CLUSTER_H5_RAW,
    GENERATE_CLUSTER_H5_ALIGN,
    REMOVE_DAT_AFTER_H5_CONVERSION,
    ARCHIVE_H5_RAW,
    IMPORT_H5_ALIGN_INTO_RENDER,
    APPLY_FIBSEM_CORRECTION_TRANSFORM,
    EXPORT_PREVIEW_VOLUME
}
