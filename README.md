# Vault Survival Plugin

## Overview
This plugin enhances the survival gameplay experience on your Minecraft server. It provides essential commands for managing player health, hunger, and overall status.

## Features Implemented

### 1. Heal Command
- **Command:** `/heal`
- **Permission:** `vaultsurvival.heal`
- **Description:** Restores full health to the player who executes the command
- **Usage:**
  - `/heal` - Heals the executing player
  - `/heal all` - Heals all online players (requires admin permission)

### 2. Hunger Command
- **Command:** `/hunger [amount]`
- **Permission:** `vaultsurvival.hunger`
- **Description:** Modifies hunger level for the executing player
- **Usage:**
  - `/hunger` - Adds 10 hunger points (default)
  - `/hunger 5` - Adds 5 hunger points
  - `/hunger -5` - Removes 5 hunger points

### 3. Status Command
- **Command:** `/status`
- **Permission:** `vaultsurvival.status`
- **Description:** Displays the current health and hunger levels of the player
- **Usage:**
  - `/status` - Shows current status information

## Installation Instructions

1. Build the plugin using Maven:
   ```
   mvn clean package
   ```

2. Place the generated `.jar` file in your server's `plugins` directory

3. Restart or reload your Minecraft server:
   ```
   /reload
   ```

4. The plugin will automatically enable and register commands

## Permissions
- `vaultsurvival.heal` - Access to heal command
- `vaultsurvival.hunger` - Access to hunger command  
- `vaultsurvival.status` - Access to status command

## Testing Instructions

1. Join your Minecraft server with a player
2. Use the commands listed above to test functionality:
   - `/status` - Verify current health and hunger values
   - `/heal` - Confirm health is restored to full
   - `/hunger` - Confirm hunger increases appropriately
   - `/hunger -5` - Confirm hunger decreases appropriately

## Troubleshooting

If commands are not working:
1. Check server console for error messages
2. Ensure the plugin is properly installed in the plugins folder
3. Verify you have the necessary permissions to use the commands
4. Check that your server version is compatible (requires Spigot 1.19+)

## Future Development

This initial version provides core survival features. Further development could include:
- Crafting systems
- Resource tracking
- Weather control
- Custom items and blocks
- Achievement system