package fr.xephi.authme.identity;

import java.util.UUID;

/**
 * Snapshot of a pending identity switch, created when a player confirms the switch in the
 * identity menu and consumed when the player reconnects within the expiry window.
 */
public class PendingSwitch {

    /** Lowercase name of the account that initiated the switch. */
    private final String sourceName;
    /** Name of the target account, with the casing stored in the database. */
    private final String targetRealName;
    /** UUID the reconnecting player should be given. */
    private final UUID targetUuid;
    /** Address the switch was initiated from; the rewrite only applies to the same address. */
    private final String ip;
    /** Whether the target account is a Bedrock (Floodgate) account. */
    private final boolean bedrockTarget;
    /** Floodgate UUID of the Bedrock player who initiated the switch; null for Java sources. */
    private final UUID bedrockSourceId;

    /**
     * Constructor.
     *
     * @param sourceName lowercase name of the account initiating the switch
     * @param targetRealName name of the target account with database casing
     * @param targetUuid UUID to give the player upon reconnection
     * @param ip the IP address the switch was initiated from
     * @param bedrockTarget whether the target account is a Bedrock (Floodgate) account
     * @param bedrockSourceId the Floodgate UUID of the Bedrock player who initiated the
     *        switch, or null if the switch was initiated by a Java player
     */
    public PendingSwitch(String sourceName, String targetRealName, UUID targetUuid, String ip,
                         boolean bedrockTarget, UUID bedrockSourceId) {
        this.sourceName = sourceName;
        this.targetRealName = targetRealName;
        this.targetUuid = targetUuid;
        this.ip = ip;
        this.bedrockTarget = bedrockTarget;
        this.bedrockSourceId = bedrockSourceId;
    }

    /**
     * @return the lowercase name of the account that initiated the switch
     */
    public String getSourceName() {
        return sourceName;
    }

    /**
     * @return the name of the target account with database casing
     */
    public String getTargetRealName() {
        return targetRealName;
    }

    /**
     * @return the lowercase name of the target account
     */
    public String getTargetName() {
        return targetRealName.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return the UUID the reconnecting player should be given
     */
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /**
     * @return the IP address the switch was initiated from
     */
    public String getIp() {
        return ip;
    }

    /**
     * @return true if the target account is a Bedrock (Floodgate) account
     */
    public boolean isBedrockTarget() {
        return bedrockTarget;
    }

    /**
     * @return the Floodgate UUID of the Bedrock player who initiated the switch, or null
     *         if the switch was initiated by a Java player
     */
    public UUID getBedrockSourceId() {
        return bedrockSourceId;
    }

    /**
     * @return true if the switch was initiated by a Bedrock (Floodgate) player
     */
    public boolean isBedrockSource() {
        return bedrockSourceId != null;
    }
}
