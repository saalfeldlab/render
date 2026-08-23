package org.janelia.alignment.filter;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the {@link FilterFactory} class.
 *
 * @author Eric Trautman
 */
public class FilterFactoryTest {

    @TempDir
    Path tempDir;

    private File factoryFile;

    @BeforeEach
    public void setup() {
        factoryFile = tempDir.resolve("test_filter_lists.json").toFile();
    }

    @Test
    public void testJsonProcessing() throws Exception {

        final FilterFactory factory = new FilterFactory();
        final List<Filter> defaultList = FilterFactory.buildDefaultInstanceList();
        final List<FilterSpec> defaultSpecList =
                defaultList.stream().map(FilterSpec::forFilter).collect(Collectors.toList());
        factory.addFilterList("default", defaultSpecList);

        final List<FilterSpec> favoritesSpecList = new ArrayList<>();
        favoritesSpecList.add(FilterSpec.forFilter(new CLAHE()));
        favoritesSpecList.add(FilterSpec.forFilter(new EqualizeHistogram()));
        favoritesSpecList.add(FilterSpec.forFilter(new Invert()));
        favoritesSpecList.add(FilterSpec.forFilter(new Rank()));
        favoritesSpecList.add(FilterSpec.forFilter(new RollingBallSubtraction()));
        factory.addFilterList("favorites", favoritesSpecList);

        final String json = factory.toJson();
        Files.write(factoryFile.toPath(), json.getBytes());

        final FilterFactory parsedFactory = FilterFactory.fromJson(new FileReader(factoryFile));

        final List<FilterSpec> loadedList = parsedFactory.getFilterList("favorites");
        assertEquals(favoritesSpecList.size(), loadedList.size(),
                     "invalid number of favorites loaded");

    }

    @Test
    public void testWebServiceFilterLists()
            throws IOException {

        final File configFile = new File("../render-ws/src/main/scripts/jetty/resources/filter_lists.json").getCanonicalFile();
        final FilterFactory factory = FilterFactory.fromJson(new FileReader(configFile));

        for (final String listName : factory.getSortedFilterListNames()) {
            final List<FilterSpec> filterSpecs = factory.getFilterList(listName);
            for (int i = 0; i < filterSpecs.size(); i++) {
                final FilterSpec filterSpec = filterSpecs.get(i);
                try {
                    filterSpec.buildInstance();
                } catch (final Throwable t) {
                    final StringWriter sw = new StringWriter();
                    final PrintWriter pw = new PrintWriter(sw);
                    t.printStackTrace(pw);
                    fail("failed to build filter " + i + " of the " + listName +
                                " list because of the following exception:\n" + sw);
                }
            }
        }

    }
}
