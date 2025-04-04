package org.janelia.alignment.filter;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.ArgbRendererTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link FilterFactory} class.
 *
 * @author Eric Trautman
 */
public class FilterFactoryTest {

    private File factoryFile;

    @Before
    public void setup() throws Exception {
        final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        final String timestamp = TIMESTAMP.format(new Date());
        factoryFile = new File("test_filter_lists_" + timestamp + ".json").getCanonicalFile();
    }

    @After
    public void tearDown() {
        ArgbRendererTest.deleteTestFile(factoryFile);
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
        Assert.assertEquals("invalid number of favorites loaded",
                            favoritesSpecList.size(), loadedList.size());

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
                    Assert.fail("failed to build filter " + i + " of the " + listName +
                                " list because of the following exception:\n" + sw);
                }
            }
        }

    }
}
