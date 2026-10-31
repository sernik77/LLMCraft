package dev.silverx.llmcraft.tool.impl;

import com.mojang.blaze3d.platform.NativeImage;
import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Snapshot;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class VisionTools {
    private VisionTools() {}

    public static void register(List<Tool> out) {
        out.add(Tool.builder("screenshot", Category.VISION, "Look at the screen as an image. Rarely needed - the game state and tool results are exact text; use it only for visuals text cannot describe (builds, maps, custom mod screens).")
                .param("max_width", "integer", "Downscale to this width (default 1024, min 256)", false)
                .available(ctx -> ctx.profile != null && ctx.profile.vision)
                .async((ctx, a) -> {
                    int maxWidth = Math.max(256, Math.min(2048, Args.integer(a, "max_width", 1024)));
                    Minecraft mc = Minecraft.getInstance();
                    CompletableFuture<ToolResult> f = new CompletableFuture<>();
                    String caption = "Screen: " + Snapshot.screenName(mc.screen) + (mc.player != null ? " | " + Snapshot.crosshair(mc) : "");
                    try {
                        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                            try {
                                f.complete(ToolResult.ok("Screenshot attached. " + caption).withImage("image/png", encode(image, maxWidth)));
                            } catch (Throwable t) {
                                f.complete(ToolResult.error("could not encode screenshot: " + t));
                            } finally {
                                image.close();
                            }
                        });
                    } catch (Throwable t) {
                        f.complete(ToolResult.error("could not take screenshot: " + t));
                    }
                    return f;
                }).build());
    }

    private static String encode(NativeImage image, int maxWidth) throws Exception {
        NativeImage scaled = image;
        if (image.getWidth() > maxWidth) {
            int h = Math.max(1, image.getHeight() * maxWidth / image.getWidth());
            scaled = new NativeImage(maxWidth, h, false);
            image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), scaled);
        }
        Path tmp = Files.createTempFile("llmcraft-shot", ".png");
        try {
            scaled.writeToFile(tmp);
            return Base64.getEncoder().encodeToString(Files.readAllBytes(tmp));
        } finally {
            if (scaled != image) scaled.close();
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception e) {
                LlmCraft.LOGGER.debug("tmp cleanup", e);
            }
        }
    }
}
