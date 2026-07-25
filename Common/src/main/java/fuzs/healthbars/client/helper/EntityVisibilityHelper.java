package fuzs.healthbars.client.helper;

import fuzs.healthbars.HealthBars;
import fuzs.healthbars.client.handler.PickEntityHandler;
import fuzs.healthbars.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class EntityVisibilityHelper {

    public static boolean isEntityVisible(LivingEntity livingEntity, float partialTick, boolean mustBePicked) {
        Minecraft minecraft = Minecraft.getInstance();
        return isEntityVisible(minecraft.level,
                livingEntity,
                minecraft.player,
                partialTick,
                minecraft.getEntityRenderDispatcher(),
                mustBePicked);
    }

    public static boolean isEntityVisible(Level level, LivingEntity livingEntity, Player player, float partialTick, EntityRenderDispatcher entityRenderDispatcher, boolean mustBePicked) {
        if (mustBePicked && livingEntity != PickEntityHandler.getCrosshairPickEntity()) {
            return false;
        } else if (!shouldShowName(livingEntity)) {
            // run this earlier than vanilla to avoid raytracing if not necessary
            return false;
        } else {
            return entityRenderDispatcher.distanceToSqr(livingEntity) < getMaxRenderDistanceSqr(level,
                    livingEntity,
                    player,
                    partialTick);
        }
    }

    /**
     * Originally copied from
     * {@link net.minecraft.client.renderer.entity.LivingEntityRenderer#shouldShowName(LivingEntity, double)}.
     * <p>
     * The team name tag visibility branch (which would return {@code false} for a team set to
     * {@code NEVER}, and apply the {@code HIDE_FOR_*} rules otherwise) has been intentionally
     * removed: health bar visibility is decoupled from the entity's name plate visibility. This
     * way hiding a player's name plate via a scoreboard team (a common server setup) no longer
     * hides its health bar. Whether the bar shows now depends only on this mod's own config plus
     * the baseline checks below (GUI hidden, camera entity, and invisibility).
     * <p>
     * The {@code !entity.isVehicle()} check is dropped as well, so a ridden entity keeps its
     * health bar; {@link fuzs.healthbars.client.handler.InLevelRenderingHandler} moves the bar
     * anchor up to the topmost passenger instead.
     */
    private static boolean shouldShowName(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean isVisible = isVisibleToPlayer(entity, player);
        return Minecraft.renderNames() && entity != minecraft.getCameraEntity() && isVisible;
    }

    private static boolean isVisibleToPlayer(LivingEntity entity, Player player) {
        if (entity.isSpectator()) {
            return false;
        } else if (entity.isInvisibleTo(player)) {
            if (entity.isOnFire() || entity.isCurrentlyGlowing()) {
                return true;
            } else if (entity instanceof Creeper creeper && creeper.isPowered()) {
                return true;
            } else {
                for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
                    ItemStack itemStack = entity.getItemBySlot(equipmentSlot);
                    if (!itemStack.isEmpty()) {
                        return true;
                    }
                }

                return false;
            }
        } else {
            return true;
        }
    }

    private static int getMaxRenderDistanceSqr(Level level, LivingEntity livingEntity, Player player, float partialTick) {
        int maxRenderDistance = HealthBars.CONFIG.get(ClientConfig.class).level.maxRenderDistance;
        if (livingEntity.isDiscrete()) maxRenderDistance /= 2;
        // use this instead of LivingEntity::hasLineOfSight, so we can look through transparent blocks like glass
        if (pickVisual(level, livingEntity, player, partialTick).getType() != HitResult.Type.MISS) {
            maxRenderDistance /= 4;
        }

        return maxRenderDistance * maxRenderDistance;
    }

    private static HitResult pickVisual(Level level, LivingEntity livingEntity, Player player, float partialTick) {
        Vec3 playerEyePosition = player.getEyePosition(partialTick);
        Vec3 entityEyePosition = livingEntity.getEyePosition(partialTick);
        return level.clip(new ClipContext(playerEyePosition,
                entityEyePosition,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player));
    }
}
