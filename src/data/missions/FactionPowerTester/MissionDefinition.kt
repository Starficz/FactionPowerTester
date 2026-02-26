package data.missions.FactionPowerTester

import com.fs.starfarer.api.fleet.FleetGoal
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.mission.MissionDefinitionAPI
import com.fs.starfarer.api.mission.MissionDefinitionPlugin
import org.starficz.factionpowertester.FactionTesterRefereePlugin

class MissionDefinition : MissionDefinitionPlugin {
    override fun defineMission(api: MissionDefinitionAPI) {
        // Standard large map, NO capture points (api.addObjective is intentionally missing)
        api.initMap(-8000f, 8000f, -8000f, 8000f)

        // Setup initial dummy fleets
        api.initFleet(FleetSide.PLAYER, "player", FleetGoal.ATTACK, false, 5)
        api.initFleet(FleetSide.ENEMY, "hegemony", FleetGoal.ATTACK, false, 5)

        // Spawn a Kite for both sides to act as hidden observers.
        // This prevents the battle from automatically ending when a fleet is wiped.
        api.addToFleet(FleetSide.PLAYER, "kite_original_Stock", FleetMemberType.SHIP, "Observer Alpha", true)
        api.addToFleet(FleetSide.ENEMY, "kite_original_Stock", FleetMemberType.SHIP, "Observer Omega", true)

        // Attach the custom automation script
        api.addPlugin(FactionTesterRefereePlugin())
    }
}