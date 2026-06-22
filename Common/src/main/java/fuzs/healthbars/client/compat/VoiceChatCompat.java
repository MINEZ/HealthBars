package fuzs.healthbars.client.compat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes, per entity and per client frame, the geometry of the in-level ("level") health bar
 * that is currently being rendered above an entity.
 * <p>
 * External mods can query {@link #getBarAnchor(int)} (reflectively, as a soft dependency) to align
 * their own name-tag-space overlays next to the health bar instead of next to the player's name
 * plate. This is used by Simple Voice Chat so its speaking icon follows the health bar when the
 * name plate is hidden.
 * <p>
 * All access happens on the client render thread; a concurrent map is used purely for defensiveness.
 */
public final class VoiceChatCompat {

    private static final Map<Integer, Anchor> ANCHORS = new ConcurrentHashMap<>();
    private static volatile long currentFrame;

    private VoiceChatCompat() {
        // NO-OP
    }

    /**
     * Records the level health bar geometry for an entity for the current frame. Called while
     * extracting the render state, i.e. before the entity (and thus its name tag overlays) is
     * rendered in the same frame.
     *
     * @param entityId     the entity network id (matches {@code EntityRenderState#id})
     * @param barWidth     the bar width in name-tag pixels; the bar is centered horizontally, so it
     *                     spans {@code [-barWidth / 2, +barWidth / 2]}
     * @param heightOffset the vertical name-tag-space offset; the 5px tall bar strip is drawn at
     *                     {@code heightOffset + 8}
     * @param renderScale  the scale factor applied on top of the {@code 0.025} name-tag scale
     */
    public static void putBar(int entityId, float barWidth, float heightOffset, float renderScale) {
        ANCHORS.put(entityId, new Anchor(currentFrame, new float[]{barWidth, heightOffset, renderScale}));
    }

    /**
     * Records that no level health bar is being drawn for an entity this frame.
     *
     * @param entityId the entity network id
     */
    public static void putNoBar(int entityId) {
        ANCHORS.remove(entityId);
    }

    /**
     * Advances the frame counter so entries that are no longer being updated become stale and
     * invisible to {@link #getBarAnchor(int)}. Should be called exactly once per client frame.
     */
    public static void nextFrame() {
        currentFrame++;
    }

    /**
     * Queries the level health bar geometry for an entity.
     *
     * @param entityId the entity network id
     * @return {@code {barWidth, heightOffset, renderScale}} when a level health bar is being drawn
     * for the entity in the current (or immediately preceding) frame, otherwise {@code null}
     */
    public static float[] getBarAnchor(int entityId) {
        Anchor anchor = ANCHORS.get(entityId);
        if (anchor == null || anchor.frame() < currentFrame - 1L) {
            return null;
        }

        return anchor.data();
    }

    private record Anchor(long frame, float[] data) {
        // NO-OP
    }
}
