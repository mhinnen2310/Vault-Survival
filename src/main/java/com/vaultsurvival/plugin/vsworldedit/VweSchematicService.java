package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.List;

/** Safe access to vanilla structure files in the plugin-owned schematics folder. */
public interface VweSchematicService {

    record SchematicInfo(String fileName, int width, int height, int length,
                         int blockCount, int entityCount, long fileBytes) {
        public long volume() { return (long) width * height * length; }
    }

    record AvailableFile(String fileName, long fileBytes) { }

    record Result(boolean success, String message, SchematicInfo info, boolean pendingConfirmation) {
        public static Result failure(String message) {
            return new Result(false, message, null, false);
        }
    }

    /** Dedicated folder: plugins/VaultSurvival/schematics. */
    Path getDirectory();

    /** List safe top-level .nbt files without following symbolic links. */
    List<AvailableFile> list();

    /** Validate and inspect a vanilla structure without changing the world. */
    Result inspect(String fileName);

    /** Inspect and display its target bounds at the player's current block. */
    Result preview(Player player, String fileName);

    /** Load and submit it to the existing VWE batched confirmation/undo engine. */
    Result paste(Player player, String fileName);
}
