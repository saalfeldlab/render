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
package org.janelia.alignment.spec;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Pairing of a tile identifier and a transform spec that can be used during data import
 * to specify data to be applied to an existing tile spec.
 *
 * @author Eric Trautman
 */
public class TileTransform
        implements Serializable {

    private final String tileId;
    private final TransformSpec transform;

    // empty constructor required for JSON processing
    @SuppressWarnings("unused")
    private TileTransform() {
        this(null, null);
    }

    public TileTransform(final String tileId,
                         final TransformSpec transform) {
        this.tileId = tileId;
        this.transform = transform;
    }

    public String getTileId() {
        return tileId;
    }

    public TransformSpec getTransform() {
        return transform;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static TileTransform fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<TileTransform> fromJsonArray(final String json) {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileTransform[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<TileTransform> fromJsonArray(final Reader json)
            throws IOException {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileTransform[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final JsonUtils.Helper<TileTransform> JSON_HELPER =
            new JsonUtils.Helper<>(TileTransform.class);
}
