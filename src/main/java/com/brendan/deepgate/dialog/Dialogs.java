package com.brendan.deepgate.dialog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;

/**
 * Builders for the screen shapes Deepgate uses, following the conventions in spec section 45.
 *
 * <p>Root screens close, child screens go back, confirmations confirm or go back, and destructive
 * confirmations delete or go back. Escape always closes and never means back, which falls out of
 * using {@link DialogAction#CLOSE} rather than wiring Escape to anything.
 *
 * <p>Closing a dialog never changes state: no exit path here carries an action.
 */
public final class Dialogs {
	private static final int BODY_WIDTH = 320;
	private static final int BUTTON_WIDTH = 150;

	private Dialogs() {
	}

	// ------------------------------------------------------------ buttons

	/**
	 * A button that commits a change, carrying a freshly issued single-use nonce.
	 *
	 * @param extra additional payload the handler needs, such as which home was picked
	 */
	public static ActionButton commit(DialogService dialogs, UUID playerId, long tick,
			Component label, Identifier actionId, CompoundTag extra) {
		CompoundTag payload = extra.copy();
		payload.putString(DialogService.NONCE_KEY, dialogs.nonces().issue(playerId, tick));

		return new ActionButton(
				new CommonButtonData(label, BUTTON_WIDTH),
				Optional.of(new StaticAction(new ClickEvent.Custom(actionId, Optional.of(payload)))));
	}

	/**
	 * A button on a screen with input fields, which returns every field value alongside the nonce.
	 *
	 * <p>The client merges the typed values into the payload, so the handler receives both.
	 */
	public static ActionButton commitWithInputs(DialogService dialogs, UUID playerId, long tick,
			Component label, Identifier actionId, CompoundTag extra) {
		CompoundTag payload = extra.copy();
		payload.putString(DialogService.NONCE_KEY, dialogs.nonces().issue(playerId, tick));

		return new ActionButton(
				new CommonButtonData(label, BUTTON_WIDTH),
				Optional.of(new CustomAll(actionId, Optional.of(payload))));
	}

	/** A navigation button with no state change, so it needs no nonce. */
	public static ActionButton navigate(Component label, Identifier actionId, CompoundTag payload) {
		return new ActionButton(
				new CommonButtonData(label, BUTTON_WIDTH),
				Optional.of(new StaticAction(new ClickEvent.Custom(actionId, Optional.of(payload.copy())))));
	}

	public static ActionButton navigate(Component label, Identifier actionId) {
		return navigate(label, actionId, new CompoundTag());
	}

	/** A button that only dismisses the screen. */
	public static ActionButton plain(Component label) {
		return new ActionButton(new CommonButtonData(label, BUTTON_WIDTH), Optional.<Action>empty());
	}

	public static ActionButton close() {
		return plain(Component.literal("Close"));
	}

	public static ActionButton back() {
		return plain(Component.literal("Back"));
	}

	// ------------------------------------------------------------ bodies

	public static DialogBody text(String line) {
		return new PlainMessage(Component.literal(line), BODY_WIDTH);
	}

	public static DialogBody text(Component line) {
		return new PlainMessage(line, BODY_WIDTH);
	}

	// ------------------------------------------------------------ screens

	private static CommonDialogData common(String title, List<DialogBody> body, List<Input> inputs,
			DialogAction afterAction) {
		return new CommonDialogData(
				Component.literal(title),
				Optional.empty(),
				true,
				// Never pause: this is a server, and pausing is a singleplayer concept.
				false,
				afterAction,
				body,
				inputs);
	}

	/** A message with a single dismiss button. */
	public static NoticeDialog notice(String title, List<DialogBody> body, ActionButton action) {
		return new NoticeDialog(common(title, body, List.of(), DialogAction.CLOSE), action);
	}

	public static NoticeDialog notice(String title, String message) {
		return notice(title, List.of(text(message)), plain(Component.literal("OK")));
	}

	/** Confirm or go back. Used for both ordinary and destructive confirmations. */
	public static ConfirmationDialog confirmation(String title, List<DialogBody> body,
			ActionButton confirm, ActionButton back) {
		return new ConfirmationDialog(common(title, body, List.of(), DialogAction.CLOSE), confirm, back);
	}

	/** A list of choices, with an exit button that performs nothing. */
	public static MultiActionDialog menu(String title, List<DialogBody> body, List<ActionButton> actions,
			ActionButton exit, int columns) {
		return new MultiActionDialog(
				common(title, body, List.of(), DialogAction.CLOSE),
				actions,
				Optional.of(exit),
				columns);
	}

	/** A screen with one text field, used for naming a home and renaming a gate. */
	public static MultiActionDialog textEntry(String title, List<DialogBody> body, String inputKey,
			String inputLabel, String initial, int maxLength, ActionButton save, ActionButton back) {
		Input input = new Input(inputKey,
				new TextInput(BODY_WIDTH, Component.literal(inputLabel), true, initial, maxLength,
						Optional.<TextInput.MultilineOptions>empty()));

		return new MultiActionDialog(
				common(title, body, List.of(input), DialogAction.CLOSE),
				List.of(save),
				Optional.of(back),
				1);
	}
}
