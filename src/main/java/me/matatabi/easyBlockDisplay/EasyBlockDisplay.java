package me.matatabi.easyBlockDisplay;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;

import java.util.*;
import java.util.stream.Collectors;

public class EasyBlockDisplay extends JavaPlugin implements CommandExecutor, TabCompleter {

    private final Map<UUID, Stack<BlockDisplay>> undoMap = new HashMap<>();
    private final Map<UUID, Stack<StoredDisplay>> redoMap = new HashMap<>();

    // Redo用にデータを一時保存するクラス
    private record StoredDisplay(Location loc, Material mat, float scale, float rotation) {}

    @Override
    public void onEnable() {
        getCommand("ebd").setExecutor(this);
        getCommand("ebd").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) return false;

        String sub = args[0].toLowerCase();
        UUID uuid = player.getUniqueId();

        switch (sub) {
            case "add" -> handleAdd(player, args);
            case "undo" -> handleUndo(player, uuid);
            case "redo" -> handleRedo(player, uuid);
            default -> player.sendMessage("§c使用法: /ebd <add|undo|redo>");
        }
        return true;
    }

    private void handleAdd(Player player, String[] args) {
        if (args.length < 5) {
            player.sendMessage("§c使用法: /ebd add <x> <y> <z> <block> [size:1.0] [rot:0]");
            return;
        }

        try {
            // 1. 座標解析 (0.5足して中心へ)
            double x = Math.floor(parseCoord(player.getLocation().getX(), args[1])) + 0.5;
// Yも0.5足して「ブロックのど真ん中」を基準点にする
            double y = Math.floor(parseCoord(player.getLocation().getY(), args[2])) + 0.5;
            double z = Math.floor(parseCoord(player.getLocation().getZ(), args[3])) + 0.5;
            Location loc = new Location(player.getWorld(), x, y, z);

            // 2. ブロック解析
            Material mat = Material.matchMaterial(args[4]);
            if (mat == null || !mat.isBlock()) {
                player.sendMessage("§c無効なブロックです。");
                return;
            }

            // 3. オプション解析 (size:0.8 や rot:90 の形式)
            float scale = 1.0f;
            float rotation = 0.0f;
            for (int i = 5; i < args.length; i++) {
                String arg = args[i].toLowerCase();
                if (arg.startsWith("size:")) scale = Float.parseFloat(arg.split(":")[1]);
                if (arg.startsWith("rot:")) rotation = Float.parseFloat(arg.split(":")[1]);
            }

            spawnDisplay(player, loc, mat, scale, rotation);
            redoMap.remove(player.getUniqueId()); // 新しく置いたらRedo履歴は消す

        } catch (Exception e) {
            player.sendMessage("§c入力エラーが発生しました。");
        }
    }

    private void spawnDisplay(Player player, Location loc, Material mat, float scale, float rotation) {
        BlockDisplay bd = (BlockDisplay) loc.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        bd.setBlock(mat.createBlockData());

        Transformation t = bd.getTransformation();
        t.getScale().set(scale);
        float offset = -scale / 2.0f;
        t.getTranslation().set(offset, offset, offset);

        // 回転の設定 (Y軸回転)
        float radians = (float) Math.toRadians(rotation);
        t.getLeftRotation().set(new Quaternionf().rotateY(radians));

        bd.setTransformation(t);
        undoMap.computeIfAbsent(player.getUniqueId(), k -> new Stack<>()).push(bd);
    }

    private void handleUndo(Player player, UUID uuid) {
        Stack<BlockDisplay> stack = undoMap.get(uuid);
        if (stack == null || stack.isEmpty()) {
            player.sendMessage("§c戻せる履歴がありません。");
            return;
        }
        BlockDisplay last = stack.pop();
        // Redo用に保存
        StoredDisplay sd = new StoredDisplay(last.getLocation(), last.getBlock().getMaterial(),
                last.getTransformation().getScale().x, 0); // 回転の保存は簡易化
        redoMap.computeIfAbsent(uuid, k -> new Stack<>()).push(sd);

        last.remove();
        player.sendMessage("§eUndoを実行しました。");
    }

    private void handleRedo(Player player, UUID uuid) {
        Stack<StoredDisplay> stack = redoMap.get(uuid);
        if (stack == null || stack.isEmpty()) {
            player.sendMessage("§cやり直せる履歴がありません。");
            return;
        }
        StoredDisplay sd = stack.pop();
        spawnDisplay(player, sd.loc, sd.mat, sd.scale, sd.rotation);
        player.sendMessage("§bRedoを実行しました。");
    }

    private double parseCoord(double cur, String in) {
        return in.startsWith("~") ? cur + (in.length() > 1 ? Double.parseDouble(in.substring(1)) : 0) : Double.parseDouble(in);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("add", "undo", "redo");
        if (args[0].equalsIgnoreCase("add")) {
            if (args.length <= 4) return List.of("~");
            if (args.length == 5) return Arrays.stream(Material.values()).filter(Material::isBlock).map(m -> m.name().toLowerCase()).filter(n -> n.startsWith(args[4])).collect(Collectors.toList());
            if (args.length == 6) {
                String input = args[5]; // 今入力してる文字 (例: "size:0.")
                if (!input.startsWith("size:")) {
                    return List.of("size:1.0", "size:0.5", "size:2.0"); // ヒントとして出す
                }
                // ユーザーが打ち始めた文字をそのまま候補として返すことで、
                // 「size:0.7」など自由な数値を打っても消えなくなります
                return List.of(input);
            }
            if (args.length == 7) {
                String input = args[6];
                if (!input.startsWith("rot:")) {
                    return List.of("rot:0", "rot:90", "rot:180");
                }
                return List.of(input);
            }
        }
        return Collections.emptyList();
    }
}