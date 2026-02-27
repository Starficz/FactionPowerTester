package org.starficz.factionpowertester

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflaterParams
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.impl.campaign.ids.Ranks
import com.fs.starfarer.api.impl.campaign.ids.ShipRoles
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.util.WeightedRandomPicker
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max


class FactionTesterRefereePlugin : BaseEveryFrameCombatPlugin() {

    private lateinit var engine: CombatEngineAPI

    private val factionA = "hegemony"
    private val factionB = "tritachyon"

    // Balance Search Variables
    private val baselineDpA = 200f
    private var targetDpB = 200f

    // Search Hyperparameters
    private val initialLearningRate = 0.15f  // Start making 15% of the DP diff as adjustments
    private val minimumLearningRate = 0.02f // Settle down to only 2% adjustments late-game to ignore RNG

    private var roundCounter = 1
    private val maxRounds = 100 // Enough rounds to anneal perfectly

    private var currentState = State.INIT_DUMMIES
    private var stateTimer = 0f

    // DP Tracking Variables
    private var initialDpA = 0f
    private var initialDpB = 0f
    private var hasLoggedInitialDP = false

    private val logBuilder = java.lang.StringBuilder()

    enum class State {
        INIT_DUMMIES, GENERATING, FIGHTING, SCORING, CLEANUP, FINISHED
    }

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        logBuilder.append("=== FACTION POWERLEVEL HEURISTIC SEARCH ===\n")
        logBuilder.append("Baseline: $factionA ($baselineDpA DP) vs Variable: $factionB\n\n")
    }

    override fun advance(amount: Float, events: List<InputEventAPI>) {
        if (engine.isPaused) return

        engine.ships.filter { it.customData["IS_DUMMY"] == true }.forEach {
            it.location.set(20000f, 20000f)
            it.velocity.set(0f, 0f)
            it.facing = 0f
        }

        stateTimer -= amount

        when (currentState) {
            State.INIT_DUMMIES -> {
                engine.ships.forEach { hideDummy(it) }
                currentState = State.GENERATING
            }

            State.GENERATING -> {
                hasLoggedInitialDP = false
                spawnFaction(FleetSide.PLAYER, factionA, Vector2f(0f, -4000f), baselineDpA)
                spawnFaction(FleetSide.ENEMY, factionB, Vector2f(0f, 4000f), targetDpB)

                stateTimer = 2f
                currentState = State.FIGHTING
            }

            State.FIGHTING -> {
                if (stateTimer > 0f) return

                val shipsA = getValidCombatants(0)
                val shipsB = getValidCombatants(1)

                if (!hasLoggedInitialDP) {
                    initialDpA = calculateDP(shipsA)
                    initialDpB = calculateDP(shipsB)
                    hasLoggedInitialDP = true
                }

                if (shipsA.isEmpty() || shipsB.isEmpty()) {
                    stateTimer = 3f
                    currentState = State.SCORING
                }
            }

            State.SCORING -> {
                if (stateTimer > 0f) return

                val shipsA = getValidCombatants(0)
                val shipsB = getValidCombatants(1)

                val dpA = calculateDP(shipsA)
                val dpB = calculateDP(shipsB)

                val winner = if (dpA > dpB) factionA else if (dpB > dpA) factionB else "DRAW"
                val remainingDP = max(dpA, dpB)

                // --- HEURISTIC LOSS FUNCTION WITH ANNEALING ---
                // netAdvantage is Positive if A won, Negative if B won
                val netAdvantageA = dpA - dpB

                // Calculate decaying learning rate (Progress goes 0.0 -> 1.0)
                val progress = roundCounter.toFloat() / maxRounds.toFloat()
                val currentLearningRate = max(minimumLearningRate, initialLearningRate - (initialLearningRate * progress))

                // Adjustment magnitude
                val adjustment = netAdvantageA * currentLearningRate

                // Apply adjustment:
                // If A won (netAdvantage > 0), B needs more DP (targetDpB increases)
                // If B won (netAdvantage < 0), B needs less DP (targetDpB decreases)
                targetDpB = max(10f, targetDpB + adjustment)

                val resultLine = String.format(
                    "R%03d | %s (%.0f DP) vs %s (%.0f DP) | Win: %-10s | Rem: %03.0f | LR: %.2f | Next Target: %.1f",
                    roundCounter, factionA, initialDpA, factionB, initialDpB, winner, remainingDP, currentLearningRate, targetDpB
                )

                logBuilder.append(resultLine).append("\n")

                Global.getSettings().writeTextFileToCommon("faction_tester_results", logBuilder.toString())
                engine.addFloatingText(Vector2f(0f, 0f), resultLine, 40f, java.awt.Color.YELLOW, null, 0f, 0f)

                stateTimer = 2f
                currentState = State.CLEANUP
            }

            State.CLEANUP -> {
                if (stateTimer > 0f) return

                engine.ships.filter { it.customData["IS_DUMMY"] != true }.forEach { engine.removeEntity(it) }
                engine.projectiles.forEach { engine.removeEntity(it) }
                engine.asteroids.forEach { engine.removeEntity(it) }

                roundCounter++
                if (roundCounter > maxRounds) {
                    currentState = State.FINISHED
                    engine.endCombat(2f, FleetSide.PLAYER)
                } else {
                    currentState = State.GENERATING
                }
            }
            State.FINISHED -> {}
        }
    }

    private fun spawnFaction(side: FleetSide, factionId: String, centerLoc: Vector2f, targetDP: Float) {
        val campaignFleet = generateDPFleet(factionId, targetDP)
        val manager = engine.getFleetManager(side)

        val members = campaignFleet.fleetData.membersListCopy
        val totalShips = members.size

        val maxPerRow = 5
        val spacingX = 800f
        val spacingY = 1000f

        val facing = if (side == FleetSide.PLAYER) 90f else 270f
        val yDir = if (side == FleetSide.PLAYER) -1f else 1f

        for ((i, member) in members.withIndex()) {
            val row = i / maxPerRow
            val col = i % maxPerRow

            val shipsInRow = kotlin.math.min(maxPerRow, totalShips - (row * maxPerRow))
            val startX = -((shipsInRow - 1) * spacingX) / 2f

            val spawnX = centerLoc.x + startX + (col * spacingX)
            val spawnY = centerLoc.y + (row * spacingY * yDir)
            val spawnLoc = Vector2f(spawnX, spawnY)

            val variantId = member.variant.hullVariantId
            val captain = if (member.captain != null && !member.captain.isDefault) member.captain else null

            val spawnedShip = manager.spawnShipOrWing(
                variantId,
                spawnLoc,
                facing,
                0f,
                captain
            )

            if (spawnedShip != null && spawnedShip.fleetMember != null) {
                spawnedShip.fleetMember.shipName = member.shipName
            }
        }
    }

    private fun generateDPFleet(factionId: String, targetDP: Float): CampaignFleetAPI {
        val faction = Global.getSector().getFaction(factionId)
        val doctrine = faction.doctrine
        val random = java.util.Random()

        val fleet = Global.getFactory().createEmptyFleet(factionId, "Test Fleet", true)
        val market = Global.getFactory().createMarket("fake_${factionId}", "fake", 5)
        market.factionId = factionId

        val totalWeights = (doctrine.warships + doctrine.carriers + doctrine.phaseShips).coerceAtLeast(1)
        val warshipDP = targetDP * (doctrine.warships.toFloat() / totalWeights)
        val carrierDP = targetDP * (doctrine.carriers.toFloat() / totalWeights)
        val phaseDP = targetDP * (doctrine.phaseShips.toFloat() / totalWeights)

        var currentTotalDP = 0f

        fun getRolesForCategory(isWarship: Boolean, isCarrier: Boolean, isPhase: Boolean): WeightedRandomPicker<String> {
            val picker = WeightedRandomPicker<String>(random)
            val size = doctrine.shipSize

            val sWeight = if (size <= 2) 40f else 20f
            val mWeight = if (size == 3) 40f else 30f
            val lWeight = if (size >= 4) 40f else 20f
            val cWeight = if (size == 5) 40f else if (size == 4) 20f else 5f

            if (isWarship) {
                picker.add(ShipRoles.COMBAT_SMALL, sWeight)
                picker.add(ShipRoles.COMBAT_MEDIUM, mWeight)
                picker.add(ShipRoles.COMBAT_LARGE, lWeight)
                picker.add(ShipRoles.COMBAT_CAPITAL, cWeight)
            } else if (isCarrier) {
                picker.add(ShipRoles.CARRIER_SMALL, sWeight)
                picker.add(ShipRoles.CARRIER_MEDIUM, mWeight)
                picker.add(ShipRoles.CARRIER_LARGE, lWeight + cWeight)
            } else if (isPhase) {
                picker.add(ShipRoles.PHASE_SMALL, sWeight)
                picker.add(ShipRoles.PHASE_MEDIUM, mWeight)
                picker.add(ShipRoles.PHASE_LARGE, lWeight)
                picker.add(ShipRoles.PHASE_CAPITAL, cWeight)
            }
            return picker
        }

        fun fillCategory(picker: WeightedRandomPicker<String>, allocatedDP: Float) {
            var catDP = 0f
            var fails = 0

            while (catDP < allocatedDP && fails < 15 && currentTotalDP < targetDP) {
                val role = picker.pick() ?: break

                val pickParams = FactionAPI.ShipPickParams(ShipPickMode.PRIORITY_THEN_ALL, 100, 0L, false)
                val picks = market.pickShipsForRole(role, factionId, pickParams, random, null)

                if (picks.isNullOrEmpty()) {
                    fails++
                    continue
                }

                val member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, picks[0].variantId)
                val dp = member.deploymentPointsCost

                if (currentTotalDP + dp <= targetDP + 10f) {
                    member.shipName = fleet.fleetData.pickShipName(member, random)
                    fleet.fleetData.addFleetMember(member)
                    catDP += dp
                    currentTotalDP += dp
                    fails = 0
                } else {
                    fails++
                }
            }
        }

        fillCategory(getRolesForCategory(true, false, false), warshipDP)
        fillCategory(getRolesForCategory(false, true, false), carrierDP)
        fillCategory(getRolesForCategory(false, false, true), phaseDP)

        if (currentTotalDP < targetDP - 5f) {
            fillCategory(getRolesForCategory(true, false, false), targetDP - currentTotalDP)
        }

        val fpParams = FleetParamsV3(null, factionId, 1f, FleetTypes.PATROL_LARGE, targetDP, 0f, 0f, 0f, 0f, 0f, 0f)
        fpParams.withOfficers = true
        addStandardizedOfficers(fleet, fpParams, random)

        val inflaterParams = DefaultFleetInflaterParams()
        inflaterParams.quality = 1f
        inflaterParams.factionId = factionId
        inflaterParams.seed = random.nextLong()
        inflaterParams.mode = ShipPickMode.PRIORITY_THEN_ALL
        val inflater = Misc.getInflater(fleet, inflaterParams)

        if (inflater != null) {
            fleet.inflater = inflater
            inflater.inflate(fleet)
        } else {
            fleet.fleetData.syncIfNeeded()
        }

        fleet.fleetData.sort()
        return fleet
    }

    private fun addStandardizedOfficers(fleet: CampaignFleetAPI, params: FleetParamsV3, random: java.util.Random) {
        val members = fleet.fleetData.membersListCopy
        if (members.isEmpty()) return

        var combatPoints = 0f
        var combatShips = 0f
        for (member in members) {
            if (member.isCivilian) continue
            if (member.isFighterWing) continue
            combatPoints += member.fleetPointCost.toFloat()
            combatShips += 1f
        }
        if (combatPoints < 1f) combatPoints = 1f
        if (combatShips < 1f) combatShips = 1f

        val maxCommanderLevel = Global.getSettings().getInt("maxAIFleetCommanderLevel")
        val mercMult = Global.getSettings().getFloat("officerAIMaxMercsMult")
        var maxOfficers = Global.getSettings().getInt("officerAIMax")
        val baseMaxOfficerLevel = Global.getSettings().getInt("officerMaxLevel")
        val plugin = Global.getSettings().getPlugin("officerLevelUp") as com.fs.starfarer.api.plugins.OfficerLevelupPlugin

        // --- THE FIX: Hardcode Officer Quality to 3 (Average) ---
        // Bypasses (doctrine.getOfficerQuality() - 1f) / 4f
        val fixedOfficerQuality = 3f
        var officerQualityMult = (fixedOfficerQuality - 1f) / 4f
        if (officerQualityMult > 1f) officerQualityMult = 1f

        val baseShipsForMaxOfficerLevel = Global.getSettings().getFloat("baseCombatShipsForMaxOfficerLevel")
        val baseCombatShipsPerOfficer = Global.getSettings().getFloat("baseCombatShipsPerOfficer")
        val combatShipsPerOfficer = baseCombatShipsPerOfficer * (1f - officerQualityMult * 0.5f)

        var fleetSizeOfficerQualityMult = combatShips / (baseShipsForMaxOfficerLevel * (1f - officerQualityMult * 0.5f))
        if (fleetSizeOfficerQualityMult > 1f) fleetSizeOfficerQualityMult = 1f

        maxOfficers += (fixedOfficerQuality * mercMult).toInt() + params.officerNumberBonus

        var numOfficers = kotlin.math.min(maxOfficers, (combatShips / combatShipsPerOfficer).toInt())
        numOfficers += params.officerNumberBonus
        numOfficers = kotlin.math.round(numOfficers * params.officerNumberMult).toInt()

        if (numOfficers > maxOfficers) numOfficers = maxOfficers

        var maxOfficerLevel = kotlin.math.round((fixedOfficerQuality / 2f) + (fleetSizeOfficerQualityMult * 1f) * baseMaxOfficerLevel.toFloat()).toInt()
        if (maxOfficerLevel < 1) maxOfficerLevel = 1
        maxOfficerLevel += params.officerLevelBonus
        if (maxOfficerLevel < 1) maxOfficerLevel = 1

        val picker = WeightedRandomPicker<com.fs.starfarer.api.fleet.FleetMemberAPI>(random)
        val flagshipPicker = WeightedRandomPicker<com.fs.starfarer.api.fleet.FleetMemberAPI>(random)

        var maxSize = 0
        for (member in members) {
            if (member.isFighterWing) continue
            if (member.isFlagship) continue
            if (member.isCivilian) continue
            if (!member.captain.isDefault) continue
            val size = member.hullSpec.hullSize.ordinal
            if (size > maxSize) {
                maxSize = size
            }
        }

        for (member in members) {
            if (member.isFighterWing) continue
            if (member.isFlagship) continue
            if (member.isCivilian) continue
            if (!member.captain.isDefault) continue

            val weight = member.fleetPointCost.toFloat()
            val size = member.hullSpec.hullSize.ordinal
            if (size >= maxSize) {
                flagshipPicker.add(member, weight)
            }
            picker.add(member, weight)
        }

        if (picker.isEmpty) picker.add(members[0], 1f)
        if (flagshipPicker.isEmpty) flagshipPicker.add(members[0], 1f)

        val flagship = flagshipPicker.pickAndRemove()
        picker.remove(flagship)

        var commanderLevel = maxOfficerLevel
        var commanderLevelLimit = maxCommanderLevel
        if (params.commanderLevelLimit != 0) {
            commanderLevelLimit = params.commanderLevelLimit
        }
        if (commanderLevel > commanderLevelLimit) commanderLevel = commanderLevelLimit

        var pref = FleetFactoryV3.getSkillPrefForShip(flagship)
        var commander = params.commander
        if (commander == null) {
            commander = com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent.createOfficer(
                fleet.faction, commanderLevel, pref, false, null, true, true, -1, random
            )
            if (commander.personalityAPI.id == Personalities.TIMID) {
                commander.setPersonality(Personalities.CAUTIOUS)
            }
            FleetFactoryV3.addCommanderSkills(commander, fleet, params, random)
        }
        if (params.commander == null) {
            commander.rankId = Ranks.SPACE_COMMANDER
            commander.postId = Ranks.POST_FLEET_COMMANDER
        }
        fleet.commander = commander
        fleet.fleetData.setFlagship(flagship)

        val commanderOfficerLevelBonus = commander.stats.dynamic.getMod(Stats.OFFICER_MAX_LEVEL_MOD).computeEffective(0f).toInt()
        var officerLevelLimit = plugin.getMaxLevel(null) + commanderOfficerLevelBonus
        if (params.officerLevelLimit != 0) {
            officerLevelLimit = params.officerLevelLimit
        }

        for (i in 0 until numOfficers) {
            val member = picker.pickAndRemove() ?: break

            var level = maxOfficerLevel - random.nextInt(3)
            if (Misc.isEasy()) {
                level = kotlin.math.ceil(level.toFloat() * Global.getSettings().getFloat("easyOfficerLevelMult")).toInt()
            }
            if (level < 1) level = 1
            if (level > officerLevelLimit) level = officerLevelLimit

            pref = FleetFactoryV3.getSkillPrefForShip(member)
            val person = com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent.createOfficer(
                fleet.faction, level, pref, false, fleet, true, true, -1, random
            )

            // Standardize personalities away from useless TIMID AI
            if (person.personalityAPI.id == Personalities.TIMID) {
                person.setPersonality(Personalities.CAUTIOUS)
            }

            member.captain = person
        }
    }

    private fun getValidCombatants(owner: Int): List<ShipAPI> {
        return engine.ships.filter {
            it.owner == owner &&
                    it.isAlive &&
                    !it.isFighter &&
                    !it.isDrone &&
                    !it.isStationModule &&
                    it.customData["IS_DUMMY"] != true
        }
    }

    private fun calculateDP(ships: List<ShipAPI>): Float {
        var dp = 0f
        for (ship in ships) {
            dp += ship.fleetMember?.deploymentPointsCost ?: 0f
        }
        return dp
    }

    private fun hideDummy(ship: ShipAPI) {
        ship.setCustomData("IS_DUMMY", true)
        ship.collisionClass = CollisionClass.NONE
        ship.alphaMult = 0f
        ship.isPhased = true
        ship.shipAI = null
        ship.mutableStats.hullDamageTakenMult.modifyMult("dummy_invuln", 0f)
        ship.mutableStats.empDamageTakenMult.modifyMult("dummy_invuln", 0f)
        ship.mutableStats.peakCRDuration.modifyFlat("dummy_cr", 999999f)
        ship.mutableStats.crLossPerSecondPercent.modifyMult("dummy_cr", 0f)
    }
}