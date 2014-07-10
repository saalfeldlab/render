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

import mpicbg.models.CoordinateTransform;

/**
 * Specifies a {@link mpicbg.trakem2.transform.CoordinateTransform} implementation
 * along with it's initialization properties.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Transform {

    private String className;
    private String dataString;

    private transient Class clazz;
    private transient mpicbg.trakem2.transform.CoordinateTransform validationInstance;
    private transient mpicbg.trakem2.transform.CoordinateTransform instance;

    public Transform(String className,
                     String dataString) {
        this.className = className;
        this.dataString = dataString;
    }

    /**
     * @throws IllegalArgumentException
     *   if a {@link CoordinateTransform} instance cannot be created based upon this specification.
     */
    public void validate() throws IllegalArgumentException {
        validationInstance = createAndInitTransform(); // cache instance for first createTransform call
    }

    /**
     * @return a new {@link CoordinateTransform} instance based upon this specification.
     *
     * @throws IllegalArgumentException
     *   if the instance cannot be created.
     */
    public CoordinateTransform createTransform()
            throws IllegalArgumentException {

        if (instance == null) {
            instance = validationInstance;
        } else {
            instance = createAndInitTransform();
        }
        return instance;
    }

    private Class getClazz() throws IllegalArgumentException {
        if (clazz == null) {
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

    private mpicbg.trakem2.transform.CoordinateTransform createAndInitTransform()
            throws IllegalArgumentException {

        final mpicbg.trakem2.transform.CoordinateTransform ct = newInstance();
        ct.init(dataString);
        return ct;
    }
}
