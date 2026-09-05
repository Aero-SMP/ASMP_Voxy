package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.*;

/** Render-thread-only transient evidence. Strings alone cross into the existing log writer. */
final class ShaderDebugTelemetry {
    private static final ThreadLocal<Reload> CURRENT = new ThreadLocal<>();
    private static final class Reload {
        final long renderer, generation, started = System.nanoTime();
        String material = "not-reached";
        Reload(VoxyRenderSystem renderer) { this.renderer = renderer.rendererIdentity(); this.generation = renderer.shaderReloadGeneration(); }
    }
    static void begin(VoxyRenderSystem renderer, Object pipeline, long oldResources, long newResources) {
        Reload reload = new Reload(renderer); CURRENT.set(reload);
        ClientLodDebug.workerEvidence("VOXY_SHADER_RELOAD phase=BEGIN_PAUSED renderer=" + reload.renderer
                + " session=" + ClientSession.debugSessionIdentity() + " reload=" + reload.generation
                + " trigger=" + renderer.shaderReloadReason() + " oldPipeline=" + kind(pipeline)
                + " resourceGeneration=" + oldResources + "->" + newResources
                + " geometrySections=" + renderer.regionalGeometrySectionCount()
                + " geometryBytes=" + renderer.regionalGeometryUsedBytes());
    }
    static void classification(VoxyRenderSystem renderer, ModelFactory factory, Map<?, ?> oldMap,
                               Map<?, ?> newMap, Object pipeline) {
        ModelFactory.DebugModels models = factory.debugModels();
        Diff diff = diff(oldMap == null ? Map.of() : oldMap, newMap, models);
        String header = "VOXY_SHADER_CLASSIFICATION renderer=" + renderer.rendererIdentity()
                + " session=" + ClientSession.debugSessionIdentity() + " reload=" + renderer.shaderReloadGeneration()
                + " initialMap=" + (oldMap == null) + " newPipeline=" + kind(pipeline) + " " + diff.summary;
        ClientLodDebug.workerEvidence(header + " examples=" + diff.examples);
        ClientLodDebug.shaderArtifact(header + "\n" + diff.full
                + "\nOverlap describes mapped/baked/pending states, NOT currently drawn section usage."
                + "\nZero overlap does not prove future bakes safe; raw equality guard is unchanged.\n");
    }
    record Diff(String summary, String examples, String full, int changed, int mapped, int baked, int pending) {}

