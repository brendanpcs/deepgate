package com.brendan.deepgate;

import com.brendan.deepgate.command.DeepgateCommands;
import com.brendan.deepgate.core.CombatTracker;
import com.brendan.deepgate.core.TeleportService;
import com.brendan.deepgate.dialog.DialogService;
import com.brendan.deepgate.home.BeaconScan;
import com.brendan.deepgate.home.HomeService;
import com.brendan.deepgate.request.RequestService;
import com.brendan.deepgate.spawn.SpawnService;
import com.brendan.deepgate.state.DeepgateState;
import com.brendan.deepgate.ui.HomeUi;
import com.brendan.deepgate.ui.SpawnUi;
import com.brendan.deepgate.ui.TpaUi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

/**
 * Deepgate entrypoint: registers gamerules, wires services to Fabric events, and owns the
 * server-lifetime state.
 *
 * <p>Deepgate is server-side only. A vanilla client needs no mod and no resource pack, because every
 * interface is a native dialog, vanilla text, vanilla items or vanilla particles.
 */
public final class Deepgate implements ModInitializer {
	public static final String MOD_ID = "deepgate";
	public static final Logger LOGGER = LoggerFactory.getLogger("Deepgate");

	private static final CombatTracker COMBAT = new CombatTracker();
	private static final TeleportService TELEPORTS = new TeleportService(COMBAT);
	private static final RequestService REQUESTS = new RequestService(TELEPORTS);
	private static final DialogService DIALOGS = new DialogService();
	private static final HomeService HOMES = new HomeService();

	/** How often to check whether players have walked into a beacon beam. */
	private static final int BEAM_CHECK_INTERVAL_TICKS = 10;

	public static CombatTracker combat() {
		return COMBAT;
	}

	public static TeleportService teleports() {
		return TELEPORTS;
	}

	public static RequestService requests() {
		return REQUESTS;
	}

	public static DialogService dialogs() {
		return DIALOGS;
	}

	public static HomeService homes() {
		return HOMES;
	}

	/** Build a Deepgate-namespaced identifier. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		DeepgateRules.register();
		TpaUi.registerHandlers(DIALOGS);
		SpawnUi.registerHandlers(DIALOGS);
		HomeUi.registerHandlers(DIALOGS);

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> DeepgateCommands.register(dispatcher));

		// Combat tagging (section 7). Fabric gives the damage that actually landed, which is exactly
		// the "damage must succeed" condition: a fully absorbed hit never tags.
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			MinecraftServer server = serverOf(entity);

			if (server != null) {
				// Read just the one rule: this fires on every damage event in the world.
				COMBAT.onDamage(server, entity, source, damageTaken,
						server.getGameRules().get(DeepgateRules.COMBAT_SECONDS));
			}
		});

		// Breaking a beacon takes every home bound to it, for every player (sections 14 and 18).
		// Every other kind of damage - a shrunken pyramid, a blocked beam - leaves the record alone.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel serverLevel)) {
				return;
			}

			if (blockEntity instanceof BeaconBlockEntity) {
				int removed = DeepgateState.get(serverLevel.getServer())
						.removeHomesAt(serverLevel.dimension(), pos);

				if (removed > 0) {
					LOGGER.info("Removed {} Deepgate home(s) bound to the beacon broken at {}", removed, pos);
				}
			}

			// Breaking a bed or anchor clears the personal spawn of anyone online who was bound to it
			// (section 11), so /spawn falls back to world spawn rather than reporting a block that is
			// no longer there. Offline players are caught when they next use /spawn.
			if (SpawnService.isSpawnBlock(state)) {
				SpawnService.onSpawnBlockBroken(serverLevel.getServer(), serverLevel, pos);
			}
		});

		// Everything transient dies with the connection (section 48).
		ServerPlayerEvents.LEAVE.register(player -> {
			COMBAT.clear(player.getUUID());
			TELEPORTS.clearBack(player.getUUID());
			REQUESTS.onPlayerGone(player.getUUID());
			DIALOGS.clear(player.getUUID());
			HOMES.leaveBeam(player.getUUID());
		});

		// Death clears the undo record: "back" would otherwise mean back to where you died.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) {
				TELEPORTS.clearBack(newPlayer.getUUID());
			}
		});

		// Nothing transient survives a restart, so start from a clean slate either way.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			COMBAT.clearAll();
			TELEPORTS.clearAll();
			REQUESTS.clearAll();
			DIALOGS.clearAll();
			HOMES.clearAll();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TELEPORTS.tick(server);

			// Beam detection runs on a slow cadence over the player list. The scan itself walks down
			// from each player and stops at the first opaque block, so it never touches the world at
			// large (section 53).
			if (server.getTickCount() % BEAM_CHECK_INTERVAL_TICKS == 0) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					checkBeam(server, player);
				}
			}

			for (var expired : REQUESTS.tick(server)) {
				// An expired request charges nothing, because nothing was ever taken.
				notify(server, expired.senderId(), "Your teleport request expired.");
				notify(server, expired.targetId(), "A teleport request expired.");
			}
		});

		LOGGER.info("Deepgate ready");
	}

	/**
	 * Open the right screen when a player walks into a beacon beam (section 15).
	 *
	 * <p>Edge triggered: standing in the beam does nothing after the first moment, and leaving it is
	 * what rearms the prompt.
	 */
	private static void checkBeam(MinecraftServer server, ServerPlayer player) {
		var found = BeaconScan.beaconBelow(player);

		if (found.isEmpty()) {
			HOMES.leaveBeam(player.getUUID());
			return;
		}

		var rules = DeepgateRules.snapshot(server);

		if (!found.get().qualifies(rules.homeBeaconLayers())) {
			// Too small to anchor a home; treat it as not being in a beam at all.
			HOMES.leaveBeam(player.getUUID());
			return;
		}

		if (!HOMES.enterBeam(player.getUUID(), found.get().pos())) {
			return;
		}

		DeepgateState state = DeepgateState.get(server);

		if (state.homeAt(player.getUUID(), player.level().dimension(), found.get().pos()).isPresent()) {
			// Already one of their homes, so this is a destination picker rather than a naming screen.
			// No chat copy: this was triggered by walking, not by asking.
			HomeUi.openList(player, false);
			return;
		}

		var blocked = HOMES.cannotCreate(player, rules);

		if (blocked.isPresent()) {
			// Shown as a screen, the same as the other two beam outcomes, so walking into a beam always
			// tells you where you stand. Chat carries it too, so it is readable without a client.
			player.sendSystemMessage(Component.literal(blocked.get()));
			DIALOGS.open(player, com.brendan.deepgate.dialog.Dialogs.notice(blocked.get(),
					"Delete a home from /home to make room for a new one."));
			return;
		}

		HomeUi.openSetHome(player, found.get().pos());
	}

	private static MinecraftServer serverOf(LivingEntity entity) {
		return entity.level().getServer();
	}

	private static void notify(MinecraftServer server, java.util.UUID playerId, String message) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);

		if (player != null) {
			player.sendSystemMessage(Component.literal(message));
		}
	}
}
