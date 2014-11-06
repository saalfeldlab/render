package org.janelia.render.service;

import com.mongodb.MongoClient;
import org.janelia.render.service.dao.RenderParametersDao;
import org.janelia.render.service.dao.SharedMongoClient;

import java.net.UnknownHostException;

/**
 * Shared utility methods for all Render services.
 *
 * @author Eric Trautman
 */
public class RenderServiceUtil {

    public static RenderParametersDao buildDao()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new RenderParametersDao(mongoClient);
    }

    public static void throwServiceException(Throwable t)
            throws ServiceException {

        if (t instanceof ServiceException) {
            throw (ServiceException) t;
        } else if (t instanceof IllegalArgumentException) {
            throw new IllegalServiceArgumentException(t.getMessage(), t);
        } else {
            throw new ServiceException(t.getMessage(), t);
        }
    }

}