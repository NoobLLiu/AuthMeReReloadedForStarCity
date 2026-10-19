package fr.xephi.authme.service;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Handles migrations of the account schema. Each account stores a schema version; accounts
 * from before this system exists (v1 accounts) have no version and are upgraded silently on
 * their first successful login after this plugin version is installed.
 * <p>
 * To add a future migration (e.g. v2 to v3), increase {@link #TARGET_SCHEMA_VERSION} and
 * extend {@link #migrateIfNeeded} with the steps required to bring an account of an older
 * version up to date.
 * <p>
 * Since email addresses are optional and account switching is based on explicit account
 * bindings, upgrading a v1 account requires no interaction with the player: the schema
 * version is advanced and the UUID of the current connection is recorded.
 */
public class AccountMigrationService {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AccountMigrationService.class);

    /** The account schema version written by this plugin version. */
    public static final int TARGET_SCHEMA_VERSION = 2;

    @Inject
    private DataSource dataSource;

    AccountMigrationService() {
    }

    /**
     * Returns whether the given account still needs to be migrated to the current schema version.
     *
     * @param auth the account to check
     * @return true if the account requires a migration, false otherwise
     */
    public boolean isMigrationPending(PlayerAuth auth) {
        return auth.getSchemaVersion() == null || auth.getSchemaVersion() < TARGET_SCHEMA_VERSION;
    }

    /**
     * Upgrades the account to the current schema version if it is from an older one. The
     * upgrade happens silently: the player is not held back and no email address is required.
     * The UUID of the connection the player logs in with is recorded so the identity switch
     * feature can hand out the account's own UUID instead of a regenerated one.
     *
     * @param player the player logging in
     * @param auth the account being logged into
     */
    public void migrateIfNeeded(Player player, PlayerAuth auth) {
        if (!isMigrationPending(auth)) {
            return;
        }
        String name = player.getName().toLowerCase(Locale.ROOT);
        auth.setSchemaVersion(TARGET_SCHEMA_VERSION);
        if (!dataSource.updateSchemaVersion(auth)) {
            logger.warning("Failed to persist schema upgrade for '" + name + "'");
            return;
        }
        if (!dataSource.updateUuid(auth)) {
            logger.warning("Failed to save the UUID of the upgraded account '" + name + "'");
        }
        logger.info("Account '" + name + "' has been migrated to schema version " + TARGET_SCHEMA_VERSION);
    }
}
