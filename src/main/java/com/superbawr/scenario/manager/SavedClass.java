package com.superbawr.scenario.manager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;

public class SavedClass {
    private final String roleId;
    private final ListTag inventory;

    public SavedClass(String roleId, ListTag inventory) {
        this.roleId = roleId;
        this.inventory = inventory;
    }

    public String getRoleId() {
        return roleId;
    }

    public ListTag getInventory() {
        return inventory;
    }

    public void applyTo(Player player) {
        player.getInventory().clearContent();
        player.getInventory().load(inventory);
    }
}
