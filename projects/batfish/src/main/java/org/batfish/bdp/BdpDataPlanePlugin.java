package org.batfish.bdp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.apache.commons.collections4.map.LRUMap;
import org.batfish.common.BatfishException;
import org.batfish.common.Version;
import org.batfish.common.plugin.DataPlanePlugin;
import org.batfish.common.util.CommonUtil;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.BgpProcess;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Edge;
import org.batfish.datamodel.FilterResult;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.FlowDisposition;
import org.batfish.datamodel.FlowTrace;
import org.batfish.datamodel.FlowTraceHop;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.Route;
import org.batfish.datamodel.RouteBuilder;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.SourceNat;
import org.batfish.datamodel.Topology;
import org.batfish.datamodel.answers.Answer;
import org.batfish.datamodel.answers.BdpAnswerElement;
import org.batfish.datamodel.collections.AdvertisementSet;
import org.batfish.datamodel.collections.EdgeSet;
import org.batfish.datamodel.collections.IbgpTopology;
import org.batfish.datamodel.collections.NodeInterfacePair;
import org.batfish.datamodel.collections.RouteSet;

public class BdpDataPlanePlugin extends DataPlanePlugin {

  /**
   * Set to true to debug all iterations, including during oscillation. Ignores max recorded
   * iterations value.
   */
  private static boolean DEBUG_ALL_ITERATIONS = false;

  private static final int DEBUG_MAX_RECORDED_ITERATIONS = 12;

  /**
   * Set to true to debug oscillation. Make sure to set max recorded iterations to minimum necessary
   * value.
   */
  private static boolean DEBUG_REPEAT_ITERATIONS = false;

  private static final String TRACEROUTE_INGRESS_NODE_INTERFACE_NAME =
      "traceroute_source_interface";

  private static final String TRACEROUTE_INGRESS_NODE_NAME = "traceroute_source_node";

  private final Map<BdpDataPlane, Map<Flow, Set<FlowTrace>>> _flowTraces;

  public BdpDataPlanePlugin() {
    _flowTraces = new HashMap<>();
  }

