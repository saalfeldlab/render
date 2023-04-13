package org.janelia.alignment;

import java.io.File;
import java.io.Serializable;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.loader.ImageLoader.LoaderType;

/**
 *
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ImageAndMask implements Serializable {

    private String imageUrl;
    private LoaderType imageLoaderType;
    private Integer imageSliceNumber;

    private String maskUrl;
    private LoaderType maskLoaderType;
    private Integer maskSliceNumber;

    // cached full path URL strings (in case relative paths were specified),
    // marked transient to prevent serialization
    private transient String validatedImageUrl = null;
    private transient String validatedMaskUrl = null;

    public ImageAndMask() {
        this(null, null, null,
             null, null, null);
    }

    public ImageAndMask(final String imageUrl,
                        final String maskUrl) {
        this(imageUrl, null, null, maskUrl, null, null);
    }

    public ImageAndMask(final File imageFile,
                        final File maskFile) {
        if (imageFile != null) {
            this.imageUrl = imageFile.toURI().toString();
        }
        if (maskFile != null) {
            this.maskUrl = maskFile.toURI().toString();
        }
    }

    public ImageAndMask(final String imageUrl,
                        final LoaderType imageLoaderType,
                        final Integer imageSliceNumber,
                        final String maskUrl,
                        final LoaderType maskLoaderType,
                        final Integer maskSliceNumber) {
        this.imageUrl = imageUrl;
        this.imageLoaderType = imageLoaderType;
        this.imageSliceNumber = imageSliceNumber;
        this.maskUrl = maskUrl;
        this.maskLoaderType = maskLoaderType;
        this.maskSliceNumber = maskSliceNumber;
    }

    public ImageAndMask copyWithImage(final String changedImageUrl,
                                      final LoaderType changedImageLoaderType,
                                      final Integer changedImageSliceNumber) {
        return new ImageAndMask(changedImageUrl,
                                changedImageLoaderType,
                                changedImageSliceNumber,
                                this.maskUrl,
                                this.maskLoaderType,
                                this.maskSliceNumber);
    }

    public ImageAndMask copyWithMask(final String changedMaskUrl,
                                     final LoaderType changedMaskLoaderType,
                                     final Integer changedMaskSliceNumber) {
        return new ImageAndMask(this.imageUrl,
                                this.imageLoaderType,
                                this.imageSliceNumber,
                                changedMaskUrl,
                                changedMaskLoaderType,
                                changedMaskSliceNumber);
    }

    public ImageAndMask copyWithDerivedUrls(final String derivedImageUrl,
                                            final String derivedMaskUrl) {
        return new ImageAndMask(derivedImageUrl,
                                this.imageLoaderType,
                                this.imageSliceNumber,
                                derivedMaskUrl,
                                this.maskLoaderType,
                                this.maskSliceNumber);
    }

    public ImageAndMask copyWithoutMask() {
        return copyWithMask(null, null, null);
    }

    public ImageAndMask maskAsImage() {
        return new ImageAndMask(this.maskUrl,
                                this.maskLoaderType,
                                this.maskSliceNumber,
                                null,
                                null,
                                null);
    }

    public boolean hasImage() {
        return imageUrl != null;
    }

    public String getImageUrl() throws IllegalArgumentException {
        if ((validatedImageUrl == null) && (imageUrl != null)) {
            validatedImageUrl = getUrlString(getUri(imageUrl));
        }
        return validatedImageUrl;
    }

    public String getImageFilePath() {
        final String imageUrl = getImageUrl();
        final Matcher m = FILE_NAME_PATTERN.matcher(imageUrl);
        return m.replaceFirst("/");
    }

    public boolean hasMask() {
        return maskUrl != null;
    }

    public String getMaskUrl() throws IllegalArgumentException {
        if ((validatedMaskUrl == null) && (maskUrl != null)) {
            validatedMaskUrl = getUrlString(getUri(maskUrl));
        }
        return validatedMaskUrl;
    }

    public String getMaskUrl(final Integer maskMinX,
                             final Integer maskMinY) throws IllegalArgumentException {
        String maskUrl = getMaskUrl();
        if (((maskMinX != null) || (maskMinY != null)) && LoaderType.DYNAMIC_MASK.equals(maskLoaderType)) {
            maskUrl = DynamicMaskLoader.parseUrl(maskUrl).withMinXAndY(maskMinX, maskMinY).toString();
        }
        return maskUrl;
    }

    public String getMaskFilePath() {
        final String maskUrl = getMaskUrl();
        final Matcher m = FILE_NAME_PATTERN.matcher(maskUrl);
        return m.replaceFirst("/");
    }

    public LoaderType getImageLoaderType() {
        return imageLoaderType == null ? LoaderType.IMAGEJ_DEFAULT : imageLoaderType;
    }

    public Integer getImageSliceNumber() {
        return imageSliceNumber;
    }

    public LoaderType getMaskLoaderType() {
        return maskLoaderType == null ? LoaderType.IMAGEJ_DEFAULT : maskLoaderType;
    }

    public Integer getMaskSliceNumber() {
        return maskSliceNumber;
    }

    @Override
    public String toString() {
        final String imageLoaderInfo = imageLoaderType == null ? "" :
                                       ", imageLoaderName: '" + imageLoaderType +
                                       ", imageSliceNumber: " + imageSliceNumber;
        final String maskInfo = maskUrl == null ? "" : ", maskUrl: '" + maskUrl + '\'';
        final String maskLoaderInfo = maskLoaderType == null ? "" :
                                      ", maskLoaderName: '" + maskLoaderType +
                                      ", maskSliceNumber: " + maskSliceNumber;
        return "{imageUrl: '" + imageUrl + '\'' + imageLoaderInfo + maskInfo + maskLoaderInfo + '}';
    }

    /**
     * @throws IllegalArgumentException
     *   if the image or mask URLs are invalid.
     */
    public void validate() throws IllegalArgumentException {
        if (imageUrl == null) {
            throw new IllegalArgumentException("no imageUrl specified");
        }

        final URI imageUri = getUri(imageUrl);
        validateFile(imageUri, "imageUrl");
        validatedImageUrl = getUrlString(imageUri);

        if (maskUrl != null) {
            final URI maskUri = getUri(maskUrl);
            validateFile(maskUri, "maskUrl");
            validatedMaskUrl = getUrlString(maskUri);
        }
    }

    private URI getUri(final String urlString) {
        URI uri = null;
        if (urlString != null) {
            uri = Utils.convertPathOrUriStringToUri(urlString);
        }
        return uri;
    }

    private String getUrlString(final URI uri) {
        String urlString = null;
        if (uri != null) {
            urlString = uri.toString();
        }
        return urlString;
    }

    private void validateFile(final URI uri,
                              final String context)
            throws IllegalArgumentException {

        final String scheme = uri.getScheme();
        if ((scheme == null) || FILE_SCHEME.equals(scheme)) {
            final File file;
            try {
                file = new File(uri.getPath());
            } catch (final Exception e) {
                throw new IllegalArgumentException("failed to convert '" + uri + "' to a file reference", e);
            }
            if (! file.exists()) {
                throw new IllegalArgumentException("cannot find " + context + " file '" +
                                                   file.getAbsolutePath() + "'");
            }
            if (! file.canRead()) {
                throw new IllegalArgumentException("no read access for " + context + " file '" +
                                                   file.getAbsolutePath() + "'");
            }
            if (file.length() == 0) {
                throw new IllegalArgumentException(context + " file '" +
                                                   file.getAbsolutePath() + "' is empty");
            }
        }
    }

    private static final String FILE_SCHEME = "file";
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("file:/+");
}
