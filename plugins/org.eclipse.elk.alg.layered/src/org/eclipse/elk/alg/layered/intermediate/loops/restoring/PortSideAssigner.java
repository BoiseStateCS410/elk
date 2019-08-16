/*******************************************************************************
 * Copyright (c) 2019 Kiel University and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.elk.alg.layered.intermediate.loops.restoring;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.eclipse.elk.alg.layered.intermediate.loops.SelfHyperLoop;
import org.eclipse.elk.alg.layered.intermediate.loops.SelfLoopHolder;
import org.eclipse.elk.alg.layered.intermediate.loops.SelfLoopPort;
import org.eclipse.elk.alg.layered.options.InternalProperties;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.options.PortSide;

/**
 * Assigns self loop ports to the northern or southern port side. How this is done exactly depends on the
 * {@link LayeredOptions#EDGE_ROUTING_SELF_LOOP_DISTRIBUTION self loop distribution strategy}. All this class will do
 * is assign port sides to hidden self loop ports. It will not make them visible again.
 */
public class PortSideAssigner {
    
    /**
     * Assigns port sides to all hidden ports subject to the self loop distribution strategy.
     */
    public void assignPortSides(final SelfLoopHolder slHolder) {
        assert !slHolder.getLNode().getProperty(InternalProperties.ORIGINAL_PORT_CONSTRAINTS).isSideFixed();
        
        switch (slHolder.getLNode().getProperty(LayeredOptions.EDGE_ROUTING_SELF_LOOP_DISTRIBUTION)) {
        case NORTH:
            assignToNorthSide(slHolder);
            break;
            
        case NORTH_SOUTH:
            assignToNorthOrSouthSide(slHolder);
            break;
            
        case EQUALLY:
            assignToAllSides(slHolder);
            break;
            
        default:
            assert false;
        }
        
        // Have the loops compute which of their ports are on which side
        slHolder.getSLHyperLoops().stream().forEach(slLoop -> slLoop.computePortsPerSide());
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // North

    /**
     * Assigns all self loops to the northern side.
     */
    private void assignToNorthSide(final SelfLoopHolder slHolder) {
        slHolder.getSLHyperLoops().stream()
            .flatMap(slLoop -> slLoop.getSLPorts().stream())
            .map(slPort -> slPort.getLPort())
            .forEach(lPort -> lPort.setSide(PortSide.NORTH));
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // North, South

    /**
     * Distributes all self loops to the northern or southern sides.
     */
    private void assignToNorthOrSouthSide(final SelfLoopHolder slHolder) {
        // Self loops can consist of more than two ports. Instead of simply assigning an equal number of self loops to
        // both sides, we'd rather end up with a similar number of ports on both sides. This is what we're trying to
        // to here. If one side falls behind, we fill that up with ports until the other falls behind. Beware: greedy
        // algorithm ahead!
        int northPorts = 0;
        int southPorts = 0;
        
        for (SelfHyperLoop slLoop : slHolder.getSLHyperLoops()) {
            // Decide on a port side
            PortSide newPortSide = null;
            if (northPorts <= southPorts) {
                newPortSide = PortSide.NORTH;
                northPorts += slLoop.getSLPorts().size();
                
            } else if (southPorts < northPorts) {
                newPortSide = PortSide.SOUTH;
                southPorts += slLoop.getSLPorts().size();
            }
            
            // Assign the ports
            final PortSide finalNewPortSide = newPortSide;
            slLoop.getSLPorts().stream()
                .map(slPort -> slPort.getLPort())
                .forEach(lPort -> lPort.setSide(finalNewPortSide));
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Equal Distribution

    /**
     * The way we'll be distributing our ports. Self loops will be assigned to sides following this pattern.
     */
    private static enum Target {
        NORTH(PortSide.NORTH, PortSide.NORTH),
        SOUTH(PortSide.SOUTH, PortSide.SOUTH),
        EAST(PortSide.EAST, PortSide.EAST),
        WEST(PortSide.WEST, PortSide.WEST),
        NORTH_WEST_CORNER(PortSide.WEST, PortSide.NORTH),
        NORTH_EAST_CORNER(PortSide.NORTH, PortSide.EAST),
        SOUTH_WEST_CORNER(PortSide.SOUTH, PortSide.WEST),
        SOUTH_EAST_CORNER(PortSide.EAST, PortSide.SOUTH);
        
        /** The first port side involved in the target. */
        private final PortSide firstSide;
        /** The second port side involved in the target. */
        private final PortSide secondSide;
        
        Target(final PortSide firstSide, final PortSide secondSide) {
            this.firstSide = firstSide;
            this.secondSide = secondSide;
        }
        
        /**
         * Returns whether the target spans the corner between two sides.
         */
        boolean isCornerTarget() {
            return firstSide != secondSide;
        }
    }

    /**
     * Distributes self loops to all sides.
     */
    private void assignToAllSides(final SelfLoopHolder slHolder) {
        // Obtain a list of self hyper loops, ordered descendingly by the number of involved ports. This will later tend
        // to place hyper loops with many ports not across corners, but on a single side
        SortedSet<SelfHyperLoop> slSortedLoops = new TreeSet<>(
                (slLoop1, slLoop2) -> Integer.compare(slLoop2.getSLPorts().size(), slLoop1.getSLPorts().size()));
        
        // Iterate over our self loops and assign each to the next target
        Target[] assignmentTargets = Target.values();
        int currLoop = 0;
        
        for (SelfHyperLoop slLoop : slSortedLoops) {
            Target currTarget = assignmentTargets[currLoop % assignmentTargets.length];
            assignToTarget(slLoop, currTarget);
            
            currLoop++;
        }
    }

    /**
     * Assigns the given self loop to the given target.
     */
    private void assignToTarget(final SelfHyperLoop slLoop, final Target target) {
        // Gather the self loop's ports
        List<SelfLoopPort> slPorts = new ArrayList<>(slLoop.getSLPorts());
        
        // If this is a corner target, we sort the list of ports by net flow: ports with more outgoing edges should end
        // up at the first side, while ports with more incoming edges should end up on the second side. This is an
        // attempt to have most edges point clockwise. If this is not a corner target, all ports will end up on the
        // same side anyway, so there's no need for us to sort anything
        if (target.isCornerTarget()) {
            slPorts.sort((slPort1, slPort2) -> Integer.compare(
                    slPort1.getLPort().getNetFlow(),
                    slPort2.getLPort().getNetFlow()));
        }
        
        // Assign the first half of the ports to the first side, and the second half to the second side
        int secondHalfStartIndex = slPorts.size() / 2;
        
        for (int i = 0; i < secondHalfStartIndex; i++) {
            slPorts.get(i).getLPort().setSide(target.firstSide);
        }
        
        for (int i = secondHalfStartIndex; i < slPorts.size(); i++) {
            slPorts.get(i).getLPort().setSide(target.secondSide);
        }
    }

}
