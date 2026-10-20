package com.luminacity.advancedinteractions.integration;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {ftbquests.*} and {ftbteams.*} placeholders when FTB Quests / FTB Teams are installed. Uses reflection
 * against their public API (checked against FTB Quests 1902.5 and FTB Teams 1902.2), so there is no
 * build dependency and nothing happens when they're absent.
 */
public final class FtbIntegration {
    private FtbIntegration() {}

    private static final Logger LOG = LoggerFactory.getLogger("AdvancedInteractions");

    public static void register() {
        if (ModList.get().isLoaded("ftbquests")) {
            if (Quests.init()) Placeholders.register("ftbquests", new QuestsProvider());
        }
        if (ModList.get().isLoaded("ftbteams")) {
            if (Teams.init()) Placeholders.register("ftbteams", new TeamsProvider());
        }
    }

    // ================================================================ quests

    private static final class Quests {
        static Field instance, teamName;
        static Method getData, get, parseCode, relProgress, isCompleted, isStarted, getTitle, getAllChapters, getQuests;

        static boolean init() {
            try {
                Class<?> sqf = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
                Class<?> qf = Class.forName("dev.ftb.mods.ftbquests.quest.QuestFile");
                Class<?> base = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
                Class<?> obj = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject");
                Class<?> team = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData");
                Class<?> chapter = Class.forName("dev.ftb.mods.ftbquests.quest.Chapter");
                instance = sqf.getField("INSTANCE");
                getData = qf.getMethod("getData", Entity.class);
                get = qf.getMethod("get", long.class);
                getAllChapters = qf.getMethod("getAllChapters");
                parseCode = base.getMethod("parseCodeString", String.class);
                getTitle = base.getMethod("getTitle");
                relProgress = team.getMethod("getRelativeProgress", obj);
                isCompleted = team.getMethod("isCompleted", obj);
                isStarted = team.getMethod("isStarted", obj);
                teamName = team.getField("name");
                getQuests = chapter.getMethod("getQuests");
                return true;
            } catch (Throwable t) {
                LOG.warn("FTB Quests found but its API didn't match; {ftbquests.*} placeholders disabled ({})", t.toString());
                return false;
            }
        }

        static Object file() throws Exception { return instance.get(null); }

        static Object object(Object file, String code) throws Exception {
            if (code == null || code.isBlank()) return null;
            long id = (long) parseCode.invoke(null, code.trim());
            return get.invoke(file, id);
        }
    }

    private static final class QuestsProvider implements Placeholders.PlaceholderProvider {
        private static final Map<String, String> KEYS = new LinkedHashMap<>();
        static {
            KEYS.put("progress:<id>", "Viewer's team progress % for a quest or chapter (id from the quest editor); empty id = all quests");
            KEYS.put("completed:<id>", "true / false");
            KEYS.put("started:<id>", "true / false");
            KEYS.put("title:<id>", "Quest or chapter title");
            KEYS.put("quests.completed", "Quests the viewer's team has completed (quests.total for all)");
            KEYS.put("chapters", "Number of chapters");
            KEYS.put("team", "Viewer's quest team name");
        }

        @Override public Map<String, String> keys() { return KEYS; }

        @Override
        public String resolve(String key, String args, Placeholders.PHContext ctx) {
            try {
                Object file = Quests.file();
                if (file == null) return "";
                String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
                if (k.equals("title")) {
                    Object o = Quests.object(file, args);
                    return o == null ? "" : ((Component) Quests.getTitle.invoke(o)).getString();
                }
                if (k.equals("chapters")) return Integer.toString(((Collection<?>) Quests.getAllChapters.invoke(file)).size());
                if (k.equals("quests.total")) return Integer.toString(allQuests(file).size());

                if (ctx == null || !(ctx.viewer() instanceof ServerPlayer sp)) return "";
                Object data = Quests.getData.invoke(file, sp);
                if (data == null) return "";
                return switch (k) {
                    case "team" -> String.valueOf(Quests.teamName.get(data));
                    case "quests.completed" -> {
                        int n = 0;
                        for (Object q : allQuests(file)) if ((boolean) Quests.isCompleted.invoke(data, q)) n++;
                        yield Integer.toString(n);
                    }
                    case "progress", "completed", "started" -> {
                        Object o = args == null || args.isBlank() ? file : Quests.object(file, args);
                        if (o == null) yield "";
                        Method m = k.equals("progress") ? Quests.relProgress : k.equals("completed") ? Quests.isCompleted : Quests.isStarted;
                        yield String.valueOf(m.invoke(data, o));
                    }
                    default -> "";
                };
            } catch (Throwable t) {
                return "";
            }
        }

