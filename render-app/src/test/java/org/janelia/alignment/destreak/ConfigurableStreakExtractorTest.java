package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;

import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import net.imglib2.multithreading.SimpleMultiThreading;

public class ConfigurableStreakExtractorTest {

    @Test
    public void testParameterSerialization() {
        final Map<String, String> parametersMap =
                ConfigurableStreakCorrectorTest.TOUGH_RESIN_CORRECTOR.toParametersMap();

        final ConfigurableMaskStreakExtractor deserializedExtractor = new ConfigurableMaskStreakExtractor();
        deserializedExtractor.init(parametersMap);

        final String expectedDataString = ConfigurableStreakCorrectorTest.TOUGH_RESIN_CORRECTOR.toDataString();
        final String actualDataString = deserializedExtractor.toDataString();
        Assert.assertEquals("data strings should match", expectedDataString, actualDataString);
    }

    @SuppressWarnings({"ConstantConditions", "deprecation"})
    public static void main(final String[] args) {

        new ImageJ();

        // change index to test different images
        final String srcPath = ConfigurableStreakCorrectorTest.getToughResinPath(1);

        final ConfigurableMaskStreakExtractor extractor =
                new ConfigurableMaskStreakExtractor(ConfigurableStreakCorrectorTest.TOUGH_RESIN_CORRECTOR);

        final ImagePlus imp = new ImagePlus(srcPath);
        imp.setProcessor(imp.getProcessor().convertToByteProcessor());

        extractor.process(imp.getProcessor(), 1.0);
        imp.show();

        SimpleMultiThreading.threadHaltUnClean();
    }

}
