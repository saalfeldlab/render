package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;

public class ImageCalculatorFilter implements Filter {

    public enum BinaryOperation {
        ADD(Double::sum),
        SUBTRACT((a, b) -> a - b),
        MULTIPLY((a, b) -> a * b),
        DIVIDE((a, b) -> a / b),
        MIN(Math::min),
        MAX(Math::max),
        AVERAGE((a, b) -> (a + b) / 2.0),
        COPY((a, b) -> b),
        DIFFERENCE((a, b) -> Math.abs(a - b));

        private final DoubleBinaryOperator operator;

        BinaryOperation(final DoubleBinaryOperator operator) {
            this.operator = operator;
        }

        public double apply(final double a, final double b) {
            return operator.applyAsDouble(a, b);
        }

        public static BinaryOperation fromString(final String operation) {
            for (final BinaryOperation op : values()) {
                if (op.name().equalsIgnoreCase(operation)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("unknown operation: " + operation);
        }
    }

    private String secondImageUri;
    private ImageProcessor secondImage;
    private BinaryOperation operation;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public ImageCalculatorFilter() {
        this(null, null);
    }

    public ImageCalculatorFilter(final String secondImageUri, final BinaryOperation operation) {
        this.secondImageUri = secondImageUri;
        this.operation = operation;
        initializeSecondImage();
    }

    @Override
    public void init(final Map<String, String> params) {
        this.operation = BinaryOperation.fromString(Filter.getStringParameter("operation", params));
        this.secondImageUri = Filter.getStringParameter("secondImageUri", params);
        initializeSecondImage();
    }

    private void initializeSecondImage() {
        if (secondImageUri == null || operation == null) {
            throw new IllegalStateException("secondImageUri and operator must be set before initializing the second image");
        }
        final ImagePlus imp = new ImagePlus(secondImageUri);
        secondImage = imp.getProcessor().convertToFloat();
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("operation", operation.name());
        map.put("secondImageUri", secondImageUri);
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        // TODO: check if not allowing scaling makes sense
        if (scale != 1.0) {
            throw new IllegalArgumentException("scaling is not supported for this filter");
        }

        // convert to 32-bit grayscale (float) for lossless processing
        final ImageProcessor firstImage = ip.convertToFloat();

        // apply the operation
        for (int i = 0; i < ip.getPixelCount(); i++) {
            final double a = firstImage.getf(i);
            final double b = secondImage.getf(i);
            ip.setf(i, (float) operation.apply(a, b));
        }
    }
}