  private void collectFlowTraces(
      BdpDataPlane dp,
      String currentNodeName,
      Set<Edge> visitedEdges,
      List<FlowTraceHop> hopsSoFar,
      Set<FlowTrace> flowTraces,
      Flow originalFlow,
      Flow transformedFlow) {
    Ip dstIp = transformedFlow.getDstIp();
    Set<String> dstIpOwners = dp._ipOwners.get(dstIp);
    if (dstIpOwners != null && dstIpOwners.contains(currentNodeName)) {
      FlowTrace trace =
          new FlowTrace(FlowDisposition.ACCEPTED, hopsSoFar, FlowDisposition.ACCEPTED.toString());
      flowTraces.add(trace);
    } else {
      Node currentNode = dp._nodes.get(currentNodeName);
      String vrfName;
      if (hopsSoFar.isEmpty()) {
        vrfName = transformedFlow.getIngressVrf();
      } else {
        FlowTraceHop lastHop = hopsSoFar.get(hopsSoFar.size() - 1);
        String receivingInterface = lastHop.getEdge().getInt2();
        vrfName = currentNode._c.getInterfaces().get(receivingInterface).getVrf().getName();
      }
      VirtualRouter currentVirtualRouter = currentNode._virtualRouters.get(vrfName);
      Map<AbstractRoute, Map<String, Map<Ip, Set<AbstractRoute>>>> nextHopInterfacesByRoute =
          currentVirtualRouter._fib.getNextHopInterfacesByRoute(dstIp);
      Map<String, Map<Ip, Set<AbstractRoute>>> nextHopInterfacesWithRoutes =
          currentVirtualRouter._fib.getNextHopInterfaces(dstIp);
      if (!nextHopInterfacesWithRoutes.isEmpty()) {
        for (String nextHopInterfaceName : nextHopInterfacesWithRoutes.keySet()) {
          // SortedSet<String> routesForThisNextHopInterface = new
          // TreeSet<>(
          // nextHopInterfacesWithRoutes.get(nextHopInterfaceName)
          // .stream().map(ar -> ar.toString())
          // .collect(Collectors.toSet()));
          SortedSet<String> routesForThisNextHopInterface = new TreeSet<>();
          Ip finalNextHopIp = null;
          for (Entry<AbstractRoute, Map<String, Map<Ip, Set<AbstractRoute>>>> e :
              nextHopInterfacesByRoute.entrySet()) {
            AbstractRoute routeCandidate = e.getKey();
            Map<String, Map<Ip, Set<AbstractRoute>>> routeCandidateNextHopInterfaces = e.getValue();
            if (routeCandidateNextHopInterfaces.containsKey(nextHopInterfaceName)) {
              Ip nextHopIp = routeCandidate.getNextHopIp();
              if (!nextHopIp.equals(Route.UNSET_ROUTE_NEXT_HOP_IP)) {
                Set<Ip> finalNextHopIps =
                    routeCandidateNextHopInterfaces.get(nextHopInterfaceName).keySet();
                if (finalNextHopIps.size() > 1) {
                  throw new BatfishException(
                      "Can not currently handle multiple final next hop ips across multiple "
                          + "routes leading to one next hop interface");
                }
                Ip newFinalNextHopIp = finalNextHopIps.iterator().next();
                if (finalNextHopIp != null && !newFinalNextHopIp.equals(finalNextHopIp)) {
                  throw new BatfishException(
                      "Can not currently handle multiple final next hop ips for same next hop "
                          + "interface");
                }
                finalNextHopIp = newFinalNextHopIp;
              }
              routesForThisNextHopInterface.add(routeCandidate + "_fnhip:" + finalNextHopIp);
            }
          }
          NodeInterfacePair nextHopInterface =
              new NodeInterfacePair(currentNodeName, nextHopInterfaceName);
          if (nextHopInterfaceName.equals(Interface.NULL_INTERFACE_NAME)) {
            List<FlowTraceHop> newHops = new ArrayList<>(hopsSoFar);
            Edge newEdge =
                new Edge(
                    nextHopInterface,
                    new NodeInterfacePair(
                        Configuration.NODE_NONE_NAME, Interface.NULL_INTERFACE_NAME));
            FlowTraceHop newHop =
                new FlowTraceHop(
                    newEdge, routesForThisNextHopInterface, hopFlow(originalFlow, transformedFlow));
            newHops.add(newHop);
            FlowTrace nullRouteTrace =
                new FlowTrace(
                    FlowDisposition.NULL_ROUTED, newHops, FlowDisposition.NULL_ROUTED.toString());
            flowTraces.add(nullRouteTrace);
          } else {
            Interface outgoingInterface =
                dp._nodes
                    .get(nextHopInterface.getHostname())
                    ._c
                    .getInterfaces()
                    .get(nextHopInterface.getInterface());
            SourceNat sourceNat = outgoingInterface.getSourceNat();
            if (sourceNat != null) {
              boolean performTranslation = true;
              IpAccessList acl = sourceNat.getAcl();
              if (acl != null) {
                FilterResult result = acl.filter(transformedFlow);
                if (result.getAction() == LineAction.REJECT) {
                  performTranslation = false;
                }
              }
              if (performTranslation) {
                Ip natPoolStartIp = sourceNat.getPoolIpFirst();
                if (natPoolStartIp != null) {
                  Flow.Builder transformedFlowBuilder = new Flow.Builder(transformedFlow);
                  transformedFlowBuilder.setSrcIp(natPoolStartIp);
                  transformedFlow = transformedFlowBuilder.build();
                }
              }
            }
            EdgeSet edges = dp._topology.getInterfaceEdges().get(nextHopInterface);
            if (edges != null) {
              boolean continueToNextNextHopInterface = false;
              continueToNextNextHopInterface =
                  processCurrentNextHopInterfaceEdges(
                      dp,
                      currentNodeName,
                      visitedEdges,
                      hopsSoFar,
                      flowTraces,
                      originalFlow,
                      transformedFlow,
                      dstIp,
                      dstIpOwners,
                      nextHopInterfaceName,
                      routesForThisNextHopInterface,
                      finalNextHopIp,
                      nextHopInterface,
                      edges,
                      true);
              if (continueToNextNextHopInterface) {
                continue;
              }
            } else {
              /*
               * Should only get here for delta environment where
               * non-flow-sink interface from base has no edges in delta
               */
              Edge neighborUnreachbleEdge =
                  new Edge(
                      nextHopInterface,
                      new NodeInterfacePair(
                          Configuration.NODE_NONE_NAME, Interface.NULL_INTERFACE_NAME));
              FlowTraceHop neighborUnreachableHop =
                  new FlowTraceHop(
                      neighborUnreachbleEdge,
                      routesForThisNextHopInterface,
                      hopFlow(originalFlow, transformedFlow));
              List<FlowTraceHop> newHops = new ArrayList<>(hopsSoFar);
              newHops.add(neighborUnreachableHop);
              /** Check if denied out. If not, make standard neighbor-unreachable trace. */
              IpAccessList outFilter = outgoingInterface.getOutgoingFilter();
              boolean denied = false;
              if (outFilter != null) {
                FlowDisposition disposition = FlowDisposition.NEIGHBOR_UNREACHABLE_OR_DENIED_OUT;
                denied =
                    flowTraceDeniedHelper(
                        flowTraces, originalFlow, transformedFlow, newHops, outFilter, disposition);
              }
              if (!denied) {
                FlowTrace trace =
                    new FlowTrace(
                        FlowDisposition.NEIGHBOR_UNREACHABLE_OR_EXITS_NETWORK,
                        newHops,
                        FlowDisposition.NEIGHBOR_UNREACHABLE_OR_EXITS_NETWORK.toString());
                flowTraces.add(trace);
              }
            }
          }
        }
      } else {
        FlowTrace trace =
            new FlowTrace(FlowDisposition.NO_ROUTE, hopsSoFar, FlowDisposition.NO_ROUTE.toString());
        flowTraces.add(trace);
      }
    }
  }

  @Override
  public Answer computeDataPlane(boolean differentialContext) {
    Answer answer = new Answer();
    BdpDataPlane dp = new BdpDataPlane();
    BdpAnswerElement ae = new BdpAnswerElement();
    Map<String, Configuration> configurations = _batfish.loadConfigurations();
    Topology topology = _batfish.computeTopology(configurations);
    _batfish.resetTimer();
    _logger.info("\n*** COMPUTING DATA PLANE ***\n");
    Map<Ip, Set<String>> ipOwners = _batfish.computeIpOwners(configurations, true);
    Map<Ip, String> ipOwnersSimple = _batfish.computeIpOwnersSimple(ipOwners);
    dp.initIpOwners(configurations, ipOwners, ipOwnersSimple);
    _batfish.initRemoteBgpNeighbors(configurations, dp.getIpOwners());
    Map<String, Node> nodes = new TreeMap<>();
    configurations.values().forEach(c -> nodes.put(c.getHostname(), new Node(c, nodes)));
    AdvertisementSet externalAdverts = _batfish.processExternalBgpAnnouncements(configurations);
    computeFixedPoint(nodes, topology, dp, externalAdverts, ae);
    computeFibs(nodes);
    dp.setNodes(nodes);
    dp.setTopology(topology);
    dp.setFlowSinks(_batfish.computeFlowSinks(configurations, differentialContext, topology));
    ae.setVersion(Version.getVersion());
    _batfish.newBatch("Writing data plane to disk", 0);
    _batfish.writeDataPlane(dp, ae);
    _batfish.printElapsedTime();
    answer.addAnswerElement(ae);
    return answer;
  }

