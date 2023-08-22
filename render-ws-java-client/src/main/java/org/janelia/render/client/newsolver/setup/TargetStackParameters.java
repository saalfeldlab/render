package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

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
			names = "--completeTargetStack",
			description = "Complete the target stack after processing",
			arity = 0)
	public boolean completeStack = false;
}
