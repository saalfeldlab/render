package org.janelia.render.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compound identifier for a stack.
 *
 * @author Eric Trautman
 */
public class StackId {

    private String owner;
    private String project;
    private String stack;

    public StackId(String owner,
                   String project,
                   String stack)
            throws IllegalArgumentException {

        validateValue("owner", VALID_OWNER_OR_PROJECT_NAME, owner);
        validateValue("project", VALID_OWNER_OR_PROJECT_NAME, project);
        validateValue("stack", VALID_STACK_NAME, stack);

        this.owner = owner;
        this.project = project;
        this.stack = stack;
    }

    public String getOwner() {
        return owner;
    }

    @Override
    public String toString() {
        return "StackId{" +
               "owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               ", stack='" + stack + '\'' +
               '}';
    }

    public String getDatabaseName() {
        return owner + '-' + project + '-' + stack;
    }

    public static StackId fromDatabaseName(String databaseName) {
        StackId stackId = null;
        final int nameLength = databaseName.length();
        final int stopOwner = databaseName.indexOf('-');
        final int startProject = stopOwner + 1;
        if ((startProject > 0) && (startProject < nameLength)) {
            final int stopProject = databaseName.indexOf('-', startProject);
            final int startStack = stopProject + 1;
            if ((startStack > startProject) && (startStack < nameLength)) {
                stackId = new StackId(databaseName.substring(0, stopOwner),
                                      databaseName.substring(startProject, stopProject),
                                      databaseName.substring(startStack));
            }

        }
        return stackId;
    }

    private void validateValue(String context,
                               Pattern pattern,
                               String value)
            throws IllegalArgumentException {

        final Matcher m = pattern.matcher(value);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid " + context + " '" + value + "' specified");
        }
    }

    private static final Pattern VALID_OWNER_OR_PROJECT_NAME = Pattern.compile("[A-Za-z0-9]++");
    private static final Pattern VALID_STACK_NAME = Pattern.compile("[A-Za-z0-9\\-]++");
}
