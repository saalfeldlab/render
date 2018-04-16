package org.janelia.render.service.swagger;

import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.util.Json;
import io.swagger.util.Yaml;

/**
 * Servlet that handles initializing Swagger
 * (see <a href="https://github.com/swagger-api/swagger-core/wiki/Swagger-Core-RESTEasy-2.X-Project-Setup#using-swaggers-beanconfig">
 *     </a>
 *
 * @author Eric Trautman
 */
public class SwaggerBootstrapServlet
        extends HttpServlet {

    @Override
    public void init(final ServletConfig config) throws ServletException {
        super.init(config);

        final BeanConfig beanConfig = loadConfig(new File("resources/swagger.properties"));
        beanConfig.setVersion("v1");
        beanConfig.setSchemes(new String[]{"http"});
        beanConfig.setBasePath("/render-ws");
        beanConfig.setResourcePackage("org.janelia.render.service");
        beanConfig.setScan(true);
        beanConfig.setPrettyPrint(true);

        // Needed to register these modules to get Swagger to use JAXB annotations
        // (see https://github.com/swagger-api/swagger-core/issues/960 for explanation)
        Json.mapper().registerModule(new JaxbAnnotationModule());
        Yaml.mapper().registerModule(new JaxbAnnotationModule());
    }

    public static BeanConfig loadConfig(final File file)
            throws IllegalArgumentException {

        final BeanConfig beanConfig = new BeanConfig();
        final Properties properties = new Properties();

        final String path = file.getAbsolutePath();
        if (file.exists()) {

            try (final FileInputStream in = new FileInputStream(file)) {
                properties.load(in);
            } catch (final Exception e) {
                throw new IllegalArgumentException("failed to load properties from " + path, e);
            }

            LOG.info("loaded swagger properties from {}", path);

        } else {
            LOG.warn("{} not found, using default configuration for swagger", path);
        }

        beanConfig.setTitle(properties.getProperty("title", "Render Web Service APIs"));

        // default to empty contact so that swagger-ui will hide created by line
        beanConfig.setContact(properties.getProperty("contact", ""));

        // default to empty host so that swagger-ui will use same host for try-it-out requests
        beanConfig.setHost(properties.getProperty("host", ""));

        beanConfig.setBasePath(properties.getProperty("basePath", "/render-ws"));

        return beanConfig;
    }

    private static final Logger LOG = LoggerFactory.getLogger(SwaggerBootstrapServlet.class);


}
