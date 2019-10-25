package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.function.Supplier;

import mpicbg.models.Affine2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.InterpolatedModel;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.SimilarityModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

/**
 * Utility to map an enumeration of supported model names to instances.
 *
 * Interpolated model supplier code stolen from
 *
 * <a href="https://github.com/saalfeldlab/hot-knife/blob/master/src/main/java/org/janelia/saalfeldlab/hotknife/util/Transform.java#L76-L129">
 *     https://github.com/saalfeldlab/hot-knife/blob/master/src/main/java/org/janelia/saalfeldlab/hotknife/util/Transform.java#L76-L129
 * </a>
 *
 * @author Eric Trautman
 */
public enum ModelType {

    TRANSLATION( (ModelSupplier<TranslationModel2D>) TranslationModel2D::new ),
    RIGID( (ModelSupplier<RigidModel2D>) RigidModel2D::new ),
    SIMILARITY( (ModelSupplier<SimilarityModel2D>) SimilarityModel2D::new ),
    AFFINE( (ModelSupplier<AffineModel2D>) AffineModel2D::new );

    private final ModelSupplier supplier;

    ModelType(final ModelSupplier supplier) {
        this.supplier = supplier;
    }

    public <T extends Model & Affine2D> T getInstance() {
        //noinspection unchecked
        return (T) supplier.get();
    }

    public InterpolatedAffineModel2D getInterpolatedInstance(final ModelType regularizerModelType,
                                                             final double lambda) {
        final InterpolatedAffineModel2DSupplier supplier =
                new InterpolatedAffineModel2DSupplier(this.supplier,
                                                      regularizerModelType.supplier,
                                                      lambda);
        return supplier.get();
    }

    @SuppressWarnings("serial")
    private interface ModelSupplier<T extends Model & Affine2D> extends Supplier<T>, Serializable {
    }

    @SuppressWarnings("serial")
    private static abstract class AbstractInterpolatedModelSupplier<
            A extends Model<A>,
            B extends Model<B>,
            C extends InterpolatedModel<A, B, C>
            > implements Supplier<C>, Serializable {

        final Supplier<A> aSupplier;
        final Supplier<B> bSupplier;
        final double lambda;

        <SA extends Supplier<A> & Serializable, SB extends Supplier<B> & Serializable> AbstractInterpolatedModelSupplier(
                final SA aSupplier,
                final SB bSupplier,
                final double lambda) {

            this.aSupplier = aSupplier;
            this.bSupplier = bSupplier;
            this.lambda = lambda;
        }
    }

//    @SuppressWarnings("serial")
//    public static class InterpolatedModelSupplier<
//            A extends Model<A>,
//            B extends Model<B>,
//            C extends InterpolatedModel<A, B, C>
//            > extends AbstractInterpolatedModelSupplier<A, B, C>{
//
//        <SA extends Supplier<A> & Serializable, SB extends Supplier<B> & Serializable> InterpolatedModelSupplier(
//                final SA aSupplier,
//                final SB bSupplier,
//                final double lambda) {
//
//            super(aSupplier, bSupplier, lambda);
//        }
//
//        @SuppressWarnings("unchecked")
//        @Override
//        public C get() {
//
//            return (C)new InterpolatedModel<>(aSupplier.get(), bSupplier.get(), lambda);
//        }
//    }
//
    @SuppressWarnings("serial")
    public static class InterpolatedAffineModel2DSupplier<
            A extends Model<A> & Affine2D<A>,
            B extends Model<B> & Affine2D<B>
            > extends AbstractInterpolatedModelSupplier<A, B, InterpolatedAffineModel2D<A, B>> {

        <SA extends Supplier<A> & Serializable, SB extends Supplier<B> & Serializable> InterpolatedAffineModel2DSupplier(
                final SA aSupplier,
                final SB bSupplier,
                final double lambda) {

            super(aSupplier, bSupplier, lambda);
        }

        @Override
        public InterpolatedAffineModel2D<A, B> get() {

            return new InterpolatedAffineModel2D<>(aSupplier.get(), bSupplier.get(), lambda);
        }
    }
}