    static Diff diff(Map<?, ?> before, Map<?, ?> after, ModelFactory.DebugModels models) {
        Set<Object> keys = new HashSet<>(before.keySet()); keys.addAll(after.keySet());
        Set<Block> changedBlocks = new HashSet<>();
        int added = 0, removed = 0, changed = 0, explicitChanged = 0, effectiveUnknown = 0;
        List<String> lines = new ArrayList<>();
        for (Object key : keys) {
            boolean oldHas = before.containsKey(key), newHas = after.containsKey(key);
            Object old = before.get(key), next = after.get(key);
            if (oldHas == newHas && Objects.equals(old, next)) continue;
            changed++;
            if (!oldHas) added++; else if (!newHas) removed++;
            if (old != null && next != null && key instanceof Block) explicitChanged++; else effectiveUnknown++;
            if (key instanceof Block block) changedBlocks.add(block);
            lines.add(identity(key) + " old=" + classification(old) + " new=" + classification(next));
        }
        int mapped = 0, baked = 0, pending = 0, conservativeExceptions = 0;
        for (int i = 0; i < models.states().length; i++) {
            boolean raw = changedBlocks.contains(models.states()[i].getBlock());
            boolean alias = changedBlocks.contains(models.aliases()[i].getBlock());
            if (raw) mapped++;
            if (alias && models.models()[i] >= 0) baked++;
            if ((raw || alias) && models.pending()[i]) pending++;
            if ((raw || alias) && (models.aliases()[i].getBlock() instanceof LeavesBlock
                    || models.aliases()[i].getBlock() instanceof LiquidBlock)) conservativeExceptions++;
        }
        Collections.sort(lines);
        String summary = "rawChanged=" + changed + " added=" + added + " removed=" + removed
                + " replaced=" + (changed - added - removed) + " explicitClassificationChanges=" + explicitChanged
                + " fallbackEffectiveUnknown=" + effectiveUnknown + " mappedOverlap=" + mapped
                + " bakedAliasOverlap=" + baked + " pendingOverlap=" + pending
                + " voxyLeafFluidExceptions=" + conservativeExceptions
                + " overlap=" + (changedBlocks.size() != changed ? "UNKNOWN" : mapped + baked + pending == 0 ? "ZERO" : "NONZERO")
                + " drawnUsage=UNKNOWN";
        return new Diff(summary, String.join("; ", lines.subList(0, Math.min(6, lines.size()))),
                String.join("\n", lines), changed, mapped, baked, pending);
    }
    // Installed Iris 1.8.12 maps Block -> BlockRenderType. A missing/null value does NOT mean
    // SOLID: Iris leaves vanilla/other-mod classification intact. Do not guess that fallback.
    private static String classification(Object value) {
        return value == null ? "FALLBACK_UNKNOWN" : value instanceof Enum<?> e ? e.name() : "UNKNOWN_VALUE_TYPE";
    }
    private static String identity(Object value) {
        return value instanceof Block b ? BuiltInRegistries.BLOCK.getKey(b).toString() : "UNKNOWN_KEY_TYPE";
    }
    static String canonical(BlockState state) {
        StringJoiner properties = new StringJoiner(",", "[", "]");
        state.getValues().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().getName()))
                .forEach(e -> properties.add(e.getKey().getName() + "=" + propertyName(state, e.getKey())));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + properties.toString();
    }
    private static <T extends Comparable<T>> String propertyName(BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property) {
        return property.getName(state.getValue(property));
    }
    static String aliasConflict(ModelFactory.DebugModels models, Object2IntMap<BlockState> mapping) {
        Map<Integer, Integer> representatives = new HashMap<>();
        for (int state = 0; state < models.models().length; state++) {
            int id = models.models()[state]; if (id < 0) continue;
            Integer first = representatives.putIfAbsent(id, state);
            if (first == null) continue;
            BlockState a = models.aliases()[first], b = models.aliases()[state];
            int old = mapping.containsKey(a) ? mapping.getInt(a) : 0;
            int next = mapping.containsKey(b) ? mapping.getInt(b) : 0;
            if (old != next) return "model=" + id + " first=" + canonical(a) + " material=" + old
                    + " conflicting=" + canonical(b) + " material=" + next;
        }
        return "conflictingAlias=UNKNOWN";
    }
    static void material(ModelFactory factory, Object2IntMap<BlockState> mapping, String outcome) {
        Reload reload = CURRENT.get();
        if (reload == null) return;
        reload.material = outcome + " cumulativeUpdates=" + factory.materialUpdates()
                + (outcome.equals("ALIAS_CONFLICT") ? " " + aliasConflict(factory.debugModels(), mapping) : "");
        // Called under ModelFactory's monitor. Defer writing/logging until the shader boundary exits it.
    }
    static void end(VoxyRenderSystem renderer, String outcome, String reason) {
        Reload reload = CURRENT.get();
        if (reload == null || reload.renderer != renderer.rendererIdentity()) return;
        CURRENT.remove();
        ClientLodDebug.workerEvidence("VOXY_SHADER_RELOAD phase=END renderer=" + reload.renderer
                + " session=" + ClientSession.debugSessionIdentity() + " reload=" + reload.generation
                + " outcome=" + outcome + " reason=" + reason + " durationNs=" + (System.nanoTime() - reload.started)
                + " material=" + reload.material);
    }
    private static String kind(Object pipeline) { return pipeline == null ? "NONE" : pipeline.getClass().getSimpleName(); }
}
