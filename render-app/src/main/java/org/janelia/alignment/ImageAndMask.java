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
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ImageAndMask {

    private String imageUrl;
    private String maskUrl;

    public ImageAndMask(String imageUrl,
                        String maskUrl) {
        this.imageUrl = imageUrl;
        this.maskUrl = maskUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getMaskUrl() {
        return maskUrl;
    }

    /**
     * @throws IllegalArgumentException
     *   if the image or mask URLs are invalid.
     */
    public void validate() throws IllegalArgumentException {
        if (imageUrl == null) {
            throw new IllegalArgumentException("no imageUrl specified");
        }
        validateUrlString(imageUrl, "imageUrl");
        validateUrlString(maskUrl, "maskUrl");
    }

    private void validateUrlString(String urlString,
                                   String context)
            throws IllegalArgumentException {

        if (urlString != null) {

            URI uri;
            try {
                uri = new URI(urlString);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("cannot parse " + context + " '" + urlString + "'", e);
            }

            final String scheme = uri.getScheme();
            if ((scheme == null) || FILE_SCHEME.equals(scheme)) {
                final File file = new File(uri);
                if (! file.exists()) {
                    throw new IllegalArgumentException("cannot find " + context + " file '" +
                                                       file.getAbsolutePath() + "'");
                }
                if (! file.canRead()) {
                    throw new IllegalArgumentException("no read access for " + context + " file '" +
                                                       file.getAbsolutePath() + "'");
                }
            }

        }
    }

    private static final String FILE_SCHEME = "file";
}
