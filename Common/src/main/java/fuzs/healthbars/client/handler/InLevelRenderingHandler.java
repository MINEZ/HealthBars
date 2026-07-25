package fuzs.healthbars.client.handler;

import com.mojang.blaze3d.vertex.PoseStack;
import fuzs.healthbars.HealthBars;
import fuzs.healthbars.client.gui.GraphicsLayer;
import fuzs.healthbars.client.helper.EntityVisibilityHelper;
import fuzs.healthbars.client.helper.HealthBarRenderHelper;
import fuzs.healthbars.world.entity.HealthTracker;
import fuzs.healthbars.client.renderer.entity.state.HealthTrackerRenderState;
import fuzs.healthbars.config.ClientConfig;
import fuzs.puzzleslib.api.client.renderer.v1.RenderStateExtraData;
import fuzs.puzzleslib.api.event.v1.core.EventResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

public class InLevelRenderingHandler {
    private static final ContextKey<Optional<HealthTrackerRenderState>> HEALTH_TRACKER_PROPERTY = new ContextKey<>(
            HealthBars.id("health_tracker"));

    private static boolean isRenderingInGui;

    public static void setIsRenderingInGui(boolean isRenderingInGui) {
        InLevelRenderingHandler.isRenderingInGui = isRenderingInGui;
    }

    public static void onExtractRenderState(Entity entity, EntityRenderState entityRenderState, float partialTick) {
        if (entity instanceof LivingEntity livingEntity && canBarRender(livingEntity, partialTick)) {
            HealthTracker healthTracker = HealthTracker.getHealthTracker(livingEntity, false);
            if (healthTracker != null) {
                HealthTrackerRenderState renderState = HealthTrackerRenderState.extractRenderState(healthTracker,
                        livingEntity,
                        partialTick,
                        HealthBars.CONFIG.get(ClientConfig.class).level);
                // in picked-entity mode only the crosshair's target is shown, and it should always
                // appear at the topmost mob's head rather than at its own height within the stack, so
                // its bar is pinned to the anchor row (stackHeight 0); otherwise bars fan out by height
                renderState.stackHeight =
                        HealthBars.CONFIG.get(ClientConfig.class).level.pickedEntity ? 0 : getStackHeight(entity);
                // anchor the whole riding stack to a single point (the topmost mob's name tag, which
                // already sits above the mob pile) and record that entity's camera distance, so every
                // bar in the stack shares one anchor and one render scale and therefore stays
                // horizontally aligned and clears the models; the bars are then fanned upward when drawn
                Entity anchorEntity = getTopEntity(entity);
                if (anchorEntity != entity) {
                    renderState.anchorDistanceToCameraSq = distanceToCameraSq(anchorEntity, partialTick);
                }
                RenderStateExtraData.set(entityRenderState, HEALTH_TRACKER_PROPERTY, Optional.of(renderState));
                Vec3 nameTagAttachment = getNameTagAttachment(entity, anchorEntity, partialTick);
                if (entityRenderState.nameTag == null) {
                    // we must force the name tag to render, as the name tag render event does not run unless this is set
                    entityRenderState.nameTag = CommonComponents.EMPTY;
                    entityRenderState.nameTagAttachment = nameTagAttachment;
                } else if (anchorEntity != entity && nameTagAttachment != null) {
                    entityRenderState.nameTagAttachment = nameTagAttachment;
                }
            }
        }
    }

    private static boolean canBarRender(LivingEntity livingEntity, float partialTick) {
        if (!HealthBars.CONFIG.get(ClientConfig.class).anyRendering.get()
                || !HealthBars.CONFIG.get(ClientConfig.class).levelRendering || isRenderingInGui) {
            return false;
        } else if (livingEntity.isAlive() && HealthBars.CONFIG.get(ClientConfig.class).isEntityAllowed(livingEntity)) {
            Minecraft minecraft = Minecraft.getInstance();
            Vec3 nameTagAttachment = livingEntity.getAttachments()
                    .getNullable(EntityAttachment.NAME_TAG, 0, livingEntity.getViewYRot(partialTick));
            // other mods might be rendering this mob without a level in some menu, so the camera is null then
            if (nameTagAttachment != null && minecraft.getEntityRenderDispatcher().camera != null) {
                return EntityVisibilityHelper.isEntityVisible(livingEntity,
                        partialTick,
                        HealthBars.CONFIG.get(ClientConfig.class).level.pickedEntity);
            }
        }

        return false;
    }