        private static List<Object> allQuests(Object file) throws Exception {
            List<Object> out = new ArrayList<>();
            for (Object ch : (Collection<?>) Quests.getAllChapters.invoke(file)) out.addAll((Collection<?>) Quests.getQuests.invoke(ch));
            return out;
        }
    }

    // ================================================================ teams

    private static final class Teams {
        static Method getPlayerTeam, displayName, getId, getMembers, getOnline, getOwner, getColor;

        static boolean init() {
            try {
                Class<?> api = Class.forName("dev.ftb.mods.ftbteams.FTBTeamsAPI");
                Class<?> base = Class.forName("dev.ftb.mods.ftbteams.data.TeamBase");
                getPlayerTeam = api.getMethod("getPlayerTeam", ServerPlayer.class);
                displayName = base.getMethod("getDisplayName");
                getId = base.getMethod("getId");
                getMembers = base.getMethod("getMembers");
                getOnline = base.getMethod("getOnlineMembers");
                getOwner = base.getMethod("getOwner");
                getColor = base.getMethod("getColor");
                return true;
            } catch (Throwable t) {
                LOG.warn("FTB Teams found but its API didn't match; {ftbteams.*} placeholders disabled ({})", t.toString());
                return false;
            }
        }
    }

    private static final class TeamsProvider implements Placeholders.PlaceholderProvider {
        private static final Map<String, String> KEYS = new LinkedHashMap<>();
        static {
            KEYS.put("name", "Viewer's team name");
            KEYS.put("id", "Team id");
            KEYS.put("members", "Number of members (members.list for their names)");
            KEYS.put("online", "Members online now (online.list for their names)");
            KEYS.put("owner", "Team owner's name");
            KEYS.put("color", "Team colour as hex");
        }

        @Override public Map<String, String> keys() { return KEYS; }

        @Override
        public String resolve(String key, String args, Placeholders.PHContext ctx) {
            if (ctx == null || !(ctx.viewer() instanceof ServerPlayer sp)) return "";
            try {
                Object team = Teams.getPlayerTeam.invoke(null, sp);
                if (team == null) return "";
                return switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
                    case "name" -> String.valueOf(Teams.displayName.invoke(team));
                    case "id" -> String.valueOf(Teams.getId.invoke(team));
                    case "members" -> Integer.toString(((Collection<?>) Teams.getMembers.invoke(team)).size());
                    case "members.list" -> names(sp, (Collection<?>) Teams.getMembers.invoke(team));
                    case "online" -> Integer.toString(((Collection<?>) Teams.getOnline.invoke(team)).size());
                    case "online.list" -> {
                        List<String> n = new ArrayList<>();
                        for (Object p : (Collection<?>) Teams.getOnline.invoke(team)) n.add(((ServerPlayer) p).getGameProfile().getName());
                        yield String.join(", ", n);
                    }
                    case "owner" -> name(sp, (UUID) Teams.getOwner.invoke(team));
                    case "color" -> String.format("#%06X", ((int) Teams.getColor.invoke(team)) & 0xFFFFFF);
                    default -> "";
                };
            } catch (Throwable t) {
                return "";
            }
        }

        private static String names(ServerPlayer sp, Collection<?> uuids) {
            List<String> n = new ArrayList<>();
            for (Object u : uuids) n.add(name(sp, (UUID) u));
            return String.join(", ", n);
        }

        private static String name(ServerPlayer sp, UUID id) {
            if (id == null) return "";
            var cache = sp.getServer().getProfileCache();
            return cache == null ? id.toString() : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString());
        }
    }
}
