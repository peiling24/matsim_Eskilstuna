package org.matsim.project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.bicycle.AdditionalBicycleLinkScore;
import org.matsim.contrib.bicycle.AdditionalBicycleLinkScoreDefaultImpl;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculator;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculatorDefaultImpl;
import org.matsim.contrib.bicycle.BicycleTravelDisutilityFactory;
import org.matsim.contrib.bicycle.BicycleTravelTime;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.vehicles.VehicleType;

import com.google.inject.Inject;
import com.google.inject.Singleton;


/**
 * Bicycle module variant that keeps all default bicycle behavior but filters
 * additional bicycle link scoring to bicycle mode vehicles only.
 */
public final class BicycleModeFilteredModule extends AbstractModule {
	private static final Logger LOG = LogManager.getLogger(BicycleModeFilteredModule.class);

	@Inject
	private BicycleConfigGroup bicycleConfigGroup;

	@Override
	public void install() {
		addTravelTimeBinding(bicycleConfigGroup.getBicycleMode()).to(BicycleTravelTime.class).in(Singleton.class);
		addTravelDisutilityFactoryBinding(bicycleConfigGroup.getBicycleMode()).to(BicycleTravelDisutilityFactory.class)
			.in(Singleton.class);

		this.addEventHandlerBinding().to(BicycleModeFilteredScoreEventsCreator.class);

		this.bind(AdditionalBicycleLinkScore.class).to(AdditionalBicycleLinkScoreDefaultImpl.class);

		this.installOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				this.addLinkSpeedCalculatorBinding().to(BicycleLinkSpeedCalculator.class);
			}
		});

		bind(BicycleLinkSpeedCalculator.class).to(BicycleLinkSpeedCalculatorDefaultImpl.class);

		addControlerListenerBinding().to(ConsistencyCheck.class);

	}

	static class ConsistencyCheck implements StartupListener {
		@Inject
		private BicycleConfigGroup bicycleConfigGroup;
		@Inject
		private Scenario scenario;

		@Override
		public void notifyStartup(StartupEvent event) {
			Id<VehicleType> bicycleVehTypeId = Id.create(bicycleConfigGroup.getBicycleMode(), VehicleType.class);
			if (scenario.getVehicles().getVehicleTypes().get(bicycleVehTypeId) == null) {
				LOG.warn("There is no vehicle type '" + bicycleConfigGroup.getBicycleMode() + "' specified in the vehicle types. "
					+ "Can't check the consistency of the maximum velocity in the bicycle vehicle type and the bicycle config group. "
					+ "Should at least be approximately the same and randomization should be enabled.");
			} else {
				double mobsimSpeed = scenario.getVehicles().getVehicleTypes().get(bicycleVehTypeId).getMaximumVelocity();
				if (Math.abs(mobsimSpeed - bicycleConfigGroup.getMaxBicycleSpeedForRouting()) > 0.1) {
					LOG.warn("There is an inconsistency in the specified maximum velocity for " + bicycleConfigGroup.getBicycleMode() + ":"
						+ " Maximum speed specified in the 'bicycle' config group (used for routing): "
						+ bicycleConfigGroup.getMaxBicycleSpeedForRouting() + " vs."
						+ " maximum speed specified for the vehicle type (used in mobsim): " + mobsimSpeed);
					if (scenario.getConfig().routing().getRoutingRandomness() == 0.) {
						throw new RuntimeException(
							"The recommended way to deal with the inconsistency between routing and scoring/mobsim is to have a randomized router. Aborting... ");
					}
				}
			}
			if (!scenario.getConfig().qsim().getMainModes().contains(bicycleConfigGroup.getBicycleMode())) {
				LOG.warn(bicycleConfigGroup.getBicycleMode() + " not specified as main mode.");
			}
		}
	}
}
