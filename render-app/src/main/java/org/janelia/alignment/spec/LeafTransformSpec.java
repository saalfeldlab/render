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

import mpicbg.models.CoordinateTransform;

import java.util.Map;
import java.util.Set;

/**
 * Specifies a {@link mpicbg.trakem2.transform.CoordinateTransform} implementation
 * along with it's initialization properties.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class LeafTransformSpec extends TransformSpec {

    public static final String TYPE = null; // use null type for leaf specs so legacy in-lined data

    private String className;
    private String dataString;

    private transient Class clazz;

    /**
     * "Legacy" constructor that supports simple specs without ids or metadata.
     *
     * @param  className   name of transformation implementation (java) class.
     * @param  dataString  data with which transformation implementation should be initialized.
     */
    public LeafTransformSpec(String className,
                             String dataString) {
        super(null, TYPE, null);
        this.className = className;
        this.dataString = dataString;
    }

    /**
     * Full constructor.
     *
     * @param  id          identifier for this specification.
     * @param  metaData    meta data about the specification.
     * @param  className   name of transformation implementation (java) class.
     * @param  dataString  data with which transformation implementation should be initialized.
     */
    public LeafTransformSpec(String id,
                             TransformSpecMetaData metaData,
                             String className,
                             String dataString) {
        super(id, TYPE, metaData);
        this.className = className;
        this.dataString = dataString;
    }

    @Override
    public boolean isFullyResolved() {
        return true;
    }

    @Override
    public void addUnresolvedIds(Set<String> unresolvedIds) {
        // nothing to do
    }

    @Override
    public void resolveReferences(Map<String, TransformSpec> idToSpecMap) {
        // nothing to do
    }

    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {

        final mpicbg.trakem2.transform.CoordinateTransform ct = newInstance();
        if (dataString == null) {
            throw new IllegalArgumentException("no dataString defined for leaf transform spec with id '" +
                                               getId() + "'");
        }
        ct.init(dataString);
        return ct;
    }

    private Class getClazz() throws IllegalArgumentException {
        if (clazz == null) {
            if (className == null) {
                throw new IllegalArgumentException("no className defined for leaf transform spec with id '" +
                                                   getId() + "'");
            }
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("transform class '" + className + "' cannot be found", e);
            }
        }
        return clazz;
    }

    private mpicbg.trakem2.transform.CoordinateTransform newInstance()
            throws IllegalArgumentException {

        final Class clazz = getClazz();
        final Object instance;
        try {
            instance = clazz.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to create instance of transform class '" + className + "'", e);
        }

        final mpicbg.trakem2.transform.CoordinateTransform coordinateTransform;
        if (instance instanceof mpicbg.trakem2.transform.CoordinateTransform) {
            coordinateTransform = (mpicbg.trakem2.transform.CoordinateTransform) instance;
        } else {
            throw new IllegalArgumentException("transform class '" + className + "' does not implement the '" +
                                               mpicbg.trakem2.transform.CoordinateTransform.class + "' interface");
        }

        return coordinateTransform;
    }

}