    public static EventResult onRenderNameTag(EntityRenderer<?, ?> entityRenderer, EntityRenderState entityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState) {
        Component component = entityRenderState.nameTag;
        Optional<HealthTrackerRenderState> optional = RenderStateExtraData.getOrDefault(entityRenderState,
                HEALTH_TRACKER_PROPERTY,
                Optional.empty());
        if (component != null && optional.isPresent()) {
            poseStack.pushPose();
            Vec3 vec3 = entityRenderState.nameTagAttachment;
            if (vec3 != null) {
                poseStack.translate(vec3.x, vec3.y + 0.5, vec3.z);
            }

            poseStack.mulPose(cameraRenderState.orientation);
            HealthTrackerRenderState renderState = optional.get();
            // use the anchor entity's camera distance when this bar belongs to a riding stack, so the
            // whole stack is scaled identically and the bars line up horizontally
            double distanceToCameraSq = renderState.anchorDistanceToCameraSq >= 0.0
                    ? renderState.anchorDistanceToCameraSq
                    : entityRenderState.distanceToCameraSq;
            float renderScale = getRenderScale(distanceToCameraSq);
            poseStack.scale(0.025F * renderScale, -0.025F * renderScale, 0.025F * renderScale);
            ClientConfig.Level config = HealthBars.CONFIG.get(ClientConfig.class).level;
            int posY = config.heightOffset;
            if (Objects.equals("deadmau5", component.getString())) {
                posY -= 10;
            }

            if (!config.renderTitleComponent && component != CommonComponents.EMPTY) {
                posY -= 13;
            }

            // the stack is anchored at the topmost mob's name tag, which already sits above the mob
            // pile. the bottom mob's bar sits at the anchor and each mob higher up the stack gets a
            // bar one row higher, so the whole fan floats above the pile without overlapping while
            // its top-to-bottom order matches the physical riding order (the mob on top, the bar on top)
            posY -= renderState.stackHeight * getStackSpacing(config, Minecraft.getInstance().font);

            int lightCoords = config.fullBrightness ? GraphicsLayer.PACKED_LIGHT : entityRenderState.lightCoords;
            GraphicsLayer graphicsLayer = new GraphicsLayer.Level(poseStack, submitNodeCollector);
            if (config.behindWalls) {
                submitHealthBar(graphicsLayer,
                        0,
                        posY,
                        renderState,
                        ARGB.white(0.125F),
                        Font.DisplayMode.SEE_THROUGH,
                        renderState.backgroundColor,
                        lightCoords,
                        entityRenderState.outlineColor);
            }

            submitHealthBar(graphicsLayer,
                    0,
                    posY,
                    renderState,
                    -1,
                    Font.DisplayMode.NORMAL,
                    config.behindWalls ? 0 : renderState.backgroundColor,
                    lightCoords,
                    entityRenderState.outlineColor);
            poseStack.popPose();
            // when the component is empty, rendering has been forced by us and vanilla should not be allowed to proceed
            if (config.renderTitleComponent || component == CommonComponents.EMPTY) {
                return EventResult.INTERRUPT;
            }
        }

        return EventResult.PASS;
    }

    /**
     * The entity's height within its riding stack, counted from the bottom: {@code 0} for the entity
     * at the very bottom, {@code 1} for whatever rides it, and so on up to the topmost passenger. The
     * stack is anchored at the topmost mob's name tag (already above the pile), so drawing each bar
     * {@code stackHeight} rows up floats the whole fan above the pile while ordering the bars to match
     * the physical riding order: the mob higher in the stack gets the higher bar.
     */
    private static int getStackHeight(Entity entity) {
        int stackHeight = 0;
        Entity vehicle = entity.getVehicle();
        while (vehicle != null) {
            stackHeight++;
            vehicle = vehicle.getVehicle();
        }

        return stackHeight;
    }

