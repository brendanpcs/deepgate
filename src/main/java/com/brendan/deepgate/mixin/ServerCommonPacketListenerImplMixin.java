package com.brendan.deepgate.mixin;

import com.brendan.deepgate.Deepgate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Delivers dialog button clicks to Deepgate.
 *
 * <p>This is the one place Deepgate has to reach into vanilla for the dialog system: Fabric API
 * exposes no event for custom click actions, so there is no hook to prefer over a mixin here.
 *
 * <p>Purely observational. Nothing is cancelled and no vanilla behaviour changes, so other mods and
 * datapacks keep receiving the same packet; Deepgate simply ignores ids that are not its own.
 *
 * <p><strong>Why TAIL and not HEAD.</strong> Vanilla's method body is:
 *
 * <pre>
 * PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
 * this.server.handleCustomClickAction(packet.id(), packet.payload());
 * </pre>
 *
 * <p>{@code ensureRunningOnSameThread} reschedules the packet onto the server thread and then
 * <em>throws</em> on the network thread, so the method is entered twice for a single click. An
 * injection at HEAD therefore fires twice: the first pass consumes the nonce and performs the
 * action, and the second is rejected as a stale screen - one click, one teleport, and a confusing
 * "that screen is out of date" chased after it.
 *
 * <p>TAIL is only reachable on the pass that did not throw, which is the server-thread pass. That
 * makes the delivery exactly-once, and means the handler is already on the main thread with no need
 * to schedule it.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
	@Inject(method = "handleCustomClickAction", at = @At("TAIL"))
	private void deepgate$onCustomClickAction(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
		// Custom click actions only mean anything once a player is in the world.
		if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) {
			return;
		}

		ServerPlayer player = listener.player;

		if (player == null || player.hasDisconnected()) {
			return;
		}

		Deepgate.dialogs().dispatch(player, packet.id(), packet.payload());
	}
}
