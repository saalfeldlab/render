package org.janelia.alignment.filter;

import java.io.File;
import java.io.FileReader;
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

}
