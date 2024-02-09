package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Parameters for target stack.
 *
 * @author Michael Innerberger
 */
@Parameters
public class TargetStackParameters implements Serializable {
	@Parameter(
			names = "--targetOwner",
			description = "Owner name for result stack (default is same as owner)"
	)
	public String owner;

	@Parameter(
			names = "--targetProject",
			description = "Project name for result stack (default is same as project)"
	)
	public String project;

	@Parameter(
			names = "--targetStack",
			description = "Name for result stack (if omitted, models are simply logged)")
	public String stack;

	@Parameter(
			names = "--targetStackSuffix",
			description = "Suffix to append to the target stack name when it is to be derived from the source stack name")
	public String stackSuffix;

	@Parameter(
			names = "--completeTargetStack",
			description = "Complete the target stack after processing",
			arity = 0)
	public boolean completeStack = false;

	public void setValuesFromPipeline(final StackId sourceStackId,
									  final String defaultSuffix) {
		this.owner = sourceStackId.getOwner();
		this.project = sourceStackId.getProject();
		String suffix = defaultSuffix;
		if (stackSuffix != null && (! stackSuffix.trim().isEmpty())) {
			suffix = stackSuffix.trim();
		}
		this.stack = sourceStackId.getStack() + suffix;
	}
}
