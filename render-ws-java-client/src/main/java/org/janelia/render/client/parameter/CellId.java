package org.janelia.render.client.parameter;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.ParameterException;

import java.util.regex.Pattern;

/**
 * Identifies a row and column cell position.
 *
 * @author Eric Trautman
 */
public class CellId
        implements Comparable<CellId> {

    public final int row;
    public final int column;

    public CellId(final String value)
            throws IllegalArgumentException {
        final int[] intValues = parseCellIdString(value);
        this.row = intValues[0];
        this.column = intValues[1];
    }

    public CellId(final Integer row,
                  final Integer column) {
        this.row = row;
        this.column = column;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final CellId that = (CellId) o;

        if (row != that.row) {
            return false;
        }
        return column == that.column;
    }

    @Override
    public int hashCode() {
        int result = row;
        result = 31 * result + column;
        return result;
    }

    @Override
    public int compareTo(final CellId that) {
        int result = this.row - that.row;
        if (result == 0) {
            result = this.column - that.column;
        }
        return result;
    }

    @Override
    public String toString() {
        return "{\"row\": " + row +
               ", \"column\": " + column +
               '}';
    }

    public static class StringConverter implements IStringConverter<CellId> {


        @Override
        public CellId convert(final String value) {
            final CellId cellId;
            try {
                cellId = new CellId(value);
            } catch (final Exception e) {
                throw new ParameterException("invalid cell value", e);
            }
            return cellId;
        }
    }

    private static final Pattern CELL_ID_STRING_PATTERN = Pattern.compile(",");

    private static int[] parseCellIdString(final String value) {
        final String[] stringValues = CELL_ID_STRING_PATTERN.split(value);
        final int[] values = new int[2];
        if (stringValues.length == 2) {
            try {
                values[0] = Integer.parseInt(stringValues[0]);
                values[1] = Integer.parseInt(stringValues[1]);
            } catch (final NumberFormatException nfe) {
                throw new IllegalArgumentException("Cell identifier must be 'row,column' with integral values", nfe);
            }
        } else {
            throw new IllegalArgumentException("Cell identifier must be 'row,column' with integral values");
        }
        return values;
    }

}
