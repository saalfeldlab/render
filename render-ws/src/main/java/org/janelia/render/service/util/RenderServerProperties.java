package org.janelia.render.service.util;

import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configured properties for a render server.
 *
 * @author Eric Trautman
 */
public class RenderServerProperties {

    private static RenderServerProperties serverProperties;

    private final String filePath;
    private final Map<String, String> properties;

    public RenderServerProperties(final String filePath) {

        this.properties = new LinkedHashMap<>(); // keep properties sorted by key

        final File propertiesFile = new File(filePath).getAbsoluteFile();
        this.filePath = propertiesFile.getPath();

        try {
            final Properties p = new Properties();
            if (propertiesFile.exists()) {
                final FileInputStream in = new FileInputStream(propertiesFile);
                p.load(in);
                LOG.info("loaded {}", propertiesFile);
            }

            for (final String sortedKey : new TreeSet<>(p.stringPropertyNames())) {
                properties.put(sortedKey, p.getProperty(sortedKey));
            }

        } catch (final Throwable t) {
            LOG.warn("failed to load properties", t);
        }

    }

    public String get(final String key) {
        return properties.get(key);
    }

    public Integer getInteger(final String key) {
        final String valueString = get(key);
        Integer value = null;
        if ((valueString != null) && (valueString.trim().length() > 0)) {
            try {
                value = Integer.parseInt(valueString);
            } catch (final Throwable t) {
                LOG.warn("failed to parse " + key + " value in " + filePath, t);
            }
        }
        return value;
    }

    public static RenderServerProperties getProperties() {
        if (serverProperties == null) {
            buildProperties();
        }
        return serverProperties;
    }

    private static synchronized void buildProperties() {
        if (serverProperties == null) {
            serverProperties = new RenderServerProperties("resources/render-server.properties");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderServerProperties.class);
}
