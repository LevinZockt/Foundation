package org.mineacademy.fo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.mineacademy.fo.collection.ExpiringMap;
import org.mineacademy.fo.exception.FoException;
import org.mineacademy.fo.model.HookManager;
import org.mineacademy.fo.model.SimpleRunnable;
import org.mineacademy.fo.platform.Platform;
import org.mineacademy.fo.remain.Remain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Utility class for managing entities.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EntityUtil {

	/**
	 * Used to prevent duplicate registering of {@link HitTracking} listener but only
	 * register events when needed.
	 */
	private static boolean registeredHitListener = false;

	/**
	 * Returns the closest entity to the center location within the given 3-dimensional range
	 * that matches the given entity class, or null if not found.
	 *
	 * @param <T>
	 * @param center
	 * @param range3D
	 * @param entityClass
	 * @return
	 */
	public static <T extends LivingEntity> T findNearestEntity(Location center, double range3D, Class<T> entityClass) {
		final List<T> found = new ArrayList<>();

		for (final Entity nearby : Remain.getNearbyEntities(center, range3D))
			if (nearby instanceof LivingEntity && entityClass.isAssignableFrom(nearby.getClass()))
				found.add((T) nearby);

		Collections.sort(found, (first, second) -> Double.compare(first.getLocation().distance(center), second.getLocation().distance(center)));

		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * Returns the entity target only if it is a player, or null
	 *
	 * @param entity
	 * @return
	 */
	public static Player getTargetPlayer(Entity entity) {
		final Entity target = getTarget(entity);

		if (target == null)
			return null;

		return target instanceof Player && target.getLocation().getWorld().equals(entity.getWorld()) && !HookManager.isNPC(target) ? (Player) target : null;
	}

	/**
	 * Return the target for the given entity, supporting getting targets if entity is an NPC, use
	 * {@link HookManager#isNPC(Entity)} to check if the target is an NPC
	 *
	 * @param entity
	 * @return the target, or null if does not have / unsupported
	 */
	public static Entity getTarget(Entity entity) {
		Entity target = null;

		try {
			if (entity instanceof Mob)
				target = ((Mob) entity).getTarget();
		} catch (final Throwable t) {
			// Old MC
		}

		if (target == null && entity instanceof Creature)
			target = ((Creature) entity).getTarget();

		if (target == null)
			target = HookManager.getNPCTarget(entity);

		return target;
	}

	/**
	 * Attempts to spawn the entity for 1 tick at y=0 coordinate and then remove it
	 * as means to getting its default health in Minecraft.
	 *
	 * Must be on the main thread.
	 *
	 * @param type
	 * @return
	 */
	public static double getDefaultHealth(EntityType type) {
		Valid.checkSync("Cannot use getDefaultHealth async!");

		if (type == EntityType.PLAYER)
			return 20;

		final Location location = Bukkit.getWorlds().get(0).getSpawnLocation();
		location.setY(0);

		final Entity entity = location.getWorld().spawnEntity(location, type);
		Valid.checkBoolean(entity instanceof LivingEntity, "Cannot use getDefaultHealth for non-living entity: " + type);

		final double health = Remain.getHealth((LivingEntity) entity);

		entity.remove();
		return health;
	}

	/**
	 * Attempts to remove all vehicles and passengers stack from the given entity
	 *
	 * @param entity
	 */
	public static void removeVehiclesAndPassengers(Entity entity) {
		Entity vehicle = entity.getVehicle();

		while (vehicle != null) {
			final Entity copyOf = vehicle;
			vehicle = vehicle.getVehicle();

			copyOf.remove();
		}

		try {
			for (final Entity passenger : entity.getPassengers())
				passenger.remove();

		} catch (final NoSuchMethodError err) {
			final Entity passenger = entity.getPassenger();

			if (passenger != null)
				passenger.remove();
		}
	}

	/**
	 * Return if this entity is creature and aggressive (not an animal)
	 *
	 * @param entity
	 * @return
	 */
	public static boolean isAggressive(Entity entity) {
		if (entity instanceof Ghast || entity instanceof Slime)
			return true;

		if (entity instanceof Wolf && ((Wolf) entity).isAngry())
			return true;

		if (entity instanceof Animals)
			return false;

		return entity instanceof Creature;
	}

	/**
	 * Return if this entity is a {@link Creature}, {@link Slime} or {@link Wolf}
	 *
	 * @param entity
	 * @return
	 */
	public static boolean isCreature(Entity entity) {
		return entity instanceof Slime || entity instanceof Wolf || entity instanceof Creature;
	}

	/**
	 * Return if this entity is suitable for removing (e.g. dropped items,
	 * falling blocks, arrows, projectiles)
	 *
	 * @param entity
	 * @return
	 */
	public static boolean canBeCleaned(Entity entity) {
		return entity instanceof FallingBlock || entity instanceof Item || entity instanceof Projectile || entity instanceof ExperienceOrb;
	}

	// ----------------------------------------------------------------------------------------------------
	// Tracking
	// ----------------------------------------------------------------------------------------------------

	/**
	 * Runs a timer task with the tick period of 1 and will trigger your hit listener
	 * when the given entity isOnGround. If the entity gets removed before it hits
	 * the ground, nothing is called
	 * <p>
	 * If the entity still flies after 30 seconds, nothing is called
	 *
	 * @param entity
	 * @param hitGroundListener
	 */
	public static void trackFalling(Entity entity, Runnable hitGroundListener) {
		track(entity, 30 * 20, null, hitGroundListener);
	}

	/**
	 * Runs a timer task with the tick period of 1 and will trigger your fly listener
	 * each tick until entity is either removed or isOnGround
	 * <p>
	 * If the entity still flies after 30 seconds, nothing is called
	 *
	 * @param entity
	 * @param flyListener
	 */
	public static void trackFlying(Entity entity, Runnable flyListener) {
		track(entity, 30 * 20, flyListener, null);
	}

	/**
	 * Runs a timer task with the tick period of 1 and will trigger your hit listener
	 * when the given entity isOnGround. If the entity gets removed before it hits
	 * the ground, nothing is called
	 * <p>
	 * The fly listener is called every tick
	 *
	 * @param entity
	 * @param timeoutTicks
	 * @param flyListener
	 * @param hitGroundListener
	 */
	public static void track(Entity entity, int timeoutTicks, Runnable flyListener, Runnable hitGroundListener) {
		if (flyListener == null && hitGroundListener == null)
			throw new FoException("Cannot track entity with fly and hit listeners on null!");

		Platform.runTaskTimer(1, new SimpleRunnable() {

			private int elapsedTicks = 0;

			@Override
			public void run() {

				// Cancel after the given timeout to save performance
				if (this.elapsedTicks++ > timeoutTicks) {
					this.cancel();

					return;
				}

				// Cancel when invalid
				if (entity == null || entity.isDead() || !entity.isValid()) {
					if (entity instanceof FallingBlock && hitGroundListener != null)
						hitGroundListener.run();

					this.cancel();
					return;
				}

				// Run the hit listener
				if (entity.isOnGround()) {
					if (hitGroundListener != null)
						hitGroundListener.run();

					this.cancel();

				} else if (flyListener != null)
					flyListener.run();
			}
		});
	}

	/**
	 * (No timer task) Starts tracking a projectile's impact and executes the hit
	 * task when it hits something. After 30 seconds of flight we stop tracking
	 * to save performance
	 *
	 * @param projectile
	 * @param hitTask
	 */
	public static void trackHit(Projectile projectile, Consumer<ProjectileHitEvent> hitTask) {
		HitTracking.addFlyingProjectile(projectile, hitTask);

		if (!registeredHitListener) {
			Platform.registerEvents(new HitTracking());

			registeredHitListener = true;
		}
	}
}

/**
 * Class responsible for tracking connection between projectile launch and projectile hit event
 */
class HitTracking implements Listener {

	/**
	 * List of flying projectiles with code to run on impact,
	 * stop tracking after 30 seconds to prevent overloading the map
	 */
	private static ExpiringMap<UUID, List<Consumer<ProjectileHitEvent>>> flyingProjectiles = ExpiringMap.builder().expiration(30, TimeUnit.SECONDS).build();

	/**
	 * Invoke the hit listener when the registered projectile hits something
	 *
	 * @param event
	 */
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onHit(ProjectileHitEvent event) {
		final List<Consumer<ProjectileHitEvent>> hitListeners = flyingProjectiles.remove(event.getEntity().getUniqueId());

		if (hitListeners != null)
			for (final Consumer<ProjectileHitEvent> listener : hitListeners)
				listener.accept(event);
	}

	/**
	 * Add a new flying projectile that will be pending and execute code when collide
	 *
	 * @param projectile
	 * @param hitTask
	 */
	static void addFlyingProjectile(Projectile projectile, Consumer<ProjectileHitEvent> hitTask) {
		final UUID uniqueId = projectile.getUniqueId();
		final List<Consumer<ProjectileHitEvent>> listeners = flyingProjectiles.getOrDefault(uniqueId, new ArrayList<>());

		listeners.add(hitTask);
		flyingProjectiles.put(uniqueId, listeners);
	}
}