  private void computeFibs(Map<String, Node> nodes) {
    AtomicInteger completed = _batfish.newBatch("Computing FIBs", nodes.size());
    nodes
        .values()
        .parallelStream()
        .forEach(
            n -> {
              for (VirtualRouter vr : n._virtualRouters.values()) {
                vr.computeFib();
              }
              completed.incrementAndGet();
            });
  }

  private void computeFixedPoint(
      Map<String, Node> nodes,
      Topology topology,
      BdpDataPlane dp,
      AdvertisementSet externalAdverts,
      BdpAnswerElement ae) {
    // BEGIN DONE ONCE (except main rib)
    // connected, initial static routes, ospf setup, bgp setup
    AtomicInteger initialCompleted =
        _batfish.newBatch(
            "Compute initial connected and static routes, ospf setup, bgp setup", nodes.size());
    nodes
        .values()
        .parallelStream()
        .forEach(
            n -> {
              for (VirtualRouter vr : n._virtualRouters.values()) {
                vr.initConnectedRib();
                vr.importRib(vr._independentRib, vr._connectedRib);
                vr.importRib(vr._mainRib, vr._connectedRib);
                vr.initStaticRib();
                vr.importRib(vr._independentRib, vr._staticInterfaceRib);
                vr.importRib(vr._mainRib, vr._staticInterfaceRib);
                vr.initBaseOspfRoutes();
                vr.initEbgpTopology(dp);
                vr.initBaseBgpRibs(externalAdverts, dp.getIpOwners());
              }
              initialCompleted.incrementAndGet();
            });

    final Object routesChangedMonitor = new Object();

    // OSPF internal routes
    final boolean[] ospfInternalChanged = new boolean[] {true};
    int ospfInternalIterations = 0;
    while (ospfInternalChanged[0]) {
      ospfInternalIterations++;
      ospfInternalChanged[0] = false;
      AtomicInteger ospfInterAreaSummaryCompleted =
          _batfish.newBatch(
              "Compute OSPF Inter-area summaries: iteration " + ospfInternalIterations,
              nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  if (vr.computeInterAreaSummaries()) {
                    synchronized (routesChangedMonitor) {
                      ospfInternalChanged[0] = true;
                    }
                  }
                }
                ospfInterAreaSummaryCompleted.incrementAndGet();
              });
      AtomicInteger ospfInternalCompleted =
          _batfish.newBatch(
              "Compute OSPF Internal routes: iteration " + ospfInternalIterations, nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  if (vr.propagateOspfInternalRoutes(nodes, topology)) {
                    synchronized (routesChangedMonitor) {
                      ospfInternalChanged[0] = true;
                    }
                  }
                }
                ospfInternalCompleted.incrementAndGet();
              });
      AtomicInteger ospfInternalUnstageCompleted =
          _batfish.newBatch(
              "Unstage OSPF Internal routes: iteration " + ospfInternalIterations, nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  vr.unstageOspfInternalRoutes();
                }
                ospfInternalUnstageCompleted.incrementAndGet();
              });
    }
    AtomicInteger ospfInternalImportCompleted =
        _batfish.newBatch("Import OSPF Internal routes", nodes.size());
    nodes
        .values()
        .parallelStream()
        .forEach(
            n -> {
              for (VirtualRouter vr : n._virtualRouters.values()) {
                vr.importRib(vr._ospfRib, vr._ospfIntraAreaRib);
                vr.importRib(vr._ospfRib, vr._ospfInterAreaRib);
                vr.importRib(vr._independentRib, vr._ospfRib);
              }
              ospfInternalImportCompleted.incrementAndGet();
            });
    // END DONE ONCE

    Map<Integer, Integer> iterationByHashCode = new HashMap<>();
    Map<Integer, Integer> iterationHashCodes = new TreeMap<>();
    Map<Integer, RouteSet> iterationRoutes = null;
    if (DEBUG_ALL_ITERATIONS) {
      iterationRoutes = new TreeMap<>();
    } else if (DEBUG_REPEAT_ITERATIONS && DEBUG_MAX_RECORDED_ITERATIONS > 1) {
      iterationRoutes = new LRUMap<>(DEBUG_MAX_RECORDED_ITERATIONS);
    }
    boolean[] dependentRoutesChanged = new boolean[] {true};
    int dependentRoutesIterations = 0;
    while (dependentRoutesChanged[0]) {
      dependentRoutesIterations++;
      dependentRoutesChanged[0] = false;
      // (Re)initialization of dependent route calculation
      AtomicInteger reinitializeDependentCompleted =
          _batfish.newBatch(
              "Iteration " + dependentRoutesIterations + ": Reinitialize dependent routes",
              nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {

                  /*
                   * RIBs that are read from
                   */
                  vr._prevMainRib = vr._mainRib;
                  vr._mainRib = new Rib(vr);

                  vr._prevOspfExternalType1Rib = vr._ospfExternalType1Rib;
                  vr._ospfExternalType1Rib = new OspfExternalType1Rib(vr);

                  vr._prevOspfExternalType2Rib = vr._ospfExternalType2Rib;
                  vr._ospfExternalType2Rib = new OspfExternalType2Rib(vr);

                  vr._prevBgpRib = vr._bgpMultipathRib;
                  vr._bgpMultipathRib = new BgpMultipathRib(vr);

                  vr._prevBgpBestPathRib = vr._bgpBestPathRib;
                  vr._bgpBestPathRib = new BgpBestPathRib(vr);

                  vr._prevEbgpRib = vr._ebgpMultipathRib;
                  vr._ebgpMultipathRib = new BgpMultipathRib(vr);
                  vr.importRib(vr._ebgpMultipathRib, vr._baseEbgpRib);

                  vr._prevEbgpBestPathRib = vr._ebgpBestPathRib;
                  vr._ebgpBestPathRib = new BgpBestPathRib(vr);
                  vr.importRib(vr._ebgpBestPathRib, vr._baseEbgpRib);

                  vr._prevIbgpBestPathRib = vr._ibgpBestPathRib;
                  vr._ibgpBestPathRib = new BgpBestPathRib(vr);
                  vr.importRib(vr._ibgpBestPathRib, vr._baseIbgpRib);

                  vr._prevIbgpRib = vr._ibgpMultipathRib;
                  vr._ibgpMultipathRib = new BgpMultipathRib(vr);
                  vr.importRib(vr._ibgpMultipathRib, vr._baseIbgpRib);

                  /*
                   * RIBs not read from
                   */
                  vr._ospfRib = new OspfRib(vr);

                  /*
                   * Staging RIBs
                   */
                  vr._ebgpStagingRib = new BgpMultipathRib(vr);
                  vr._ibgpStagingRib = new BgpMultipathRib(vr);
                  vr._ospfExternalType1StagingRib = new OspfExternalType1Rib(vr);
                  vr._ospfExternalType2StagingRib = new OspfExternalType2Rib(vr);

                  /*
                   * Add routes that cannot change (does not affect below
                   * computation)
                   */
                  vr.importRib(vr._mainRib, vr._independentRib);

                  /*
                   * Re-add independent OSPF routes to ospfRib for tie-breaking
                   */
                  vr.importRib(vr._ospfRib, vr._ospfIntraAreaRib);
                  vr.importRib(vr._ospfRib, vr._ospfInterAreaRib);
                }
                reinitializeDependentCompleted.incrementAndGet();
              });

      // Static nextHopIp routes
      AtomicInteger recomputeStaticCompleted =
          _batfish.newBatch(
              "Iteration "
                  + dependentRoutesIterations
                  + ": Recompute static routes with next-hop IP",
              nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                boolean staticChanged = true;
                while (staticChanged) {
                  for (VirtualRouter vr : n._virtualRouters.values()) {
                    staticChanged = false;
                    if (vr.activateStaticRoutes()) {
                      staticChanged = true;
                    }
                  }
                }
                recomputeStaticCompleted.incrementAndGet();
              });

      // Generated/aggregate routes
      AtomicInteger recomputeAggregateCompleted =
          _batfish.newBatch(
              "Iteration " + dependentRoutesIterations + ": Recompute aggregate/generated routes",
              nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  boolean generatedChanged = true;
                  vr._generatedRib = new Rib(vr);
                  while (generatedChanged) {
                    generatedChanged = false;
                    if (vr.activateGeneratedRoutes()) {
                      generatedChanged = true;
                    }
                  }
                  vr.importRib(vr._mainRib, vr._generatedRib);
                }
                recomputeAggregateCompleted.incrementAndGet();
              });

      // OSPF external routes
      // recompute exports
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  vr.initOspfExports();
                }
              });

      // repropagate exports
      final boolean[] ospfExternalChanged = new boolean[] {true};
      int ospfExternalSubIterations = 0;
      while (ospfExternalChanged[0]) {
        ospfExternalSubIterations++;
        AtomicInteger propagateOspfExternalCompleted =
            _batfish.newBatch(
                "Iteration "
                    + dependentRoutesIterations
                    + ": Propagate OSPF external routes: subIteration: "
                    + ospfExternalSubIterations,
                nodes.size());
        ospfExternalChanged[0] = false;
        nodes
            .values()
            .parallelStream()
            .forEach(
                n -> {
                  for (VirtualRouter vr : n._virtualRouters.values()) {
                    if (vr.propagateOspfExternalRoutes(nodes, topology)) {
                      synchronized (routesChangedMonitor) {
                        ospfExternalChanged[0] = true;
                      }
                    }
                  }
                  propagateOspfExternalCompleted.incrementAndGet();
                });
        AtomicInteger unstageOspfExternalCompleted =
            _batfish.newBatch(
                "Iteration "
                    + dependentRoutesIterations
                    + ": Unstage OSPF external routes: subIteration: "
                    + ospfExternalSubIterations,
                nodes.size());
        nodes
            .values()
            .parallelStream()
            .forEach(
                n -> {
                  for (VirtualRouter vr : n._virtualRouters.values()) {
                    vr.unstageOspfExternalRoutes();
                  }
                  unstageOspfExternalCompleted.incrementAndGet();
                });
      }
      AtomicInteger importOspfExternalCompleted =
          _batfish.newBatch(
              "Iteration " + dependentRoutesIterations + ": Unstage OSPF external routes",
              nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  vr.importRib(vr._ospfRib, vr._ospfExternalType1Rib);
                  vr.importRib(vr._ospfRib, vr._ospfExternalType2Rib);
                  vr.importRib(vr._mainRib, vr._ospfRib);
                }
                importOspfExternalCompleted.incrementAndGet();
              });

      // BGP routes
      // first let's initialize nodes-level generated/aggregate routes
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  if (vr._vrf.getBgpProcess() != null) {
                    vr.initBgpAggregateRoutes();
                  }
                }
              });
      AtomicInteger propagateBgpCompleted =
          _batfish.newBatch(
              "Iteration " + dependentRoutesIterations + ": Propagate BGP routes", nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  vr.propagateBgpRoutes(nodes, dp.getIpOwners());
                }
                propagateBgpCompleted.incrementAndGet();
              });
      AtomicInteger importBgpCompleted =
          _batfish.newBatch(
              "Iteration " + dependentRoutesIterations + ": Import BGP routes into respective RIBs",
              nodes.size());
      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  vr.unstageBgpRoutes();
                  BgpProcess proc = vr._vrf.getBgpProcess();
                  if (proc != null && proc.getMultipathEbgp()) {
                    vr.importRib(vr._bgpMultipathRib, vr._ebgpMultipathRib);
                  } else {
                    vr.importRib(vr._bgpMultipathRib, vr._ebgpBestPathRib);
                  }
                  if (proc != null && proc.getMultipathIbgp()) {
                    vr.importRib(vr._bgpMultipathRib, vr._ibgpMultipathRib);
                  } else {
                    vr.importRib(vr._bgpMultipathRib, vr._ibgpBestPathRib);
                  }
                  vr.importRib(vr._bgpBestPathRib, vr._ebgpBestPathRib);
                  vr.importRib(vr._bgpBestPathRib, vr._ibgpBestPathRib);
                  vr.importRib(vr._mainRib, vr._bgpMultipathRib);
                }
                importBgpCompleted.incrementAndGet();
              });

      /** Collect sizes of certain RIBs this iteration */
      int numBgpBestPathRibRoutes =
          nodes
              .values()
              .stream()
              .flatMap(n -> n._virtualRouters.values().stream())
              .mapToInt(vr -> vr._bgpBestPathRib.getRoutes().size())
              .sum();
      ae.getBgpBestPathRibRoutesByIteration()
          .put(dependentRoutesIterations, numBgpBestPathRibRoutes);
      int numBgpMultipathRibRoutes =
          nodes
              .values()
              .stream()
              .flatMap(n -> n._virtualRouters.values().stream())
              .mapToInt(vr -> vr._bgpMultipathRib.getRoutes().size())
              .sum();
      ae.getBgpMultipathRibRoutesByIteration()
          .put(dependentRoutesIterations, numBgpMultipathRibRoutes);
      int numMainRibRoutes =
          nodes
              .values()
              .stream()
              .flatMap(n -> n._virtualRouters.values().stream())
              .mapToInt(vr -> vr._mainRib.getRoutes().size())
              .sum();
      ae.getMainRibRoutesByIteration().put(dependentRoutesIterations, numMainRibRoutes);

      if (DEBUG_REPEAT_ITERATIONS) {
        Map<Ip, String> ipOwners = dp.getIpOwnersSimple();
        RouteSet routes = computeOutputRoutes(nodes, ipOwners);
        iterationRoutes.put(dependentRoutesIterations, routes);
      }

      // Check to see if routes have changed
      AtomicInteger checkFixedPointCompleted =
          _batfish.newBatch(
              "Iteration " + dependentRoutesIterations + ": Check if fixed-point reached",
              nodes.size());
      int iterationHashCode = computeIterationHashCode(nodes);
      Integer iterationWithThisHashCode = iterationByHashCode.get(iterationHashCode);
      iterationHashCodes.put(dependentRoutesIterations, iterationHashCode);
      if (iterationWithThisHashCode == null) {
        iterationByHashCode.put(iterationHashCode, dependentRoutesIterations);
      } else if (dependentRoutesIterations - iterationWithThisHashCode > 1) {
        String msg =
            "Iteration "
                + dependentRoutesIterations
                + " has same hash as iteration: "
                + iterationWithThisHashCode
                + "\n"
                + iterationHashCodes;
        if (!DEBUG_REPEAT_ITERATIONS) {
          throw new BatfishException(msg);
        } else if (!DEBUG_ALL_ITERATIONS) {
          String errorMessage =
              debugIterations(
                  msg, iterationRoutes, iterationWithThisHashCode, dependentRoutesIterations);
          throw new BatfishException(errorMessage);
        } else {
          String errorMessage = debugIterations(msg, iterationRoutes, 1, dependentRoutesIterations);
          throw new BatfishException(errorMessage);
        }
      }

      nodes
          .values()
          .parallelStream()
          .forEach(
              n -> {
                for (VirtualRouter vr : n._virtualRouters.values()) {
                  boolean changed = false;
                  if (!vr._mainRib.getRoutes().equals(vr._prevMainRib.getRoutes())) {
                    changed = true;
                  }
                  if (!vr._ospfExternalType1Rib
                      .getRoutes()
                      .equals(vr._prevOspfExternalType1Rib.getRoutes())) {
                    changed = true;
                  }
                  if (!vr._ospfExternalType2Rib
                      .getRoutes()
                      .equals(vr._prevOspfExternalType2Rib.getRoutes())) {
                    changed = true;
                  }
                  if (changed) {
                    synchronized (routesChangedMonitor) {
                      dependentRoutesChanged[0] = true;
                    }
                  }
                }
                checkFixedPointCompleted.incrementAndGet();
              });
    }
    ae.setOspfInternalIterations(ospfInternalIterations);
    ae.setDependentRoutesIterations(dependentRoutesIterations);
  }

  private int computeIterationHashCode(Map<String, Node> nodes) {
    int mainHash =
        nodes
            .values()
            .parallelStream()
            .mapToInt(
                n ->
                    n._virtualRouters
                        .values()
                        .stream()
                        .mapToInt(vr -> vr._mainRib.getRoutes().hashCode())
                        .sum())
            .sum();
    int ospfExternalType1Hash =
        nodes
            .values()
            .parallelStream()
            .mapToInt(
                n ->
                    n._virtualRouters
                        .values()
                        .stream()
                        .mapToInt(vr -> vr._ospfExternalType1Rib.getRoutes().hashCode())
                        .sum())
            .sum();
    int ospfExternalType2Hash =
        nodes
            .values()
            .parallelStream()
            .mapToInt(
                n ->
                    n._virtualRouters
                        .values()
                        .stream()
                        .mapToInt(vr -> vr._ospfExternalType2Rib.getRoutes().hashCode())
                        .sum())
            .sum();
    int hash = mainHash + ospfExternalType1Hash + ospfExternalType2Hash;
    return hash;
  }

  private RouteSet computeOutputRoutes(Map<String, Node> nodes, Map<Ip, String> ipOwners) {
    RouteSet outputRoutes = new RouteSet();
    nodes.forEach(
        (hostname, node) -> {
          node._virtualRouters.forEach(
              (vrName, vr) -> {
                for (AbstractRoute route : vr._mainRib.getRoutes()) {
                  RouteBuilder rb = new RouteBuilder();
                  rb.setNode(hostname);
                  rb.setNetwork(route.getNetwork());
                  Ip nextHopIp = route.getNextHopIp();
                  if (route.getProtocol() == RoutingProtocol.CONNECTED
                      || (route.getProtocol() == RoutingProtocol.STATIC
                          && nextHopIp.equals(Route.UNSET_ROUTE_NEXT_HOP_IP))
                      || Interface.NULL_INTERFACE_NAME.equals(route.getNextHopInterface())) {
                    rb.setNextHop(Configuration.NODE_NONE_NAME);
                  }
                  if (!nextHopIp.equals(Route.UNSET_ROUTE_NEXT_HOP_IP)) {
                    rb.setNextHopIp(nextHopIp);
                    String nextHop = ipOwners.get(nextHopIp);
                    if (nextHop != null) {
                      rb.setNextHop(nextHop);
                    }
                  }
                  rb.setNextHopInterface(route.getNextHopInterface());
                  rb.setAdministrativeCost(route.getAdministrativeCost());
                  rb.setCost(route.getMetric());
                  rb.setProtocol(route.getProtocol());
                  rb.setTag(route.getTag());
                  rb.setVrf(vrName);
                  Route outputRoute = rb.build();
                  outputRoutes.add(outputRoute);
                }
              });
        });
    return outputRoutes;
  }

  private String debugIterations(
      String msg, Map<Integer, RouteSet> iterationRoutes, int first, int last) {
    StringBuilder sb = new StringBuilder();
    sb.append(msg);
    sb.append("\n");
    RouteSet initialRoutes = iterationRoutes.get(first);
    sb.append("Initial routes (iteration " + first + "):\n");
    for (Route route : initialRoutes) {
      String routeStr = route.prettyPrint(null);
      sb.append(routeStr);
    }
    for (int i = first + 1; i <= last; i++) {
      RouteSet baseRoutes = iterationRoutes.get(i - 1);
      RouteSet deltaRoutes = iterationRoutes.get(i);
      RouteSet added = CommonUtil.difference(deltaRoutes, baseRoutes, RouteSet::new);
      RouteSet removed = CommonUtil.difference(baseRoutes, deltaRoutes, RouteSet::new);
      RouteSet changed = CommonUtil.union(added, removed, RouteSet::new);
      sb.append("Changed routes (iteration " + (i - 1) + " ==> " + i + "):\n");
      for (Route route : changed) {
        String diffSymbol = added.contains(route) ? "+" : "-";
        String routeStr = route.prettyPrint(diffSymbol);
        sb.append(routeStr);
      }
    }
    String errorMessage = sb.toString();
    return errorMessage;
  }

  private boolean flowTraceDeniedHelper(
      Set<FlowTrace> flowTraces,
      Flow originalFlow,
      Flow transformedFlow,
      List<FlowTraceHop> newHops,
      IpAccessList filter,
      FlowDisposition disposition) {
    boolean out =
        disposition == FlowDisposition.DENIED_OUT
            || disposition == FlowDisposition.NEIGHBOR_UNREACHABLE_OR_DENIED_OUT;
    FilterResult outResult = filter.filter(transformedFlow);
    boolean denied = outResult.getAction() == LineAction.REJECT;
    if (denied) {
      String outFilterName = filter.getName();
      Integer matchLine = outResult.getMatchLine();
      String lineDesc;
      if (matchLine != null) {
        lineDesc = filter.getLines().get(matchLine).getName();
        if (lineDesc == null) {
          lineDesc = "line:" + matchLine;
        }
      } else {
        lineDesc = "no-match";
      }
      String notes = disposition + "{" + outFilterName + "}{" + lineDesc + "}";
      if (out) {
        FlowTraceHop lastHop = newHops.get(newHops.size() - 1);
        newHops.remove(newHops.size() - 1);
        Edge lastEdge = lastHop.getEdge();
        Edge deniedOutEdge =
            new Edge(
                lastEdge.getFirst(),
                new NodeInterfacePair(Configuration.NODE_NONE_NAME, Interface.NULL_INTERFACE_NAME));
        FlowTraceHop deniedOutHop =
            new FlowTraceHop(
                deniedOutEdge, lastHop.getRoutes(), hopFlow(originalFlow, transformedFlow));
        newHops.add(deniedOutHop);
      }
      FlowTrace trace = new FlowTrace(disposition, newHops, notes);
      flowTraces.add(trace);
    }
    return denied;
  }

  @Override
  public AdvertisementSet getAdvertisements() {
    AdvertisementSet adverts = new AdvertisementSet();
    BdpDataPlane dp = loadDataPlane();
    for (Node node : dp._nodes.values()) {
      for (VirtualRouter vrf : node._virtualRouters.values()) {
        adverts.addAll(vrf._receivedBgpAdvertisements);
        adverts.addAll(vrf._sentBgpAdvertisements);
      }
    }
    return adverts;
  }

  @Override
  public List<Flow> getHistoryFlows() {
    BdpDataPlane dp = loadDataPlane();
    List<Flow> flowList = new ArrayList<>();
    _flowTraces
        .get(dp)
        .forEach(
            (flow, flowTraces) -> {
              for (int i = 0; i < flowTraces.size(); i++) {
                flowList.add(flow);
              }
            });
    return flowList;
  }

  @Override
  public List<FlowTrace> getHistoryFlowTraces() {
    BdpDataPlane dp = loadDataPlane();
    List<FlowTrace> flowTraceList = new ArrayList<>();
    _flowTraces
        .get(dp)
        .forEach(
            (flow, flowTraces) -> {
              for (FlowTrace flowTrace : flowTraces) {
                flowTraceList.add(flowTrace);
              }
            });
    return flowTraceList;
  }

  @Override
  public IbgpTopology getIbgpNeighbors() {
    throw new UnsupportedOperationException("no implementation for generated method");
    // TODO Auto-generated method stub
  }

  @Override
  public SortedMap<String, SortedMap<String, SortedSet<AbstractRoute>>> getRoutes() {
    BdpDataPlane dp = loadDataPlane();
    SortedMap<String, SortedMap<String, SortedSet<AbstractRoute>>> routesByHostname =
        new TreeMap<>();
    for (Entry<String, Node> e : dp._nodes.entrySet()) {
      String hostname = e.getKey();
      Node node = e.getValue();
      for (Entry<String, VirtualRouter> e2 : node._virtualRouters.entrySet()) {
        String vrfName = e2.getKey();
        VirtualRouter vrf = e2.getValue();
        for (AbstractRoute route : vrf._mainRib.getRoutes()) {
          SortedMap<String, SortedSet<AbstractRoute>> routesByVrf =
              routesByHostname.computeIfAbsent(hostname, k -> new TreeMap<>());
          SortedSet<AbstractRoute> routes =
              routesByVrf.computeIfAbsent(vrfName, k -> new TreeSet<>());
          routes.add(route);
        }
      }
    }
    return routesByHostname;
  }

  @Nullable
  private Flow hopFlow(Flow originalFlow, Flow transformedFlow) {
    if (originalFlow == transformedFlow) {
      return null;
    } else {
      return transformedFlow;
    }
  }

  private BdpDataPlane loadDataPlane() {
    return (BdpDataPlane) _batfish.loadDataPlane();
  }

  private FlowTrace neighborUnreachableTrace(
      List<FlowTraceHop> completedHops,
      NodeInterfacePair srcInterface,
      SortedSet<String> routes,
      Flow originalFlow,
      Flow transformedFlow) {
    Edge neighborUnreachbleEdge =
        new Edge(
            srcInterface,
            new NodeInterfacePair(Configuration.NODE_NONE_NAME, Interface.NULL_INTERFACE_NAME));
    FlowTraceHop neighborUnreachableHop =
        new FlowTraceHop(neighborUnreachbleEdge, routes, hopFlow(originalFlow, transformedFlow));
    List<FlowTraceHop> newHops = new ArrayList<>(completedHops);
    newHops.add(neighborUnreachableHop);
    FlowTrace trace =
        new FlowTrace(
            FlowDisposition.NEIGHBOR_UNREACHABLE_OR_EXITS_NETWORK,
            newHops,
            FlowDisposition.NEIGHBOR_UNREACHABLE_OR_EXITS_NETWORK.toString());
    return trace;
  }

  private boolean processCurrentNextHopInterfaceEdges(
      BdpDataPlane dp,
      String currentNodeName,
      Set<Edge> visitedEdges,
      List<FlowTraceHop> hopsSoFar,
      Set<FlowTrace> flowTraces,
      Flow originalFlow,
      Flow transformedFlow,
      Ip dstIp,
      Set<String> dstIpOwners,
      String nextHopInterfaceName,
      SortedSet<String> routesForThisNextHopInterface,
      Ip finalNextHopIp,
      NodeInterfacePair nextHopInterface,
      EdgeSet edges,
      boolean arp) {
    boolean continueToNextNextHopInterface = false;
    int unreachableNeighbors = 0;
    int potentialNeighbors = 0;
    for (Edge edge : edges) {
      if (!edge.getNode1().equals(currentNodeName)) {
        continue;
      }
      potentialNeighbors++;
      List<FlowTraceHop> newHops = new ArrayList<>(hopsSoFar);
      Set<Edge> newVisitedEdges = new LinkedHashSet<>(visitedEdges);
      FlowTraceHop newHop =
          new FlowTraceHop(
              edge, routesForThisNextHopInterface, hopFlow(originalFlow, transformedFlow));
      newVisitedEdges.add(edge);
      newHops.add(newHop);
      /*
       * Check to see whether neighbor would refrain from sending ARP reply
       * (NEIGHBOR_UNREACHABLE)
       *
       * This occurs if:
       *
       * - Using interface-only route
       *
       * AND
       *
       * - Neighbor does not own arpIp
       *
       * AND EITHER
       *
       * -- Neighbor not using proxy-arp
       *
       * - OR
       *
       * -- Subnet of neighbor's receiving-interface contains arpIp
       */
      if (arp) {
        Ip arpIp;
        Set<String> arpIpOwners;
        if (finalNextHopIp == null) {
          arpIp = dstIp;
          arpIpOwners = dstIpOwners;
        } else {
          arpIp = finalNextHopIp;
          arpIpOwners = dp._ipOwners.get(arpIp);
        }
        // using interface-only route
        String node2 = edge.getNode2();
        if (arpIpOwners == null || !arpIpOwners.contains(node2)) {
          // neighbor does not own arpIp
          String int2Name = edge.getInt2();
          Interface int2 = dp._nodes.get(node2)._c.getInterfaces().get(int2Name);
          boolean neighborUnreachable = false;
          Boolean proxyArp = int2.getProxyArp();
          if (proxyArp == null || !proxyArp) {
            // TODO: proxyArp probably shouldn't be null
            neighborUnreachable = true;
          } else {
            for (Prefix prefix : int2.getAllPrefixes()) {
              if (prefix.getNetworkPrefix().contains(arpIp)) {
                neighborUnreachable = true;
                break;
              }
            }
          }
          if (neighborUnreachable) {
            unreachableNeighbors++;
            continue;
          }
        }
      }
      if (visitedEdges.contains(edge)) {
        FlowTrace trace =
            new FlowTrace(FlowDisposition.LOOP, newHops, FlowDisposition.LOOP.toString());
        flowTraces.add(trace);
        potentialNeighbors--;
        continue;
      }
      String nextNodeName = edge.getNode2();
      // now check output filter and input filter
      if (nextHopInterfaceName != null) {
        IpAccessList outFilter =
            dp._nodes
                .get(currentNodeName)
                ._c
                .getInterfaces()
                .get(nextHopInterfaceName)
                .getOutgoingFilter();
        if (outFilter != null) {
          FlowDisposition disposition = FlowDisposition.DENIED_OUT;
          boolean denied =
              flowTraceDeniedHelper(
                  flowTraces, originalFlow, transformedFlow, newHops, outFilter, disposition);
          if (denied) {
            potentialNeighbors--;
            continue;
          }
        }
      }
      IpAccessList inFilter =
          dp._nodes.get(nextNodeName)._c.getInterfaces().get(edge.getInt2()).getIncomingFilter();
      if (inFilter != null) {
        FlowDisposition disposition = FlowDisposition.DENIED_IN;
        boolean denied =
            flowTraceDeniedHelper(
                flowTraces, originalFlow, transformedFlow, newHops, inFilter, disposition);
        if (denied) {
          potentialNeighbors--;
          continue;
        }
      }
      // recurse
      collectFlowTraces(
          dp, nextNodeName, newVisitedEdges, newHops, flowTraces, originalFlow, transformedFlow);
    }
    if (arp) {
      if (unreachableNeighbors > 0 && unreachableNeighbors == potentialNeighbors) {
        FlowTrace trace =
            neighborUnreachableTrace(
                hopsSoFar,
                nextHopInterface,
                routesForThisNextHopInterface,
                originalFlow,
                transformedFlow);
        flowTraces.add(trace);
        continueToNextNextHopInterface = true;
      }
    }
    return continueToNextNextHopInterface;
  }

  @Override
  public void processFlows(Set<Flow> flows) {
    BdpDataPlane dp = loadDataPlane();
    Map<Flow, Set<FlowTrace>> flowTraces = new ConcurrentHashMap<>();
    flows
        .parallelStream()
        .forEach(
            flow -> {
              Set<FlowTrace> currentFlowTraces = new TreeSet<>();
              flowTraces.put(flow, currentFlowTraces);
              String ingressNodeName = flow.getIngressNode();
              if (ingressNodeName == null) {
                throw new BatfishException(
                    "Cannot construct flow trace since ingressNode is not specified");
              }
              Ip dstIp = flow.getDstIp();
              if (dstIp == null) {
                throw new BatfishException(
                    "Cannot construct flow trace since dstIp is not specified");
              }
              Set<Edge> visitedEdges = Collections.emptySet();
              List<FlowTraceHop> hops = new ArrayList<>();
              Set<String> dstIpOwners = dp._ipOwners.get(dstIp);
              EdgeSet edges = new EdgeSet();
              String ingressInterfaceName = flow.getIngressInterface();
              if (ingressInterfaceName != null) {
                edges.add(
                    new Edge(
                        TRACEROUTE_INGRESS_NODE_NAME,
                        TRACEROUTE_INGRESS_NODE_INTERFACE_NAME,
                        ingressNodeName,
                        ingressInterfaceName));
                processCurrentNextHopInterfaceEdges(
                    dp,
                    TRACEROUTE_INGRESS_NODE_NAME,
                    visitedEdges,
                    hops,
                    currentFlowTraces,
                    flow,
                    flow,
                    dstIp,
                    dstIpOwners,
                    null,
                    new TreeSet<>(),
                    null,
                    null,
                    edges,
                    false);
              } else {
                collectFlowTraces(
                    dp, ingressNodeName, visitedEdges, hops, currentFlowTraces, flow, flow);
              }
            });
    _flowTraces.put(dp, new TreeMap<>(flowTraces));
  }
}
