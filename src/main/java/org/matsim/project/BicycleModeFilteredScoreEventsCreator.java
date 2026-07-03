package org.matsim.project;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.bicycle.AdditionalBicycleLinkScore;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.algorithms.Vehicle2DriverEventHandler;
import org.matsim.core.gbl.Gbl;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Inject;

/**
 * Copy of MATSim bicycle score event handling with one fix:
 * bicycle-specific additional link scores are emitted only for bicycle-mode vehicles.
 */
public final class BicycleModeFilteredScoreEventsCreator implements
	VehicleEntersTrafficEventHandler,
	LinkEnterEventHandler,
	LinkLeaveEventHandler,
	VehicleLeavesTrafficEventHandler {

	private static final Logger LOG = LogManager.getLogger(BicycleModeFilteredScoreEventsCreator.class);

	private final Network network;
	private final EventsManager eventsManager;
	private final AdditionalBicycleLinkScore additionalBicycleLinkScore;
	private final String bicycleMode;

	private final Vehicle2DriverEventHandler vehicle2driver = new Vehicle2DriverEventHandler();
	private final Map<Id<Vehicle>, Id<Link>> firstLinkIdMap = new LinkedHashMap<>();
	private final Map<Id<Vehicle>, String> modeFromVehicle = new LinkedHashMap<>();
	private final Map<String, Map<Id<Link>, Double>> numberOfVehiclesOnLinkByMode = new LinkedHashMap<>();
	private final BicycleConfigGroup bicycleConfig;

	@Inject
	BicycleModeFilteredScoreEventsCreator(Scenario scenario, EventsManager eventsManager,
			AdditionalBicycleLinkScore additionalBicycleLinkScore) {
		this.eventsManager = eventsManager;
		this.network = scenario.getNetwork();
		this.additionalBicycleLinkScore = additionalBicycleLinkScore;
		this.bicycleConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), BicycleConfigGroup.class);
		this.bicycleMode = bicycleConfig.getBicycleMode();
	}

	@Override
	public void reset(int iteration) {
		vehicle2driver.reset(iteration);
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		vehicle2driver.handleEvent(event);

		this.firstLinkIdMap.put(event.getVehicleId(), event.getLinkId());
		this.modeFromVehicle.put(event.getVehicleId(), event.getNetworkMode());

		if (this.bicycleConfig.isMotorizedInteraction()) {
			numberOfVehiclesOnLinkByMode.putIfAbsent(event.getNetworkMode(), new LinkedHashMap<>());
			Map<Id<Link>, Double> map = numberOfVehiclesOnLinkByMode.get(event.getNetworkMode());
			map.merge(event.getLinkId(), 1., Double::sum);
		}
	}

	@Override
	public void handleEvent(LinkEnterEvent event) {
		if (this.bicycleConfig.isMotorizedInteraction()) {
			String mode = this.modeFromVehicle.get(event.getVehicleId());
			numberOfVehiclesOnLinkByMode.putIfAbsent(mode, new LinkedHashMap<>());
			Map<Id<Link>, Double> map = numberOfVehiclesOnLinkByMode.get(mode);
			map.merge(event.getLinkId(), 1., Double::sum);
		}
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		if (this.bicycleConfig.isMotorizedInteraction()) {
			String mode = this.modeFromVehicle.get(event.getVehicleId());
			numberOfVehiclesOnLinkByMode.putIfAbsent(mode, new LinkedHashMap<>());
			Map<Id<Link>, Double> map = numberOfVehiclesOnLinkByMode.get(mode);
			Gbl.assertIf(map.merge(event.getLinkId(), -1., Double::sum) >= 0);
		}

		if (vehicle2driver.getDriverOfVehicle(event.getVehicleId()) != null && isBicycleVehicle(event.getVehicleId())) {
			double amount = additionalBicycleLinkScore.computeLinkBasedScore(network.getLinks().get(event.getLinkId()));

			if (this.bicycleConfig.isMotorizedInteraction()) {
				var carCounts = this.numberOfVehiclesOnLinkByMode.get(TransportMode.car);
				if (carCounts != null) {
					amount -= 0.004 * carCounts.getOrDefault(event.getLinkId(), 0.);
				}
			}

			final Id<Person> driverOfVehicle = vehicle2driver.getDriverOfVehicle(event.getVehicleId());
			Gbl.assertNotNull(driverOfVehicle);
			this.eventsManager.processEvent(new PersonScoreEvent(event.getTime(), driverOfVehicle, amount,
					"bicycleAdditionalLinkScore"));
		} else if (vehicle2driver.getDriverOfVehicle(event.getVehicleId()) == null) {
			LOG.warn("no driver found for vehicleId=" + event.getVehicleId() + "; not clear why this could happen");
		}
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		if (this.bicycleConfig.isMotorizedInteraction()) {
			String mode = this.modeFromVehicle.get(event.getVehicleId());
			numberOfVehiclesOnLinkByMode.putIfAbsent(mode, new LinkedHashMap<>());
			Map<Id<Link>, Double> map = numberOfVehiclesOnLinkByMode.get(mode);
			Gbl.assertIf(map.merge(event.getLinkId(), -1., Double::sum) >= 0.);
		}

		if (vehicle2driver.getDriverOfVehicle(event.getVehicleId()) != null) {
			if (!Objects.equals(this.firstLinkIdMap.get(event.getVehicleId()), event.getLinkId())
					&& isBicycleVehicle(event.getVehicleId())) {
				double amount = additionalBicycleLinkScore.computeLinkBasedScore(network.getLinks().get(event.getLinkId()));

				final Id<Person> driverOfVehicle = vehicle2driver.getDriverOfVehicle(event.getVehicleId());
				Gbl.assertNotNull(driverOfVehicle);
				this.eventsManager.processEvent(new PersonScoreEvent(event.getTime(), driverOfVehicle, amount,
						"bicycleAdditionalLinkScore"));
			}
		} else {
			LOG.warn("no driver found for vehicleId=" + event.getVehicleId() + "; not clear why this could happen");
		}

		vehicle2driver.handleEvent(event);
	}

	private boolean isBicycleVehicle(Id<Vehicle> vehicleId) {
		return Objects.equals(this.bicycleMode, this.modeFromVehicle.get(vehicleId));
	}
}