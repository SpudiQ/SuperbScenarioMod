package com.superbawr.scenario.manager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ScenarioManager {
    private GameState state = GameState.LOBBY;
    
    private final Map<UUID, Team> playerTeams = new HashMap<>();
    private final Map<UUID, Role> playerRoles = new HashMap<>();
    private final Map<UUID, Integer> playerLives = new HashMap<>();
    
    // NBT structure: list of compounds { id: string, inventory: list }
    private final Map<String, SavedClass> savedClasses = new HashMap<>();
    
    // Block snapshots for world rollback
    private final Map<BlockPos, BlockSnapshot> blockSnapshots = new LinkedHashMap<>();
    
    private BlockPos usBase = null;
    private BlockPos ruBase = null;
    private BlockPos lobbyPos = null;
    private BlockPos zoneCenter = null;
    private BlockPos intelPos = null;
    private int zoneRadius = 20;

    private int usPoints = 0;
    private int ruPoints = 0;
    private int roundTimeSeconds = 0;
    private int remainingTimeSeconds = 0;
    
    private int countdown = 0;

    private MinecraftServer server;

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public void setBase(Team team, BlockPos pos) {
        if (team == Team.US) usBase = pos;
        else if (team == Team.RU) ruBase = pos;
    }

    public BlockPos getBase(Team team) {
        return team == Team.US ? usBase : ruBase;
    }

    public void setZone(BlockPos center, int radius) {
        this.zoneCenter = center;
        this.zoneRadius = radius;
    }

    public void setLobbyPos(BlockPos pos) {
        this.lobbyPos = pos;
    }

    public BlockPos getLobbyPos() {
        return lobbyPos;
    }

    public BlockPos getZoneCenter() {
        return zoneCenter;
    }

    public void setIntelPos(BlockPos pos) {
        this.intelPos = pos;
    }

    public BlockPos getIntelPos() {
        return intelPos;
    }

    public int getZoneRadius() {
        return zoneRadius;
    }

    public Team getTeam(UUID uuid) {
        return playerTeams.getOrDefault(uuid, Team.NONE);
    }

    public void setTeam(UUID uuid, Team team) {
        playerTeams.put(uuid, team);
    }

    public Role getRole(UUID uuid) {
        return playerRoles.get(uuid);
    }

    public void setRole(UUID uuid, Role role) {
        playerRoles.put(uuid, role);
    }

    public int getLives(UUID uuid) {
        return playerLives.getOrDefault(uuid, 3);
    }

    public void setLives(UUID uuid, int lives) {
        playerLives.put(uuid, lives);
    }

    public void decrementLives(UUID uuid) {
        playerLives.put(uuid, Math.max(0, getLives(uuid) - 1));
    }

    public void addPoints(Team team, int pts) {
        if (team == Team.US) usPoints += pts;
        else if (team == Team.RU) ruPoints += pts;
    }

    public int getPoints(Team team) {
        return team == Team.US ? usPoints : ruPoints;
    }

    public boolean hasSavedClass(Team team, String roleId) {
        return savedClasses.containsKey(team.name() + "_" + roleId.toLowerCase());
    }

    public SavedClass getSavedClass(Team team, String roleId) {
        return savedClasses.get(team.name() + "_" + roleId.toLowerCase());
    }

    public void saveClass(Team team, String roleId, ListTag inventory) {
        String key = team.name() + "_" + roleId.toLowerCase();
        savedClasses.put(key, new SavedClass(key, inventory));
        flushClasses();
    }

    public void startRound(int minutes) {
        this.roundTimeSeconds = minutes * 60;
        this.remainingTimeSeconds = this.roundTimeSeconds;
        this.usPoints = 0;
        this.ruPoints = 0;
        this.countdown = 10;
        this.state = GameState.STARTING;
        snapshotRegion(); // Save entire play area before anything changes
        distributeTeamsAndRoles();
    }

    public void stopRound() {
        this.state = GameState.FINISHED;
        restoreWorld();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("§c[Scenario] Round forcefully stopped."), false);
        }
    }

    public int getCountdown() {
        return countdown;
    }

    public void decrementCountdown() {
        this.countdown--;
    }

    public int getRemainingTimeSeconds() {
        return remainingTimeSeconds;
    }

    public void decrementRemainingTime() {
        this.remainingTimeSeconds--;
    }

    private File getConfigFile() {
        if (server == null) return null;
        File dir = server.getWorldPath(LevelResource.ROOT).toFile();
        return new File(dir, "superb_classes.dat");
    }

    private File getPositionsFile() {
        if (server == null) return null;
        File dir = server.getWorldPath(LevelResource.ROOT).toFile();
        return new File(dir, "superb_positions.dat");
    }

    public void loadClasses() {
        File file = getConfigFile();
        if (file == null || !file.exists()) return;

        try {
            CompoundTag tag = NbtIo.readCompressed(file);
            if (tag != null && tag.contains("Classes")) {
                savedClasses.clear();
                ListTag list = tag.getList("Classes", 10);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag c = list.getCompound(i);
                    String id = c.getString("role");
                    ListTag inv = c.getList("inventory", 10);
                    savedClasses.put(id, new SavedClass(id, inv));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void flushClasses() {
        File file = getConfigFile();
        if (file == null) return;
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (SavedClass sc : savedClasses.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("role", sc.getRoleId());
            c.put("inventory", sc.getInventory());
            list.add(c);
        }
        tag.put("Classes", list);

        try {
            NbtIo.writeCompressed(tag, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===================== Position Persistence =====================

    public void loadPositions() {
        File file = getPositionsFile();
        if (file == null || !file.exists()) return;

        try {
            CompoundTag tag = NbtIo.readCompressed(file);
            if (tag.contains("usBase")) usBase = readBlockPos(tag.getCompound("usBase"));
            if (tag.contains("ruBase")) ruBase = readBlockPos(tag.getCompound("ruBase"));
            if (tag.contains("lobbyPos")) lobbyPos = readBlockPos(tag.getCompound("lobbyPos"));
            if (tag.contains("zoneCenter")) zoneCenter = readBlockPos(tag.getCompound("zoneCenter"));
            if (tag.contains("intelPos")) intelPos = readBlockPos(tag.getCompound("intelPos"));
            if (tag.contains("zoneRadius")) zoneRadius = tag.getInt("zoneRadius");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void savePositions() {
        File file = getPositionsFile();
        if (file == null) return;

        CompoundTag tag = new CompoundTag();
        if (usBase != null) tag.put("usBase", writeBlockPos(usBase));
        if (ruBase != null) tag.put("ruBase", writeBlockPos(ruBase));
        if (lobbyPos != null) tag.put("lobbyPos", writeBlockPos(lobbyPos));
        if (zoneCenter != null) tag.put("zoneCenter", writeBlockPos(zoneCenter));
        if (intelPos != null) tag.put("intelPos", writeBlockPos(intelPos));
        tag.putInt("zoneRadius", zoneRadius);

        try {
            NbtIo.writeCompressed(tag, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private CompoundTag writeBlockPos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    private BlockPos readBlockPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    // ===================== World Rollback =====================

    private int snapshotRadius = 150;
    private int snapMinX, snapMaxX, snapMinY, snapMaxY, snapMinZ, snapMaxZ;
    private boolean hasSnapshot = false;

    private String missionTitle = "OPERATION DAWNBREAKER";
    private String missionDate = "April 14, 2026 - 06:00 hrs";

    public int getSnapshotRadius() { return snapshotRadius; }
    public void setSnapshotRadius(int radius) { this.snapshotRadius = radius; }
    public void setMissionTitle(String title) { this.missionTitle = title; }
    public String getMissionTitle() { return missionTitle; }
    public void setMissionDate(String date) { this.missionDate = date; }
    public String getMissionDate() { return missionDate; }

    public void snapshotRegion() {
        if (server == null) return;
        blockSnapshots.clear();
        hasSnapshot = false;
        ServerLevel level = server.overworld();

        BlockPos center = zoneCenter;
        if (center == null) {
            if (usBase != null && ruBase != null) {
                center = new BlockPos(
                    (usBase.getX() + ruBase.getX()) / 2,
                    (usBase.getY() + ruBase.getY()) / 2,
                    (usBase.getZ() + ruBase.getZ()) / 2
                );
            } else if (usBase != null) {
                center = usBase;
            } else if (ruBase != null) {
                center = ruBase;
            } else {
                return;
            }
        }

        snapMinX = center.getX() - snapshotRadius;
        snapMaxX = center.getX() + snapshotRadius;
        snapMinZ = center.getZ() - snapshotRadius;
        snapMaxZ = center.getZ() + snapshotRadius;
        snapMinY = Math.max(level.getMinBuildHeight(), center.getY() - 50);
        snapMaxY = Math.min(level.getMaxBuildHeight(), center.getY() + 50);

        int saved = 0;
        int total = 0;
        for (int x = snapMinX; x <= snapMaxX; x++) {
            for (int z = snapMinZ; z <= snapMaxZ; z++) {
                for (int y = snapMinY; y <= snapMaxY; y++) {
                    total++;
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState bs = level.getBlockState(pos);
                    if (!bs.isAir()) {
                        CompoundTag beData = null;
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) beData = be.saveWithoutMetadata();
                        blockSnapshots.put(pos, new BlockSnapshot(pos, bs, beData));
                        saved++;
                    }
                }
            }
        }

        hasSnapshot = true;
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("§e[Scenario] Snapshot: " + saved + " solid blocks saved (skipped " + (total - saved) + " air). Radius: " + snapshotRadius), false
        );
    }

    public void restoreWorld() {
        if (server == null || !hasSnapshot) return;
        ServerLevel level = server.overworld();
        int count = 0;
        Set<net.minecraft.world.level.ChunkPos> affectedChunks = new HashSet<>();

        for (int x = snapMinX; x <= snapMaxX; x++) {
            for (int z = snapMinZ; z <= snapMaxZ; z++) {
                for (int y = snapMinY; y <= snapMaxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState current = level.getBlockState(pos);
                    BlockSnapshot snapshot = blockSnapshots.get(pos);

                    if (snapshot != null) {
                        if (!current.equals(snapshot.getOriginalState())) {
                            level.setBlock(pos, snapshot.getOriginalState(), 3);
                            if (snapshot.getBlockEntityData() != null) {
                                BlockEntity be = level.getBlockEntity(pos);
                                if (be != null) {
                                    be.load(snapshot.getBlockEntityData());
                                    be.setChanged();
                                }
                            }
                            affectedChunks.add(new net.minecraft.world.level.ChunkPos(pos));
                            count++;
                        }
                    } else {
                        if (!current.isAir()) {
                            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                            affectedChunks.add(new net.minecraft.world.level.ChunkPos(pos));
                            count++;
                        }
                    }
                }
            }
        }

        for (net.minecraft.world.level.ChunkPos chunkPos : affectedChunks) {
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), null, null
                ));
            }
        }

        blockSnapshots.clear();
        hasSnapshot = false;
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("§a[Scenario] World restored. " + count + " blocks reverted."), false
        );
    }

    public void clearSnapshots() {
        blockSnapshots.clear();
    }

    public ServerPlayer getTeamLeader(Team team) {
        if (server == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (getTeam(p.getUUID()) == team && getRole(p.getUUID()) == Role.LEADER) {
                return p;
            }
        }
        return null;
    }

    public void distributeTeamsAndRoles() {
        if (server == null) return;
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        Collections.shuffle(players);
        
        List<ServerPlayer> usPlayers = new ArrayList<>();
        List<ServerPlayer> ruPlayers = new ArrayList<>();

        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            Team t = getTeam(p.getUUID()); // check if manual override exists
            if (t == Team.NONE) {
                if (usPlayers.size() <= ruPlayers.size()) {
                    usPlayers.add(p);
                    setTeam(p.getUUID(), Team.US);
                } else {
                    ruPlayers.add(p);
                    setTeam(p.getUUID(), Team.RU);
                }
            } else {
                if (t == Team.US) usPlayers.add(p);
                if (t == Team.RU) ruPlayers.add(p);
            }
        }

        assignRolesForTeam(usPlayers);
        assignRolesForTeam(ruPlayers);
    }

    private void assignRolesForTeam(List<ServerPlayer> teamPlayers) {
        int sz = teamPlayers.size();
        
        // Count already manual assigned
        boolean hasLeader = false;
        Map<Role, Integer> counts = new EnumMap<>(Role.class);
        for (Role r : Role.values()) counts.put(r, 0);

        List<ServerPlayer> unassigned = new ArrayList<>();

        for (ServerPlayer p : teamPlayers) {
            Role r = getRole(p.getUUID());
            if (r != null) {
                if (r == Role.LEADER) hasLeader = true;
                counts.put(r, counts.get(r) + 1);
            } else {
                unassigned.add(p);
            }
        }

        Collections.shuffle(unassigned);

        // Required roles
        if (!hasLeader && !unassigned.isEmpty()) {
            ServerPlayer p = unassigned.remove(0);
            setRole(p.getUUID(), Role.LEADER);
            counts.put(Role.LEADER, counts.get(Role.LEADER) + 1);
        }

        // Fill remaining roles randomly up to limits
        Role[] roles = Role.values();
        for (ServerPlayer p : unassigned) {
            boolean assigned = false;
            // try random roles
            List<Role> shuffledRoles = new ArrayList<>(Arrays.asList(roles));
            Collections.shuffle(shuffledRoles);

            for (Role r : shuffledRoles) {
                if (r == Role.LEADER || !r.isAutoGenerate()) continue; // leader already set, skip non-auto
                if (counts.get(r) < r.getMaxAllowed(sz)) {
                    setRole(p.getUUID(), r);
                    counts.put(r, counts.get(r) + 1);
                    assigned = true;
                    break;
                }
            }
            if (!assigned) {
                setRole(p.getUUID(), Role.RIFLEMAN);
            }
        }
    }
}
