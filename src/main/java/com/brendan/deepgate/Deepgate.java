package com.brendan.deepgate;

import com.brendan.deepgate.command.DeepgateCommands;
import com.brendan.deepgate.core.CombatTracker;
import com.brendan.deepgate.core.TeleportService;
import com.brendan.deepgate.dialog.DialogService;
import com.brendan.deepgate.request.RequestService;
import com.brendan.deepgate.ui.TpaUi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

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

	/** Build a Deepgate-namespaced identifier. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		DeepgateRules.register();
		TpaUi.registerHandlers(DIALOGS);

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

		// Everything transient dies with the connection (section 48).
		ServerPlayerEvents.LEAVE.register(player -> {
			COMBAT.clear(player.getUUID());
			TELEPORTS.clearBack(player.getUUID());
			REQUESTS.onPlayerGone(player.getUUID());
			DIALOGS.clear(player.getUUID());
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
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TELEPORTS.tick(server);

			for (var expired : REQUESTS.tick(server)) {
				// An expired request charges nothing, because nothing was ever taken.
				notify(server, expired.senderId(), "Your teleport request expired.");
				notify(server, expired.targetId(), "A teleport request expired.");
			}
		});

		LOGGER.info("Deepgate ready");
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
