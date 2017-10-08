package org.janelia.render.service;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/**
 * Maps all requests "/*" to the jax-rs web services.
 * This servlet 3.0 approach replaces what used to be configured in web.xml.
 *
 * @author Eric Trautman
 */
@ApplicationPath("/")
public class BaseApplication extends Application {
}
