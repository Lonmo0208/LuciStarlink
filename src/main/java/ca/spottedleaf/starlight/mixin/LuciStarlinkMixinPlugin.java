package ca.spottedleaf.starlight.mixin;

import ca.spottedleaf.starlight.common.compat.SablePresence;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the Sable interop mixins only when Sable's marker class is actually there (see {@link SablePresence}). */
public final class LuciStarlinkMixinPlugin implements IMixinConfigPlugin {
    private static final String SABLE_MIXIN_PACKAGE = "ca.spottedleaf.starlight.mixin.compat.sable.";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.startsWith(SABLE_MIXIN_PACKAGE) || SablePresence.isPresent();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
