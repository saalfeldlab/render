package org.janelia.alignment.match;

import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.SimilarityModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

/**
 * Utility to map an enumeration of supported model names to instance factories.
 *
 * @author Eric Trautman
 */
public enum ModelType {

    TRANSLATION(
            new ModelFactory() {
                @Override
                public Model getInstance() {
                    return new TranslationModel2D();
                }
            }),
    RIGID(
            new ModelFactory() {
                @Override
                public Model getInstance() {
                    return new RigidModel2D();
                }
            }),
    SIMILARITY(
            new ModelFactory() {
                @Override
                public Model getInstance() {
                    return new SimilarityModel2D();
                }
            }),
    AFFINE(
            new ModelFactory() {
                @Override
                public Model getInstance() {
                    return new AffineModel2D();
                }
            });


    private final ModelFactory modelFactory;

    ModelType(final ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    public Model getInstance() {
        return modelFactory.getInstance();
    }

    private interface ModelFactory {
        Model getInstance();
    }
}
