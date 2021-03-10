package org.janelia.render.client.solver.interactive;

import java.awt.event.ActionEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.InputTrigger;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.Behaviours;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

import bdv.util.Bdv;

public abstract class MinimalInteractiveBdvOverlay< R > extends MinimalBdvOverlay
{
	protected String MAP = "interactive-object"; // change in your example
	protected String BLOCKING_MAP = "minimal-blocking"; // change the name in your example
	protected String[] TOGGLE_EDITOR_KEYS = new String[] { "" }; // change in your example, e.g. button1 

	final TriggerBehaviourBindings triggerbindings;
	final Behaviours behaviours;
	final BehaviourMap blockMap;

	final InputTriggerConfig config;
	final ActionMap ksActionMap;
	final InputMap ksInputMap;
	final KeyStrokeAdder ksKeyStrokeAdder;

	final MouseMotionAdapter adapter;

	/**
	 * which object is currently highlighted (set by the MouseMotionAdapter)
	 * it is >=0 if anything is selected, or -1 if nothing is selected
	 */
	int highLightedObjectId = -1;

	R result = null;

	// for notification when the user pressed ESC or ENTER
	private final Object monitor = new Object();

	public MinimalInteractiveBdvOverlay( final Bdv bdv )
	{
		super(bdv);

		triggerbindings = bdv.getBdvHandle().getTriggerbindings();

		/*
		 * Create Behaviours, add as necessary calling behaviours.behaviour( ...
		 * 
		 * https://github.com/scijava/ui-behaviour/blob/master/src/test/java/org/scijava/ui/behaviour/UsageExample.java
		 */
		behaviours = new Behaviours( new InputTriggerConfig(), "bdv" );

		/*
		 * setting up keystrokes
		 * 
		 * https://github.com/scijava/ui-behaviour/wiki/InputTrigger-syntax
		 */
		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		config = new InputTriggerConfig(
				Arrays.asList(
						new InputTriggerDescription[]{new InputTriggerDescription(
								new String[]{"not mapped"},
								"drag rotate slow",
								"bdv")}));

		ksActionMap = new ActionMap();
		ksInputMap = new InputMap();
		ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new Cancel().register(ksActionMap, ksKeyStrokeAdder);
		new Confirm().register(ksActionMap, ksKeyStrokeAdder);

		//Call installKeyStrokes() after all keys are added
		
		/*
		 * Create BehaviourMap to block behaviours interfering with
		 * DragPointBehaviour. The block map is only active while a certain element
		 * is highlighted.
		 */
		blockMap = new BehaviourMap();

		/*
		 * install a mouse motion adapter
		 */
		adapter = setUpMouseMotionAdapter();
	}

	protected abstract MouseMotionAdapter setUpMouseMotionAdapter();

	protected void installKeyStrokes()
	{
		bdv.getBdvHandle().getKeybindings().addActionMap("persistence", ksActionMap);
		bdv.getBdvHandle().getKeybindings().addInputMap("persistence", ksInputMap);
	}

	public void install()
	{
		super.install();
		bdv.getBdvHandle().getViewerPanel().getDisplay().addHandler( adapter );

		//refreshBlockMap();
		//updateEditability();
		buildBlockMap();
		behaviours.install( triggerbindings, MAP );
	}

	public void uninstall()
	{
		super.uninstall();
		bdv.getBdvHandle().getViewerPanel().getDisplay().removeHandler( adapter );

		triggerbindings.removeInputTriggerMap( MAP );
		triggerbindings.removeBehaviourMap( MAP );

		unblock();
	}

	protected void setHighlightedId( final int id )
	{
		final int oldId = highLightedObjectId;
		highLightedObjectId = id;
		if ( highLightedObjectId != oldId )
		{
			if ( highLightedObjectId < 0 )
				unblock();
			else
				block();
		}
	}

	protected void block()
	{
		triggerbindings.addBehaviourMap( BLOCKING_MAP, blockMap );
	}

	protected void unblock()
	{
		triggerbindings.removeBehaviourMap( BLOCKING_MAP );
	}

	protected void buildBlockMap()
	{
		triggerbindings.removeBehaviourMap( BLOCKING_MAP );

		final Set< InputTrigger > segmentedLineTriggers = new HashSet<>();
		for ( final String s : TOGGLE_EDITOR_KEYS )
			segmentedLineTriggers.add( InputTrigger.getFromString( s ) );

		final Map< InputTrigger, Set< String > > bindings = triggerbindings.getConcatenatedInputTriggerMap().getAllBindings();
		final Set< String > behavioursToBlock = new HashSet<>();
		for ( final InputTrigger t : segmentedLineTriggers )
			behavioursToBlock.addAll( bindings.getOrDefault( t, Collections.emptySet() ) );

		blockMap.clear();
		final Behaviour block = new Behaviour() {};
		for ( final String key : behavioursToBlock )
			blockMap.put( key, block );
	}

	public R getResult()
	{
		synchronized ( monitor )
		{
			install();

			while( true )
			{
				try
				{
					monitor.wait();
					break;
				}
				catch ( final InterruptedException e )
				{
					e.printStackTrace();
				}
			}

			uninstall();
		}

		return result;
	}

	public class Confirm extends AbstractNamedAction
	{
		private static final long serialVersionUID = -8388751240134830158L;

		public Confirm() { super( "Confirm" ); }

		@Override
		public void actionPerformed(ActionEvent e)
		{
			synchronized ( monitor )
			{
				monitor.notifyAll();
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ENTER" );
		}
	}

	public class Cancel extends AbstractNamedAction
	{
		private static final long serialVersionUID = 7966320561651920538L;

		public Cancel() { super( "Cancel" ); }

		@Override
		public void actionPerformed(ActionEvent e)
		{
			synchronized ( monitor )
			{
				result = null;
				monitor.notifyAll();
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ESCAPE" );
		}
	}
}
