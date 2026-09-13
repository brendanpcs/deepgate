package com.brendan.deepgate.command;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.core.TeleportService;
import com.brendan.deepgate.request.TpaRequest;
import com.brendan.deepgate.ui.Feedback;
import com.brendan.deepgate.ui.TpaUi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Deepgate commands (spec section 2).
 *
 * <p>Every one of these is available to an ordinary player: none carries a permission requirement.
 * There is deliberately no {@code /sethome}, no {@code /delhome} and no portal-management command -
 * homes are created by walking into a beacon beam, and gates are managed by interacting with them.
 */
public final class DeepgateCommands {
	private DeepgateCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tpa")
				.executes(ctx -> openSelector(ctx.getSource(), TpaRequest.Kind.TPA))
				.then(Commands.argument("player", EntityArgument.player())
						// Sends straight away rather than opening a screen: no Deepgate action may
						// require a client to complete. The price lands in chat instead, and a fare that
						// rises before travel still needs explicit approval.
						.executes(ctx -> sendDirect(ctx.getSource(),
								EntityArgument.getPlayer(ctx, "player"), TpaRequest.Kind.TPA))));

		dispatcher.register(Commands.literal("tpahere")
				.executes(ctx -> openSelector(ctx.getSource(), TpaRequest.Kind.TPA_HERE))
				.then(Commands.argument("player", EntityArgument.player())
						// /tpahere <player> asks that player to come, so it goes straight to a request
						// rather than a confirmation screen: the person who pays is the one who approves.
						.executes(ctx -> sendDirect(ctx.getSource(),
								EntityArgument.getPlayer(ctx, "player"), TpaRequest.Kind.TPA_HERE))));

		// Accepting and denying from chat is far quicker than opening the inbox, and it is what
		// players coming from other teleport mods reach for. The optional player argument disambiguates
		// when several requests are waiting.
		dispatcher.register(Commands.literal("tpaccept")
				.executes(ctx -> accept(ctx.getSource(), null))
				.then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> accept(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));

		dispatcher.register(Commands.literal("tpadeny")
				.executes(ctx -> deny(ctx.getSource(), null))
				.then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> deny(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));

		dispatcher.register(Commands.literal("tpacancel")
				.executes(ctx -> cancel(ctx.getSource())));

		dispatcher.register(Commands.literal("tparequests")
				.executes(ctx -> openInbox(ctx.getSource())));

		dispatcher.register(Commands.literal("back")
				.executes(ctx -> back(ctx.getSource())));
	}

	private static int openSelector(CommandSourceStack source, TpaRequest.Kind kind) throws CommandSyntaxException {
		TpaUi.openPlayerSelector(source.getPlayerOrException(), kind);
		return 1;
	}

	private static int sendDirect(CommandSourceStack source, ServerPlayer target, TpaRequest.Kind kind)
			throws CommandSyntaxException {
		TpaUi.send(source.getPlayerOrException(), target, kind);
		return 1;
	}

	private static int accept(CommandSourceStack source, ServerPlayer namedSender)
			throws CommandSyntaxException {
		TpaUi.acceptCommand(source.getPlayerOrException(), namedSender);
		return 1;
	}

	private static int deny(CommandSourceStack source, ServerPlayer namedSender)
			throws CommandSyntaxException {
		TpaUi.denyCommand(source.getPlayerOrException(), namedSender);
		return 1;
	}

	private static int cancel(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();

		if (Deepgate.requests().cancel(player.getUUID())) {
			player.sendSystemMessage(Component.literal("Request cancelled."));
			return 1;
		}

		player.sendSystemMessage(Component.literal("You have no outgoing request."));
		return 0;
	}

	private static int openInbox(CommandSourceStack source) throws CommandSyntaxException {
		TpaUi.openInbox(source.getPlayerOrException());
		return 1;
	}

	/** {@code /back} has no dialog: it is an immediate attempt, per section 2. */
	private static int back(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		RuleSnapshot rules = DeepgateRules.snapshot(player.level().getServer());

		TeleportService.Result result = Deepgate.teleports().back(player, rules);

		if (result instanceof TeleportService.Result.Failed failed) {
			Feedback.refused(player, failed.failure());
			return 0;
		}

		// Success carries the refund as a negative charge.
		Feedback.wentBack(player, -((TeleportService.Result.Success) result).pointsCharged());
		return 1;
	}
}