    /**
     * Vertical spacing between the health bars of entities in the same riding stack, in name tag
     * space pixels. Mirrors the layout of {@code HealthBarRenderHelper#submitHealthBarDecorations}:
     * the 5px tall bar strip, plus one text line per enabled component, plus a small gap.
     */
    private static int getStackSpacing(ClientConfig.Level config, Font font) {
        int lines = 0;
        if (config.renderAttributeComponents) lines++;
        if (config.renderTitleComponent) lines++;
        return 5 + lines * (font.lineHeight + 2) + 2;
    }

    /**
     * The entity at the very top of the riding stack (the topmost passenger). For a lone entity this
     * is the entity itself.
     */
    private static Entity getTopEntity(Entity entity) {
        Entity topEntity = entity;
        while (!topEntity.getPassengers().isEmpty()) {
            topEntity = topEntity.getPassengers().get(0);
        }

        return topEntity;
    }

    /**
     * Returns the name tag attachment the health bar should be anchored to. For a ridden entity this
     * is the attachment of the bottom of the stack ({@code anchorEntity}), converted into the render
     * coordinate space of the entity actually being rendered, so that every bar in one stack shares
     * a single anchor point and lines up horizontally.
     */
    @Nullable
    private static Vec3 getNameTagAttachment(Entity entity, Entity anchorEntity, float partialTick) {
        Vec3 attachment = entity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        if (anchorEntity == entity || attachment == null) {
            return attachment;
        }

        Vec3 anchorAttachment = anchorEntity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, anchorEntity.getViewYRot(partialTick));
        if (anchorAttachment == null) {
            return attachment;
        }

        // convert the anchor entity's name tag point into an offset relative to the render origin of
        // the entity that is currently being rendered
        Vec3 delta = lerpPosition(anchorEntity, partialTick).subtract(lerpPosition(entity, partialTick));
        return anchorAttachment.add(delta);
    }

    private static double distanceToCameraSq(Entity entity, float partialTick) {
        return Minecraft.getInstance()
                .getEntityRenderDispatcher().camera.getPosition()
                .distanceToSqr(lerpPosition(entity, partialTick));
    }

    private static Vec3 lerpPosition(Entity entity, float partialTick) {
        return new Vec3(Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));
    }

    private static float getRenderScale(double distanceToCameraSq) {
        float renderScale = (float) HealthBars.CONFIG.get(ClientConfig.class).level.renderScale;
        if (HealthBars.CONFIG.get(ClientConfig.class).level.scaleWithDistance) {
            double entityInteractionRange = Minecraft.getInstance().player.entityInteractionRange();
            double scaleRatio = Mth.clamp((distanceToCameraSq - Math.pow(entityInteractionRange / 2.0, 2.0)) / (
                    Math.pow(entityInteractionRange * 2.0, 2.0) / 2.0), 0.0, 2.0);
            renderScale *= (float) (1.0 + scaleRatio);
        }

        return renderScale;
    }

    private static void submitHealthBar(GraphicsLayer graphicsLayer, int posX, int posY, HealthTrackerRenderState renderState, int color, Font.DisplayMode displayMode, int backgroundColor, int lightCoords, int outlineColor) {
        HealthBarRenderHelper.submitHealthBar(graphicsLayer,
                displayMode == Font.DisplayMode.SEE_THROUGH ? RenderType::textSeeThrough : RenderType::text,
                posX,
                posY,
                renderState,
                color,
                lightCoords);
        HealthBarRenderHelper.submitHealthBarDecorations(graphicsLayer,
                posX,
                posY,
                Minecraft.getInstance().font,
                renderState,
                color,
                displayMode,
                backgroundColor,
                lightCoords,
                outlineColor);
    }
}
