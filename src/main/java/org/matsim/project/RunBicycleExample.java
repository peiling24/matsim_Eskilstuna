/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
//package org.matsim.contrib.bicycle.run;
package org.matsim.project;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.bicycle.AdditionalBicycleLinkScore;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehiclesFactory;

import com.google.inject.Inject;


/**
 * @author plw
 */
public class RunBicycleExample {
	private static final Logger LOG = LogManager.getLogger(RunBicycleExample.class);

	public static void main(String[] args) {
		Config config;
		if (args.length == 1) {
			LOG.info("A user-specified config.xml file was provided. Using it...");
			config = ConfigUtils.loadConfig(args[0], new BicycleConfigGroup());
			// Config file should contain all settings - just validate bicycle mode
			BicycleConfigGroup bicycleConfigGroup = ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);
			LOG.info("Bicycle config loaded from file:");
			LOG.info("  - bicycleMode: " + bicycleConfigGroup.getBicycleMode());
			LOG.info("  - maxBicycleSpeedForRouting: " + bicycleConfigGroup.getMaxBicycleSpeedForRouting());
			LOG.info("  - userDefinedNetworkAttributeDefaultValue: " + bicycleConfigGroup.getUserDefinedNetworkAttributeDefaultValue());
		} else if (args.length == 0) {
			// DEFAULT: Use the mode choice config file 
			LOG.info("No arguments provided. Using default config: Eskilstuna/config_bicycle_modechoice_v2.xml");
			config = ConfigUtils.loadConfig("Eskilstuna/config_bicycle_modechoice_v2.xml", new BicycleConfigGroup());
			// Config file should contain all settings - just validate bicycle mode
			BicycleConfigGroup bicycleConfigGroup = ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);
			LOG.info("Bicycle config loaded from file:");
			LOG.info("  - bicycleMode: " + bicycleConfigGroup.getBicycleMode());
			LOG.info("  - maxBicycleSpeedForRouting: " + bicycleConfigGroup.getMaxBicycleSpeedForRouting());
			LOG.info("  - userDefinedNetworkAttributeDefaultValue: " + bicycleConfigGroup.getUserDefinedNetworkAttributeDefaultValue());
		} else {
			throw new RuntimeException("More than one argument was provided. There is no procedure for this situation. Thus aborting!"
								     + " Provide either (1) only a suitable config file or (2) no argument at all to run example with given example of resources folder.");
		}
		
		// Log key config values for verification
		LOG.info("Key config values:");
		LOG.info("  - lastIteration: " + config.controller().getLastIteration());
		LOG.info("  - coordinateSystem: " + config.global().getCoordinateSystem());
		LOG.info("  - qsim.mainModes: " + config.qsim().getMainModes());
		LOG.info("  - global.numberOfThreads: " + config.global().getNumberOfThreads());
		LOG.info("  - qsim.numberOfThreads: " + config.qsim().getNumberOfThreads());
		
		new RunBicycleExample().run(config);
	}

	static void fillConfigWithBicycleStandardValues(Config config) {
		// config.controller().setWriteEventsInterval(1);

		config.controller().setWriteEventsInterval(-1);
		config.controller().setWritePlansInterval(-1);

		BicycleConfigGroup bicycleConfigGroup = ConfigUtils.addOrGetModule( config, BicycleConfigGroup.class );
		bicycleConfigGroup.setMarginalUtilityOfInfrastructure_m(-0.0002);
		bicycleConfigGroup.setMarginalUtilityOfComfort_m(-0.0002);
		bicycleConfigGroup.setMarginalUtilityOfGradient_m_100m(-0.02);
		bicycleConfigGroup.setMarginalUtilityOfUserDefinedNetworkAttribute_m(-1.0000); // always needs to be negative
		bicycleConfigGroup.setUserDefinedNetworkAttributeName("accessibility"); // needs to be defined as a value from 0 to 1, 1 being best, 0 being worst
		bicycleConfigGroup.setUserDefinedNetworkAttributeDefaultValue(0.5); // used for those links that do not have a value for the user-defined attribute

		bicycleConfigGroup.setMaxBicycleSpeedForRouting(5.6667); // 20 km/h in m/s

		List<String> mainModeList = new ArrayList<>();
		mainModeList.add( bicycleConfigGroup.getBicycleMode() );
		mainModeList.add(TransportMode.car);

		config.qsim().setMainModes(mainModeList);

		config.replanning().setMaxAgentPlanMemorySize(5);
		config.replanning().addStrategySettings( new StrategySettings().setStrategyName("ChangeExpBeta" ).setWeight(0.8 ) );
		config.replanning().addStrategySettings( new StrategySettings().setStrategyName("ReRoute" ).setWeight(0.2 ) );

		config.scoring().addActivityParams( new ActivityParams("home").setTypicalDuration(12*60*60 ) );
		config.scoring().addActivityParams( new ActivityParams("work").setTypicalDuration(8*60*60 ) );
		config.scoring().addActivityParams( new ActivityParams("shop").setTypicalDuration(1*60*60 ) );
		config.scoring().addActivityParams( new ActivityParams("other").setTypicalDuration(3*60*60 ) );

		config.scoring().addModeParams( new ModeParams("bicycle").setConstant(0. ).setMarginalUtilityOfDistance(-0.0004 ).setMarginalUtilityOfTraveling(-6.0 ).setMonetaryDistanceRate(0. ) );


// Note: All teleported modes (transit, pt, walk) are configured in the config file
	}

	public void run(Config config ) {
		// Log thread configuration before any modifications
		LOG.info("=== THREAD CONFIGURATION ===");
		LOG.info("  global.numberOfThreads from config: " + config.global().getNumberOfThreads());
		LOG.info("  qsim.numberOfThreads from config: " + config.qsim().getNumberOfThreads());
		LOG.info("  Available processors: " + Runtime.getRuntime().availableProcessors());
		
		// Only override numberOfThreads if not set in config (default is usually 0 or 1)
		if (config.global().getNumberOfThreads() <= 0) {
			int availableCores = Runtime.getRuntime().availableProcessors();
			int threadsToUse = Math.min(availableCores, 8); // QSim max is ~8
			config.global().setNumberOfThreads(threadsToUse);
			LOG.info("  Auto-setting global.numberOfThreads to: " + threadsToUse);
		}
		
		// Ensure QSim also has threads configured
		if (config.qsim().getNumberOfThreads() <= 0) {
			int qsimThreads = Math.min(config.global().getNumberOfThreads(), 8);
			config.qsim().setNumberOfThreads(qsimThreads);
			LOG.info("  Auto-setting qsim.numberOfThreads to: " + qsimThreads);
		}
		
		LOG.info("  FINAL global.numberOfThreads: " + config.global().getNumberOfThreads());
		LOG.info("  FINAL qsim.numberOfThreads: " + config.qsim().getNumberOfThreads());
		LOG.info("============================");
		
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		// ============================================================
		// IMPORTANT: Control early iteration output writing
		// By default, MATSim writes plans for iterations 0 and 1, and events for iteration 0
		// regardless of the writeEventsInterval and writePlansInterval settings.
		// Set these to -1 to follow the interval settings strictly.
		// ============================================================
		config.controller().setWritePlansUntilIteration(-1);  // Don't write plans for early iterations
		config.controller().setWriteEventsUntilIteration(-1); // Don't write s for early iterations

		// Only set routing randomness if not already configured
		if (config.routing().getRoutingRandomness() <= 0) {
			config.routing().setRoutingRandomness(3.);
		}

		BicycleConfigGroup bicycleConfigGroup = ConfigUtils.addOrGetModule( config, BicycleConfigGroup.class );

		final String bicycle = bicycleConfigGroup.getBicycleMode();
		
		// Explicitly add bicycle to network modes for routing
		java.util.Set<String> networkModes = new java.util.HashSet<>(config.routing().getNetworkModes());
		networkModes.add(bicycle);
		networkModes.add(TransportMode.car);
		config.routing().setNetworkModes(networkModes);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		// set config such that the mode vehicles come from vehicles data:
		scenario.getConfig().qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);

		// now put the mode vehicles into the vehicles data:
		final VehiclesFactory vf = VehicleUtils.getFactory();
		scenario.getVehicles().addVehicleType( vf.createVehicleType(Id.create(TransportMode.car, VehicleType.class ) ) );
		scenario.getVehicles().addVehicleType( vf.createVehicleType(Id.create( bicycle, VehicleType.class ) )
							 .setNetworkMode( bicycle ).setMaximumVelocity(4.16666666 ).setPcuEquivalents(0.25 ) );
		
		// Note: walk is teleported, so no vehicle type needed
		
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new BicycleModeFilteredModule() );

		controler.run();
	}



	public void runWithOwnScoring(Config config, boolean considerMotorizedInteraction) {
		config.global().setNumberOfThreads(1);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		config.routing().setRoutingRandomness(3.);

		if (considerMotorizedInteraction) {
			BicycleConfigGroup bicycleConfigGroup = ConfigUtils.addOrGetModule( config, BicycleConfigGroup.class );
			bicycleConfigGroup.setMotorizedInteraction(considerMotorizedInteraction);
		}

		Scenario scenario = ScenarioUtils.loadScenario(config);

		// set config such that the mode vehicles come from vehicles data:
		scenario.getConfig().qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);

		// now put hte mode vehicles into the vehicles data:
		final VehiclesFactory vf = VehicleUtils.getFactory();
		scenario.getVehicles().addVehicleType( vf.createVehicleType(Id.create(TransportMode.car, VehicleType.class ) ) );
		scenario.getVehicles().addVehicleType( vf.createVehicleType(Id.create("bicycle", VehicleType.class ) ).setMaximumVelocity(4.16666666 ).setPcuEquivalents(0.25 ) );

		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new BicycleModeFilteredModule() );
		controler.addOverridingModule( new AbstractModule(){
			@Override public void install(){
				this.bind( AdditionalBicycleLinkScore.class ).to( MyAdditionalBicycleLinkScore.class );
			}
		} );

		controler.run();
	}



	private static class MyAdditionalBicycleLinkScore implements AdditionalBicycleLinkScore {

		private final AdditionalBicycleLinkScore delegate;
		@Inject MyAdditionalBicycleLinkScore( Scenario scenario ) {
			this.delegate = BicycleUtils.createDefaultBicycleLinkScore( scenario );
		}
		
		
		@Override public double computeLinkBasedScore( Link link ){
			double result = (double) link.getAttributes().getAttribute( "carFreeStatus" );  // from zero to one

			double amount = delegate.computeLinkBasedScore( link );

			return amount + result ;  // or some other way to augment the score
		
 
		}
	}


}
