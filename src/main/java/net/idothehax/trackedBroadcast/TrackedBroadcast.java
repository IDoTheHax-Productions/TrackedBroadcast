package net.idothehax.trackedBroadcast;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class TrackedBroadcast extends JavaPlugin implements TabExecutor {

    private Set<UUID> trackedPlayers = new HashSet<>();
    private FileConfiguration config;
    private int autoBroadcastTaskId = -1;

    @Override
    public void onEnable() {
        // Load config
        saveDefaultConfig();
        config = getConfig();
        loadTrackedPlayers();
        getCommand("track").setExecutor(this);
        getCommand("track").setTabCompleter(this);
        startAutoBroadcastTask();
    }

    @Override
    public void onDisable() {
        stopAutoBroadcastTask();
        saveTrackedPlayers();
    }

    // Persistence
    private void loadTrackedPlayers() {
        List<String> list = config.getStringList("trackedPlayers");
        trackedPlayers.clear();
        for (String s : list) {
            try {
                trackedPlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveTrackedPlayers() {
        List<String> list = trackedPlayers.stream().map(UUID::toString).collect(Collectors.toList());
        config.set("trackedPlayers", list);
        saveConfig();
    }

    // Autobroadcast scheduler
    private void startAutoBroadcastTask() {
        stopAutoBroadcastTask(); // prevent duplicates
        boolean enabled = config.getBoolean("autobroadcast.enabled", false);
        int interval = config.getInt("autobroadcast.interval_seconds", 60);
        if (enabled && interval > 0) {
            autoBroadcastTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    this,
                    this::broadcastTrackedLocations,
                    interval * 20L, // initial delay
                    interval * 20L  // repeat interval (20 ticks = 1 second)
            );
            getLogger().info("AutoBroadcast enabled, interval: " + interval + "s");
        }
    }

    private void stopAutoBroadcastTask() {
        if (autoBroadcastTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoBroadcastTaskId);
            autoBroadcastTaskId = -1;
        }
    }

    // Command handler
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("track.manage")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "add":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /track add <player>");
                    return true;
                }
                Player addTarget = Bukkit.getPlayer(args[1]);
                if (addTarget == null) {
                    sender.sendMessage("§cPlayer not found.");
                    return true;
                }
                if (trackedPlayers.add(addTarget.getUniqueId())) {
                    saveTrackedPlayers();
                    sender.sendMessage("§aAdded " + addTarget.getName() + " to tracked players.");
                } else {
                    sender.sendMessage("§ePlayer already tracked.");
                }
                return true;
            case "remove":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /track remove <player>");
                    return true;
                }
                Player removeTarget = Bukkit.getPlayer(args[1]);
                UUID removeUuid = removeTarget != null ? removeTarget.getUniqueId() : null;
                if (removeUuid == null) {
                    // try offline UUID
                    try {
                        removeUuid = UUID.fromString(args[1]);
                    } catch (Exception ignored) {}
                }
                if (removeUuid == null || !trackedPlayers.contains(removeUuid)) {
                    sender.sendMessage("§cPlayer is not tracked.");
                    return true;
                }
                trackedPlayers.remove(removeUuid);
                saveTrackedPlayers();
                sender.sendMessage("§aRemoved player from tracked players.");
                return true;
            case "list":
                if (trackedPlayers.isEmpty()) {
                    sender.sendMessage("§eNo players tracked.");
                } else {
                    String msg = trackedPlayers.stream()
                            .map(uuid -> {
                                Player p = Bukkit.getPlayer(uuid);
                                return p != null ? p.getName() : uuid.toString();
                            })
                            .collect(Collectors.joining(", "));
                    sender.sendMessage("§bTracked players: §f" + msg);
                }
                return true;
            case "broadcast":
                broadcastTrackedLocations();
                sender.sendMessage("§aBroadcasted tracked player locations.");
                return true;
            case "autobroadcast":
                handleAutoBroadcastCommand(sender, args);
                return true;
            default:
                sendHelp(sender);
                return true;
        }
    }

    private void handleAutoBroadcastCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /track autobroadcast <on|off|interval> [seconds]");
            return;
        }
        if ("on".equalsIgnoreCase(args[1])) {
            config.set("autobroadcast.enabled", true);
            saveConfig();
            startAutoBroadcastTask();
            sender.sendMessage("§aAutobroadcast enabled.");
        } else if ("off".equalsIgnoreCase(args[1])) {
            config.set("autobroadcast.enabled", false);
            saveConfig();
            stopAutoBroadcastTask();
            sender.sendMessage("§cAutobroadcast disabled.");
        } else if ("interval".equalsIgnoreCase(args[1]) && args.length >= 3) {
            try {
                int sec = Integer.parseInt(args[2]);
                if (sec < 5) sec = 5; // minimum 5 seconds to prevent spam
                config.set("autobroadcast.interval_seconds", sec);
                saveConfig();
                startAutoBroadcastTask();
                sender.sendMessage("§aAutobroadcast interval set to " + sec + " seconds.");
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cInvalid interval.");
            }
        } else {
            sender.sendMessage("§eUsage: /track autobroadcast <on|off|interval> [seconds]");
        }
    }

    // Broadcast logic
    public void broadcastTrackedLocations() {
        String format = config.getString("broadcastFormat", "[Tracked]\n%players%");
        String playerFormat = config.getString("playerListFormat", "§a- %name%§r at §e%world%§r: §b%x%§r, %y%, %z%");
        boolean showY = config.getBoolean("show_y_level", true);
        boolean asList = config.getBoolean("broadcastList", true);

        List<String> playerMsgs = new ArrayList<>();
        for (UUID uuid : trackedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                World w = p.getWorld();
                String msg = playerFormat
                        .replace("%name%", p.getName())
                        .replace("%world%", w.getName())
                        .replace("%x%", String.valueOf(p.getLocation().getBlockX()))
                        .replace("%z%", String.valueOf(p.getLocation().getBlockZ()));
                if (showY) {
                    msg = msg.replace("%y%", String.valueOf(p.getLocation().getBlockY()));
                } else {
                    msg = msg.replaceAll(",? ?%y%,? ?", "");
                }
                playerMsgs.add(msg);
            }
        }
        String all;
        if (asList) {
            all = String.join("\n", playerMsgs);
        } else {
            all = String.join(" | ", playerMsgs);
        }
        String result = format.replace("%players%", all);
        if (!playerMsgs.isEmpty()) {
            Bukkit.broadcastMessage(result);
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§6/track add <player> §7- Add player to tracked group");
        s.sendMessage("§6/track remove <player> §7- Remove player from tracked group");
        s.sendMessage("§6/track list §7- List tracked players");
        s.sendMessage("§6/track broadcast §7- Broadcast locations");
        s.sendMessage("§6/track autobroadcast <on|off|interval> [seconds] §7- Autobroadcast control");
    }

    // Tab completion for ease of use
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("add", "remove", "list", "broadcast", "autobroadcast"));
            return out.stream().filter(s -> s.startsWith(args[0])).collect(Collectors.toList());
        }
        if (args.length == 2) {
            if ("add".equalsIgnoreCase(args[0]) || "remove".equalsIgnoreCase(args[0])) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(s -> s.startsWith(args[1])).collect(Collectors.toList());
            }
            if ("autobroadcast".equalsIgnoreCase(args[0])) {
                return Arrays.asList("on", "off", "interval").stream()
                        .filter(s -> s.startsWith(args[1])).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }
}