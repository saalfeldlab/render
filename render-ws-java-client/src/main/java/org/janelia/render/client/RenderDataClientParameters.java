package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.lang.reflect.Constructor;

import org.janelia.alignment.spec.validator.TileSpecValidator;

/**
 * Base parameters for all render web service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class RenderDataClientParameters
        extends CommandLineParameters {

    @Parameter(
            names = "--baseDataUrl",
            description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
            required = true)
    protected String baseDataUrl;

    @Parameter(
            names = "--owner",
            description = "Owner for all stacks",
            required = true)
    protected String owner;

    @Parameter(
            names = "--project",
            description = "Project for all stacks",
            required = true)
    protected String project;

    @Parameter(
            names = "--validatorClass",
            description = "Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).  Exclude to skip validation.",
            required = false)
    protected String validatorClass;

    @Parameter(
            names = "--validatorData",
            description = "Initialization data for validator instance.",
            required = false)
    protected String validatorData;

    protected TileSpecValidator getValidatorInstance()
            throws IllegalArgumentException {

        TileSpecValidator validatorInstance = null;

        if (validatorClass != null) {

            final String context = "validatorClass '" + validatorClass + "' ";

            final Class<?> clazz;
            try {
                clazz = Class.forName(validatorClass);
            } catch (final ClassNotFoundException e) {
                throw new IllegalArgumentException(context + "cannot be found", e);
            }

            final Class<?>[] args = new Class[0];
            final Constructor constructor;
            try {
                constructor = clazz.getConstructor(args);
            } catch (final NoSuchMethodException e) {
                throw new IllegalArgumentException(context + "does not have an empty constructor", e);
            }

            final Object newInstance;
            try {
                newInstance = constructor.newInstance();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalArgumentException("an instance of " + context + "cannot be created", e);
            }

            if (newInstance instanceof TileSpecValidator) {
                validatorInstance = (TileSpecValidator) newInstance;
            } else {
                throw new IllegalArgumentException(context + "does not implement the " +
                                                   TileSpecValidator.class + " interface");
            }

            if (validatorData != null) {
                validatorInstance.init(validatorData);
            }
        }

        return validatorInstance;
    }

    public RenderDataClientParameters() {
        this.baseDataUrl = null;
        this.owner = null;
        this.project = null;
    }

}