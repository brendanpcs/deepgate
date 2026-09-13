package com.brendan.deepgate.dialog;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;

/**
 * Opens native dialogs and routes the clicks that come back (spec sections 45 and 46).
 *
 * <p>Screens are built in code and sent as anonymous dialogs, so a vanilla client needs no datapack
 * entry and no mod. Clicks arrive as custom click actions, which the packet mixin hands to
 * {@link #dispatch}.
 *
 * <p>Every client action is treated as untrusted. This class checks identity and the nonce; the
 * registered handler is responsible for re-checking everything else the spec lists, because a stale
 * screen must never act on a world that has since changed.
 */
public final class DialogService {
	/** Payload key carrying the single-use token for a committing button. */
	public static final String NONCE_KEY = "deepgate_nonce";

	private final NonceTable nonces = new NonceTable();
	private final Map<Identifier, Registration> handlers = new HashMap<>();

	/** A server-side reaction to a dialog button. Always runs on the main server thread. */
	@FunctionalInterface
	public interface Handler {
		void handle(ServerPlayer player, CompoundTag payload);
	}

	private record Registration(Handler handler, boolean requiresNonce) {
	}

	public NonceTable nonces() {
		return nonces;
	}

	/**
	 * Register a handler for an action that changes state. Its nonce is consumed before it runs.
	 *
	 * <p>Ids are namespaced, so Deepgate never collides with vanilla or another mod.
	 */
	public void register(Identifier id, Handler handler) {
		put(id, new Registration(handler, true));
	}

	/**
	 * Register a handler that only moves between screens.
	 *
	 * <p>Navigation changes nothing, so it carries no nonce. Spending one would be wasteful and, on
	 * a player browsing back and forth, could age out a token belonging to a screen still open.
	 */
	public void registerNavigation(Identifier id, Handler handler) {
		put(id, new Registration(handler, false));
	}

	private void put(Identifier id, Registration registration) {
		if (handlers.put(id, registration) != null) {
			throw new IllegalStateException("Duplicate Deepgate dialog handler for " + id);
		}
	}

	/** Show a screen built in code. */
	public void open(ServerPlayer player, Dialog dialog) {
		player.openDialog(Holder.direct(dialog));
	}

	/**
	 * Handle an incoming custom click action.
	 *
	 * <p>Called on the main server thread by the packet mixin. Unknown ids are ignored rather than
	 * logged loudly: another mod, a datapack or vanilla itself may legitimately use custom actions.
	 */
	public void dispatch(ServerPlayer player, Identifier id, Optional<Tag> payload) {
		Registration registration = handlers.get(id);

		if (registration == null) {
			return;
		}

		CompoundTag tag = payload.filter(CompoundTag.class::isInstance)
				.map(CompoundTag.class::cast)
				.orElseGet(CompoundTag::new);

		if (registration.requiresNonce()) {
			long tick = player.level().getServer().getTickCount();
			String nonce = tag.getStringOr(NONCE_KEY, "");

			if (!nonces.consume(player.getUUID(), nonce, tick)) {
				// A replayed, forged or expired click. Say so plainly rather than failing silently, so
				// a player who double-clicked understands why the second press did nothing.
				player.sendSystemMessage(Component.literal("That screen is out of date. Open it again."));
				return;
			}
		}

		try {
			registration.handler().handle(player, tag);
		} catch (RuntimeException e) {
			Deepgate.LOGGER.error("Deepgate dialog handler {} failed for {}", id,
					player.getGameProfile().name(), e);
			player.sendSystemMessage(Component.literal("Something went wrong handling that action."));
		}
	}

	public void clear(UUID playerId) {
		nonces.clear(playerId);
	}

	public void clearAll() {
		nonces.clearAll();
	}
}
