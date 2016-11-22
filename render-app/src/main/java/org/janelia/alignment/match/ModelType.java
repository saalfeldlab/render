package org.janelia.alignment.match;

import mpicbg.models.Affine2D;
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
                @SuppressWarnings("unchecked")
                @Override
                public TranslationModel2D getInstance() {
                    return new TranslationModel2D();
                }
            }),
    RIGID(
            new ModelFactory() {
                @SuppressWarnings("unchecked")
                @Override
                public RigidModel2D getInstance() {
                    return new RigidModel2D();
                }
            }),
    SIMILARITY(
            new ModelFactory() {
                @SuppressWarnings("unchecked")
                @Override
                public SimilarityModel2D getInstance() {
                    return new SimilarityModel2D();
                }
            }),
    AFFINE(
            new ModelFactory() {
                @SuppressWarnings("unchecked")
                @Override
                public AffineModel2D getInstance() {
                    return new AffineModel2D();
                }
            });


    private final ModelFactory modelFactory;

    ModelType(final ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    public <T extends Model & Affine2D> T getInstance() {
        return modelFactory.getInstance();
    }

    private interface ModelFactory {
        <T extends Model & Affine2D> T getInstance();
    }
}
