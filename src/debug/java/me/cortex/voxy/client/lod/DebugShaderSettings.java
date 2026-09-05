package me.cortex.voxy.client.lod;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Narrow debug operation: only pack-declared options, through Iris's actual apply path. */
final class DebugShaderSettings {
    private final boolean enabled = IrisApi.getInstance().getConfig().areShadersEnabled();
    private final String pack = Iris.getIrisConfig().getShaderPackName().orElse("");
    private final Map<String, String> originals = new TreeMap<>();

    DebugShaderSettings() {
        if (!Iris.getShaderPackOptionQueue().isEmpty()) {
            throw new IllegalStateException("unapplied user shader options; refusing to overwrite");
        }
        recordOptions("before");
    }

    void option(String name, String value) {
        if (!this.pack.equals(Iris.getIrisConfig().getShaderPackName().orElse(""))) {
            throw new IllegalStateException("shader pack changed during run");
        }
        if (!Iris.getShaderPackOptionQueue().isEmpty()) throw new IllegalStateException("unapplied user options");
        var options = Iris.getCurrentPack().orElseThrow().getShaderPackOptions();
        var set = options.getOptionSet();
        var values = options.getOptionValues();
        String old;
        if (set.getBooleanOptions().containsKey(name)) {
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("not a boolean");
            old = Boolean.toString(values.getBooleanValueOrDefault(name));
        } else if (set.getStringOptions().containsKey(name)) {
            if (!set.getStringOptions().get(name).getOption().getAllowedValues().contains(value)) {
                throw new IllegalArgumentException("value not declared by shader pack");
            }
            old = values.getStringValueOrDefault(name);
        } else {
            throw new IllegalArgumentException("option not declared by shader pack: " + name);
        }
        this.originals.putIfAbsent(name, old);
        Properties update = new Properties();
        update.setProperty(name, value);
        Iris.queueShaderPackOptionsFromProperties(update);
        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(
                IrisApi.getInstance().getConfig().areShadersEnabled());
        recordOptions("applied");
    }

    void restore() {
        if (!this.pack.equals(Iris.getIrisConfig().getShaderPackName().orElse(""))
                || !Iris.getShaderPackOptionQueue().isEmpty()) {
            throw new IllegalStateException("user changed pack/pending options during run; original settings not overwritten");
        }
        var config = IrisApi.getInstance().getConfig();
        if (this.originals.isEmpty() && config.areShadersEnabled() == this.enabled) return;
        Properties update = new Properties();
        update.putAll(this.originals);
        Iris.queueShaderPackOptionsFromProperties(update);
        config.setShadersEnabledAndApply(this.enabled);
        recordOptions("restored");
    }

    static void recordOptions(String phase) {
        var pack = Iris.getCurrentPack();
        Map<String, String> values = new TreeMap<>();
        if (pack.isPresent()) {
            var options = pack.get().getShaderPackOptions();
            for (String name : options.getOptionSet().getBooleanOptions().keySet()) {
                values.put(name, Boolean.toString(options.getOptionValues().getBooleanValueOrDefault(name)));
            }
            for (String name : options.getOptionSet().getStringOptions().keySet()) {
                values.put(name, options.getOptionValues().getStringValueOrDefault(name));
            }
        }
        ClientLodDebug.updaterEvent("state=HARNESS_SHADER_OPTIONS phase=" + phase
                + " pack=" + Iris.getCurrentPackName() + " iris=" + Iris.getVersion()
                + " enabled=" + IrisApi.getInstance().getConfig().areShadersEnabled() + " options=" + values);
    }
}
