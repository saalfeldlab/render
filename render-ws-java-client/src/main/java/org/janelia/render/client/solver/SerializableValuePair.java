package org.janelia.render.client.solver;

import java.io.Serial;
import java.io.Serializable;

import net.imglib2.util.Pair;

public record SerializableValuePair<A, B>(A a, B b)
        implements Pair<A, B>, Serializable {
    @Serial
    private static final long serialVersionUID = -2500067547792077916L;

    @Override
    public A getA() {
        return a;
    }

    @Override
    public B getB() {
        return b;
    }
}
