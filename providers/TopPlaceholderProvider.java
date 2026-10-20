package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in {top.*}: scoreboard leaderboards, highest score first.
 *
 * <pre>
 * #1 {top.name:kills:1} - {top.value:kills:1}
 * You are #{top.rank:kills} of {top.count:kills}
 * </pre>
 *
 * Vanilla objectives cover most boards without other mods, e.g.
 * {@code /scoreboard objectives add kills playerKillCount} or {@code minecraft.custom:minecraft.play_time}.
 */
public final class TopPlaceholderProvider implements Placeholders.PlaceholderProvider {

    private record Entry(String name, int value) {}
    private record Board(long gameTime, List<Entry> entries) {}

    /** Sorted once per tick per objective, however many tokens read it. */
    private static final Map<String, Board> CACHE = new ConcurrentHashMap<>();

    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("name:<objective>:<rank>", "Name at a rank (1 = highest)");
        KEYS.put("value:<objective>:<rank>", "Score at a rank");
        KEYS.put("rank:<objective>", "The viewer's rank, empty if unranked");
        KEYS.put("count:<objective>", "How many entries the board has");
        KEYS.put("name:state.<key>:<rank>", "Rank by a player variable instead of a scoreboard (e.g. state.coins)");
    }

    @Override public Map<String, String> keys() { return KEYS; }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null || ctx.level() == null || ctx.level().isClientSide) return "";
        MinecraftServer server = ctx.level().getServer();
        if (server == null || args == null || args.isBlank()) return "";

        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String a = args.trim();
        String objective = a;
        int rank = 1;
        int colon = a.lastIndexOf(':');
        if (colon > 0 && (k.equals("name") || k.equals("value"))) {
            objective = a.substring(0, colon).trim();
            try { rank = Integer.parseInt(a.substring(colon + 1).trim()); } catch (NumberFormatException e) { return ""; }
        }

        List<Entry> board = objective.toLowerCase(Locale.ROOT).startsWith("state.")
                ? stateBoard(server, objective.substring(6), ctx.level().getGameTime())
                : board(server.getScoreboard(), objective, ctx.level().getGameTime());
        if (board == null) return "";

        return switch (k) {
            case "name" -> rank >= 1 && rank <= board.size() ? board.get(rank - 1).name() : "";
            case "value" -> rank >= 1 && rank <= board.size() ? Integer.toString(board.get(rank - 1).value()) : "";
            case "count" -> Integer.toString(board.size());
            case "rank" -> {
                if (ctx.viewer() == null) yield "";
                String me = ctx.viewer().getScoreboardName();
                for (int i = 0; i < board.size(); i++) {
                    if (board.get(i).name().equals(me)) yield Integer.toString(i + 1);
                }
                yield "";
            }
            default -> "";
        };
    }

    /** Ranks players by a numeric player variable ({state.KEY}), e.g. {top.name:state.coins:1}. */
    private static List<Entry> stateBoard(MinecraftServer server, String key, long gameTime) {
        String cacheKey = "state." + key.toLowerCase(Locale.ROOT);
        Board cached = CACHE.get(cacheKey);
        if (cached != null && cached.gameTime() == gameTime) return cached.entries();
        List<Entry> out = new ArrayList<>();
        var cache = server.getProfileCache();
        com.luminacity.advancedinteractions.state.PlayerState.get(server).everyone(key).forEach((uuid, raw) -> {
            int v;
            try { v = (int) Math.round(Double.parseDouble(raw.trim())); } catch (NumberFormatException e) { return; }
            String name = cache == null ? null : cache.get(uuid).map(com.mojang.authlib.GameProfile::getName).orElse(null);
            out.add(new Entry(name == null ? uuid.toString().substring(0, 8) : name, v));
        });
        out.sort(Comparator.comparingInt(Entry::value).reversed().thenComparing(Entry::name));
        CACHE.put(cacheKey, new Board(gameTime, out));
        return out;
    }

    private static List<Entry> board(Scoreboard sb, String objectiveName, long gameTime) {
        Board cached = CACHE.get(objectiveName);
        if (cached != null && cached.gameTime() == gameTime) return cached.entries();

        Objective obj = sb.getObjective(objectiveName);
        if (obj == null) return null;
        List<Entry> out = new ArrayList<>();
        for (Score s : sb.getPlayerScores(obj)) {
            String owner = s.getOwner();
            if (owner == null || owner.startsWith("#")) continue; // '#' holders are hidden by convention
            out.add(new Entry(owner, s.getScore()));
        }
        out.sort(Comparator.comparingInt(Entry::value).reversed().thenComparing(Entry::name));
        if (CACHE.size() > 256) CACHE.clear();
        CACHE.put(objectiveName, new Board(gameTime, out));
        return out;
    }
}
