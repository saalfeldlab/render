package org.janelia.render.client.parameter;

import java.io.Serializable;
import java.lang.reflect.Constructor;

import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * Parameters for specifying a tile validator instance.
 *
 * @author Eric Trautman
 */
@Parameters
public class TileSpecValidatorParameters implements Serializable {

    @Parameter(
            names = "--validatorClass",
            description = "Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).  Exclude to skip validation.",
            required = false)
    public String validatorClass;

    @Parameter(
            names = "--validatorData",
            description = "Initialization data for validator instance.",
            required = false)
    public String validatorData;

    public TileSpecValidator getValidatorInstance()
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
                LOG.info("getValidatorInstance: created {}", validatorInstance);
            }
        }

        return validatorInstance;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileSpecValidatorParameters.class);

}