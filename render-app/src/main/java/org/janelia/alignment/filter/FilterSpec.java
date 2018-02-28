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
package org.janelia.alignment.filter;

import java.util.Map;

import org.janelia.alignment.json.JsonUtils;

/**
 * Specifies a {@link org.janelia.alignment.filter.Filter} implementation along with its parameters.
 *
 * @author Eric Trautman
 */
public class FilterSpec {

    private final String className;
    private final Map<String, String> parameters;

    private transient Class clazz;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private FilterSpec() {
        this.className = null;
        this.parameters = null;
    }

    /**
     * Full constructor.
     *
     * @param  className   name of transformation implementation (java) class.
     * @param  parameters  data with which transformation implementation should be initialized.
     */
    public FilterSpec(final String className,
                      final Map<String, String> parameters) {
        this.className = className;
        this.parameters = parameters;
    }

    public String getClassName() {
        return className;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    /**
     * @return instance of this filter initialized with the specified parameters.
     *
     * @throws IllegalArgumentException
     *   if an instance cannot be created for any reason.
     */
    public Filter buildInstance()
            throws IllegalArgumentException {

        final Class clazz = getClazz();
        final Object instance;
        try {
            instance = clazz.newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException("failed to create instance of filter class '" + className + "'", e);
        }

        final Filter filter;
        if (instance instanceof Filter) {
            filter = (Filter) instance;
        } else {
            throw new IllegalArgumentException("class '" + className + "' does not implement the '" +
                                               Filter.class + "' interface");
        }

        filter.init(parameters);

        return filter;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    @Override
    public String toString() {
        return toJson();
    }

    public static FilterSpec fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    /**
     * @param  filter  instance to convert to a specification.
     *
     * @return specification for the filter instance.
     */
    public static FilterSpec forFilter(final Filter filter) {
        return new FilterSpec(filter.getClass().getName(), filter.toParametersMap());
    }

    private Class getClazz() throws IllegalArgumentException {
        if (clazz == null) {
            if (className == null) {
                throw new IllegalArgumentException("no className defined for filter spec");
            }
            try {
                clazz = Class.forName(className);
            } catch (final ClassNotFoundException e) {
                throw new IllegalArgumentException("filter class '" + className + "' cannot be found", e);
            }
        }
        return clazz;
    }

    private static final JsonUtils.Helper<FilterSpec> JSON_HELPER =
            new JsonUtils.Helper<>(FilterSpec.class);
}
