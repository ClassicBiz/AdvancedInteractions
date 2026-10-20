package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.AdvancedInteractions;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/** Runs server work a number of ticks later (delayed action-chain steps). Server thread only. */
@Mod.EventBusSubscriber(modid = AdvancedInteractions.MODID)
public final class TaskScheduler {
    private TaskScheduler() {}

    private record Task(long due, Consumer<MinecraftServer> work) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static final int MAX_TASKS = 20_000;
    private static long tick;

    /** Run {@code work} after {@code ticks} server ticks (0 = next tick). */
    public static void later(int ticks, Consumer<MinecraftServer> work) {
        if (work == null || TASKS.size() >= MAX_TASKS) return;
        TASKS.add(new Task(tick + Math.max(1, ticks), work));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        if (TASKS.isEmpty()) return;
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.due() <= tick) { due.add(t); it.remove(); }
        }
        for (Task t : due) {
            try { t.work().accept(e.getServer()); } catch (Throwable ignored) { }
        }
    }
}
