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

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains a mapping of configured filter list names to specifications and
 * facilitates constructing the corresponding instances.
 *
 * @author Eric Trautman
 */
public class FilterFactory implements Serializable {

    private final Map<String, List<FilterSpec>> namedFilterSpecLists;

    /**
     * Constructs an empty factory.
     */
    public FilterFactory() {
        this.namedFilterSpecLists = new HashMap<>();
    }

    /**
     *
     * @param  name  name of the desired filter list.
     *
     * @return list of filter specifications associated with the specified name.
     *
     * @throws IllegalArgumentException
     *   if no list with the specified name exists.
     */
    public List<FilterSpec> getFilterList(final String name)
            throws IllegalArgumentException {

        final List<FilterSpec> filterSpecs = namedFilterSpecLists.get(name);

        if (filterSpecs == null) {
            throw new IllegalArgumentException("Filter list with name '" + name + "' not found.  " +
                                               "This could be caused by usage of an incorrect name or " +
                                               "by a problem with the system filter configuration file " +
                                               getSystemConfigurationFile() + ".");
        }

        return filterSpecs;
    }

    /**
     * Adds the specified list to this factory.
     *
     * @param  name      name of the list.
     * @param  specList  specifications in the list.
     */
    public void addFilterList(final String name,
                              final List<FilterSpec> specList) {
        namedFilterSpecLists.put(name, specList);
    }

    /**
     * @return a JSON representation of this factory.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    /**
     * @return a factory instance parsed from the system configuration file
     *         (or an empty factory instance if that file cannot be parsed for any reason).
     */
    public static FilterFactory loadConfiguredInstance() {

        FilterFactory factory = new FilterFactory();

        final File configFile = getSystemConfigurationFile();

        if (configFile.exists()) {
            try {
                factory = fromJson(new FileReader(configFile));

                LOG.info("loadConfiguredInstance: loaded {} named filter lists from {}",
                         factory.namedFilterSpecLists.size(), configFile);

            } catch (final Throwable t) {
                LOG.warn("loadConfiguredInstance: failed to load filters from " + configFile +
                         ", requests with filterListName parameter will not be supported", t);
            }

        } else {
            LOG.warn("loadConfiguredInstance: failed to find {}, requests with filterListName parameter will not be supported",
                     configFile);
        }

        return factory;
    }

    /**
     * @param  reader  reader to parse.
     *
     * @return a factory instance populated by parsing the specified json reader's stream.
     */
    public static FilterFactory fromJson(final Reader reader) {
        return JSON_HELPER.fromJson(reader);
    }

    /**
     * @return "legacy" default list of ad-hoc filters used for alignment.
     */
    public static List<Filter> buildDefaultInstanceList() {
        return new ArrayList<>(DEFAULT_FILTERS);
    }

    /**
     * @param  specList  list of filter specifications.
     *
     * @return list of filter instances built from the specifications.
     */
    public static List<Filter> buildInstanceList(final List<FilterSpec> specList) {
        final List<Filter> filterInstanceList = new ArrayList<>(specList.size());
        //noinspection Convert2streamapi
        for (final FilterSpec spec : specList) {
            filterInstanceList.add(spec.buildInstance());
        }
        return filterInstanceList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(FilterFactory.class);

    // Stephan's ad-hoc filter bank for temporary use in alignment
    private static final ValueToNoise VALUE_TO_NOISE_1 =
            new ValueToNoise(0, 64, 191);
    private static final ValueToNoise VALUE_TO_NOISE_2 =
            new ValueToNoise(255, 64, 191);
    private static final NormalizeLocalContrast NORMALIZE_LOCAL_CONTRAST =
            new NormalizeLocalContrast(500, 500, 3, true, true);
//    private static final CLAHE clahe = new CLAHE(true, 250, 256, 2);

    private static final List<Filter> DEFAULT_FILTERS = Arrays.asList(VALUE_TO_NOISE_1,
                                                                      VALUE_TO_NOISE_2,
                                                                      NORMALIZE_LOCAL_CONTRAST);

    private static final JsonUtils.Helper<FilterFactory> JSON_HELPER =
            new JsonUtils.Helper<>(FilterFactory.class);

    private static File getSystemConfigurationFile() {
        return new File("resources/filter_lists.json").getAbsoluteFile();
    }
}
