package dev.silverx.llmcraft.agent;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Output;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ASK-mode confirmations. Buttons carry an unguessable id, so a server cannot forge an approval
 * with a fake clickable message.
 */
public final class Approvals {
    private record Pending(String id, Category category, String summary, CompletableFuture<Boolean> future) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    private static final Set<Category> SESSION_ALLOWED = EnumSet.noneOf(Category.class);

    private Approvals() {}

    public static synchronized boolean sessionAllowed(Category c) {
        return SESSION_ALLOWED.contains(c);
    }

    public static synchronized void clearSession() {
        SESSION_ALLOWED.clear();
        denyAll();
    }

    public static CompletableFuture<Boolean> request(Category category, String summary, int timeoutSeconds) {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        String id = HexFormat.of().formatHex(b);
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        synchronized (Approvals.class) {
            PENDING.put(id, new Pending(id, category, summary, f));
        }
        f.completeOnTimeout(false, Math.max(5, timeoutSeconds), TimeUnit.SECONDS)
                .whenComplete((ok, t) -> {
                    synchronized (Approvals.class) {
                        PENDING.remove(id);
                    }
                });
        Output.show(Output.header()
                .append(Component.literal("wants to: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(summary).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + category.name().toLowerCase() + ") ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Output.button("Allow", ChatFormatting.GREEN, "/llm approve " + id, "Allow this once (or press the Approve key)"))
                .append(" ")
                .append(Output.button("Always this session", ChatFormatting.DARK_GREEN, "/llm approve " + id + " session",
                        "Allow every '" + category.name().toLowerCase() + "' action until you leave this world"))
                .append(" ")
                .append(Output.button("Deny", ChatFormatting.RED, "/llm deny " + id, "Refuse (or press the Deny key)")));
        return f;
    }

    public static synchronized boolean approve(String id, boolean session) {
        Pending p = PENDING.remove(id);
        if (p == null) return false;
        if (session) SESSION_ALLOWED.add(p.category);
        p.future.complete(true);
        return true;
    }

    public static synchronized boolean deny(String id) {
        Pending p = PENDING.remove(id);
        if (p == null) return false;
        p.future.complete(false);
        return true;
    }

    public static synchronized boolean approveLatest() {
        String last = null;
        for (String k : PENDING.keySet()) last = k;
        return last != null && approve(last, false);
    }

    public static synchronized boolean denyLatest() {
        String last = null;
        for (String k : PENDING.keySet()) last = k;
        return last != null && deny(last);
    }

    public static synchronized void denyAll() {
        for (Pending p : PENDING.values()) p.future.complete(false);
        PENDING.clear();
    }

    public static synchronized int pendingCount() {
        return PENDING.size();
    }
}
