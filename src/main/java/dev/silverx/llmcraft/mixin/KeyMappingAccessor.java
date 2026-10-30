package dev.silverx.llmcraft.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount")
    int llmcraft$getClickCount();

    @Accessor("clickCount")
    void llmcraft$setClickCount(int count);

    /** Writes the field directly, bypassing ToggleKeyMapping's toggle logic. */
    @Accessor("isDown")
    void llmcraft$setDownRaw(boolean down);
}
