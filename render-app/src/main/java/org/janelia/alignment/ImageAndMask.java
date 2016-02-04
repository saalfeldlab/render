/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import java.io.File;
import java.io.Serializable;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ImageAndMask implements Serializable {

    private String imageUrl;
    private String maskUrl;

    // cached full path URL strings (in case relative paths were specified),
    // marked transient to prevent serialization
    private transient String validatedImageUrl;
    private transient String validatedMaskUrl;

    public ImageAndMask() {
        this.imageUrl = null;
        this.maskUrl = null;
        this.validatedImageUrl = null;
        this.validatedMaskUrl = null;
    }

    public ImageAndMask(final String imageUrl,
                        final String maskUrl) {
        this.imageUrl = imageUrl;
        this.maskUrl = maskUrl;
        this.validatedImageUrl = null;
        this.validatedMaskUrl = null;
    }

    public ImageAndMask(final File imageFile,
                        final File maskFile) {
        if (imageFile != null) {
            this.imageUrl = imageFile.toURI().toString();
        }
        if (maskFile != null) {
            this.maskUrl = maskFile.toURI().toString();
        }
        this.validatedImageUrl = null;
        this.validatedMaskUrl = null;
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

    public String getMaskFilePath() {
        final String maskUrl = getMaskUrl();
        final Matcher m = FILE_NAME_PATTERN.matcher(maskUrl);
        return m.replaceFirst("/");
    }

    @Override
    public String toString() {
        return "ImageAndMask{" +
               "imageUrl='" + imageUrl + '\'' +
               ", maskUrl='" + maskUrl + '\'' +
               '}';
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
                file = new File(uri);
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
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("file:[/]+");
}
