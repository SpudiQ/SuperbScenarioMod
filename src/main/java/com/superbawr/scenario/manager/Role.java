package com.superbawr.scenario.manager;

public enum Role {
    LEADER("leader", 1, 1, true),
    MEDIC("medic", 2, 2, true),
    TANDEM("tandem", 1, 9, true),
    SAPPER("sapper", 1, 1, true),
    DRIVER("driver", 2, 2, false),
    RIFLEMAN("rifleman", 999, 1, true);

    private final String id;
    private final int maxPerTeam;
    private final int perPlayers;
    private final boolean autoGenerate;

    Role(String id, int maxPerTeam, int perPlayers, boolean autoGenerate) {
        this.id = id;
        this.maxPerTeam = maxPerTeam;
        this.perPlayers = perPlayers;
        this.autoGenerate = autoGenerate;
    }

    public boolean isAutoGenerate() {
        return autoGenerate;
    }

    public String getId() {
        return id;
    }

    public int getMaxAllowed(int teamSize) {
        if (this == RIFLEMAN) return 999;
        if (this == TANDEM) {
            int allowed = teamSize / perPlayers;
            return Math.min(Math.max(1, allowed), maxPerTeam);
        }
        return maxPerTeam;
    }

    public static Role fromId(String id) {
        for (Role r : values()) {
            if (r.id.equalsIgnoreCase(id)) return r;
        }
        return RIFLEMAN;
    }
}
