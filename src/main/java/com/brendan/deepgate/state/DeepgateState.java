package com.brendan.deepgate.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.home.HomeName;
import com.brendan.deepgate.home.HomeRecord;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The one persistent Deepgate record, held server-wide on the overworld (spec section 47).
 *
 * <p>Homes and portals are the only things that survive a restart. Combat timers, requests, undo
 * records, dialog nonces and portal sessions are all deliberately transient (section 48), which is
 * what keeps recovery after a crash trivial: there is nothing half-finished to reconcile.
 *
 * <p>{@link #DATA_VERSION} exists so a later release can migrate records rather than guess at them.
 */
public final class DeepgateState extends SavedData {
	/** Schema version, beginning at 1 (section 47). */
	public static final int DATA_VERSION = 1;

	private static final Codec<DeepgateState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(state -> state.dataVersion),
			HomeRecord.CODEC.listOf().optionalFieldOf("homes", List.of()).forGetter(state -> state.homes)
	).apply(instance, DeepgateState::new));

	private static final SavedDataType<DeepgateState> TYPE = new SavedDataType<>(
			Deepgate.id("state"),
			DeepgateState::new,
			CODEC,
			null);

	private final int dataVersion;
	private final List<HomeRecord> homes;

	private DeepgateState() {
		this(DATA_VERSION, List.of());
	}

	private DeepgateState(int dataVersion, List<HomeRecord> homes) {
		this.dataVersion = dataVersion;
		this.homes = new ArrayList<>(homes);
	}

	/** The single instance for this server. Stored on the overworld because Deepgate data is global. */
	public static DeepgateState get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	// ------------------------------------------------------------ homes

	/** Every home belonging to a player, in creation order. */
	public List<HomeRecord> homesOf(UUID owner) {
		return homes.stream().filter(home -> home.owner().equals(owner)).toList();
	}

	public Optional<HomeRecord> home(UUID id) {
		return homes.stream().filter(home -> home.id().equals(id)).findFirst();
	}

	/** A player's home by name, matched the same way uniqueness is enforced. */
	public Optional<HomeRecord> homeNamed(UUID owner, String name) {
		String folded = HomeName.fold(name);

		return homes.stream()
				.filter(home -> home.owner().equals(owner))
				.filter(home -> HomeName.fold(home.name()).equals(folded))
				.findFirst();
	}

	/** Whether this player already has a home on this beacon. */
	public Optional<HomeRecord> homeAt(UUID owner, ResourceKey<Level> dimension, BlockPos beacon) {
		return homes.stream()
				.filter(home -> home.owner().equals(owner))
				.filter(home -> home.isAt(dimension, beacon))
				.findFirst();
	}

	/**
	 * The name the first player to claim this beacon gave it, whoever they were.
	 *
	 * <p>A beacon can back a home for several players independently (section 14), and in practice a
	 * shared beacon is a shared landmark - a market, a hub, someone's front door. Offering the
	 * existing name to the next person who claims it means those homes agree with each other by
	 * default, instead of one place quietly collecting a different name per player.
	 *
	 * <p>Records are held in creation order, so the first match is the original.
	 */
	public Optional<String> firstHomeNameAt(ResourceKey<Level> dimension, BlockPos beacon) {
		return homes.stream()
				.filter(home -> home.isAt(dimension, beacon))
				.map(HomeRecord::name)
				.findFirst();
	}

	/** Every home bound to a beacon, whoever owns them, in creation order. */
	public List<HomeRecord> homesAt(ResourceKey<Level> dimension, BlockPos beacon) {
		return homes.stream().filter(home -> home.isAt(dimension, beacon)).toList();
	}

	public void add(HomeRecord home) {
		homes.add(home);
		setDirty();
	}

	public boolean remove(UUID homeId) {
		boolean removed = homes.removeIf(home -> home.id().equals(homeId));

		if (removed) {
			setDirty();
		}

		return removed;
	}

	public void replace(HomeRecord updated) {
		for (int i = 0; i < homes.size(); i++) {
			if (homes.get(i).id().equals(updated.id())) {
				homes.set(i, updated);
				setDirty();
				return;
			}
		}
	}

	/**
	 * Delete every home bound to a beacon, whoever owns them.
	 *
	 * <p>A beacon may back homes for several players independently (section 14), and breaking the
	 * block takes all of them with it (section 18) - the destination no longer exists for anyone.
	 *
	 * @return how many were removed
	 */
	public int removeHomesAt(ResourceKey<Level> dimension, BlockPos beacon) {
		int before = homes.size();
		homes.removeIf(home -> home.isAt(dimension, beacon));
		int removed = before - homes.size();

		if (removed > 0) {
			setDirty();
		}

		return removed;
	}

	public int totalHomes() {
		return homes.size();
	}
}
