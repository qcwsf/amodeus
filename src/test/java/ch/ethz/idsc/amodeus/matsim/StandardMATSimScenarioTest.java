/* amodeus - Copyright (c) 2018, ETH Zurich, Institute for Dynamic Systems and Control */
package ch.ethz.idsc.amodeus.matsim;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpTravelTimeModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import ch.ethz.idsc.amodeus.matsim.mod.AmodeusDispatcherModule;
import ch.ethz.idsc.amodeus.matsim.mod.AmodeusModule;
import ch.ethz.idsc.amodeus.prep.MatsimKMEANSVirtualNetworkCreator;
import ch.ethz.idsc.amodeus.virtualnetwork.VirtualNetwork;
import ch.ethz.matsim.av.config.AVConfig;
import ch.ethz.matsim.av.config.AVDispatcherConfig;
import ch.ethz.matsim.av.config.AVGeneratorConfig;
import ch.ethz.matsim.av.config.AVOperatorConfig;
import ch.ethz.matsim.av.framework.AVConfigGroup;
import ch.ethz.matsim.av.framework.AVModule;
import ch.ethz.matsim.av.scenario.TestScenarioAnalyzer;
import ch.ethz.matsim.av.scenario.TestScenarioGenerator;

@RunWith(Parameterized.class)
public class StandardMATSimScenarioTest {
    @Parameters
    public static Collection<Object[]> data() {
        // SingleHeuristic is added as a reference case, to check that the av package is
        // working properly

        // ATTENTION: DriveByDispatcher is not tested, because of long runtime.

        return Arrays.asList(
                new Object[][] { { "SingleHeuristic" }, { "DemandSupplyBalancingDispatcher" }, { "GlobalBipartiteMatchingDispatcher" }, { "AdaptiveRealTimeRebalancingPolicy" } });
    }

    final private String dispatcher;

    public StandardMATSimScenarioTest(String dispatcher) {
        this.dispatcher = dispatcher;
    }

    private void makeMultimodal(Scenario scenario) {
        // Add pt-links to the network to test a multimodal network as it appears in standard MATSim use cases

        Network network = scenario.getNetwork();
        NetworkFactory factory = network.getFactory();

        // Let's build a fast track through the scenario
        for (int i = 0; i < 9; i++) {
            Id<Link> ptFowardLinkId = Id.createLinkId(String.format("pt_fwd_%d:%d", i, i));
            Id<Link> ptBackwardLinkId = Id.createLinkId(String.format("pt_bck_%d:%d", i, i));
            Id<Node> fromNodeId = Id.createNodeId(String.format("%d:%d", i, i));
            Id<Node> toNodeId = Id.createNodeId(String.format("%d:%d", i + 1, i + 1));

            Link ptFowardLink = factory.createLink(ptFowardLinkId, network.getNodes().get(fromNodeId), network.getNodes().get(toNodeId));
            ptFowardLink.setFreespeed(100.0 * 1000.0 / 3600.0);
            ptFowardLink.setLength(1000.0);
            ptFowardLink.setAllowedModes(Collections.singleton("pt"));
            network.addLink(ptFowardLink);

            Link ptBackwardLink = factory.createLink(ptBackwardLinkId, network.getNodes().get(toNodeId), network.getNodes().get(fromNodeId));
            ptBackwardLink.setFreespeed(100.0 * 1000.0 / 3600.0);
            ptBackwardLink.setLength(1000.0);
            ptBackwardLink.setAllowedModes(Collections.singleton("pt"));
            network.addLink(ptBackwardLink);
        }

        // Also, a routed population may have "pt interaction" activities, which take place at links that are not part of the road network. Amodeus must be able to
        // handle these cases.

        for (Person person : scenario.getPopulation().getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                Activity trickyActivity = PopulationUtils.createActivityFromCoordAndLinkId("pt interaction", new Coord(5500.0, 5500.0), Id.createLinkId("pt_fwd_5:5"));

                plan.getPlanElements().add(PopulationUtils.createLeg("walk"));
                plan.getPlanElements().add(trickyActivity);
            }
        }
    }

    @Test
    public void testStandardMATSimScenario() {
        /* This test runs a small test scenario with the different dispatchers and makes
         * sure that all 100 generated agents arrive */

        // Set up
        Config config = ConfigUtils.createConfig(new AVConfigGroup(), new DvrpConfigGroup());
        Scenario scenario = TestScenarioGenerator.generateWithAVLegs(config);

        PlanCalcScoreConfigGroup.ModeParams modeParams = config.planCalcScore().getOrCreateModeParams(AVModule.AV_MODE);
        modeParams.setMonetaryDistanceRate(0.0);
        modeParams.setMarginalUtilityOfTraveling(8.86);
        modeParams.setConstant(0.0);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new DvrpTravelTimeModule());
        controler.addOverridingModule(new AVModule());
        controler.addOverridingModule(new AmodeusModule());
        controler.addOverridingModule(new AmodeusDispatcherModule());

        // Make the scenario multimodal
        makeMultimodal(scenario);

        // Set up a virtual network for the LPFBDispatcher

        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // ---
            }

            @Provides
            @Singleton
            public VirtualNetwork<Link> provideVirtualNetwork(@Named(AVModule.AV_MODE) Network network) {
                // Since we have no virtual netowrk saved in the working directory for our test
                // sceanario, we need to provide a custom one for the LPFB dispatcher

                return MatsimKMEANSVirtualNetworkCreator.createVirtualNetwork(scenario.getPopulation(), network, 4, true);
            }
        });

        // Config

        AVConfig avConfig = new AVConfig();
        AVOperatorConfig operatorConfig = avConfig.createOperatorConfig("test");
        operatorConfig.createPriceStructureConfig();
        AVGeneratorConfig generatorConfig = operatorConfig.createGeneratorConfig("PopulationDensity");
        generatorConfig.setNumberOfVehicles(100);

        // Choose a dispatcher
        AVDispatcherConfig dispatcherConfig = operatorConfig.createDispatcherConfig(dispatcher);

        // Make sure that we do not need the SimulationObjectCompiler
        dispatcherConfig.addParam("publishPeriod", "-1");

        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                bind(AVConfig.class).toInstance(avConfig);
            }
        });

        // Set up test analyzer and run

        TestScenarioAnalyzer analyzer = new TestScenarioAnalyzer();
        controler.addOverridingModule(analyzer);

        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addEventHandlerBinding().toInstance(new LinkEnterEventHandler() {
                    @Override
                    public void handleEvent(LinkEnterEvent event) {
                        // Fail if an AV attempts to enter a pt link

                        if (event.getVehicleId().toString().startsWith("av_") && event.getLinkId().toString().startsWith("pt")) {
                            Assert.fail("AV attempted to enter PT link");
                        }
                    }
                });
            }
        });

        controler.run();
        Assert.assertEquals(0, analyzer.numberOfDepartures - analyzer.numberOfArrivals);
    }
}
