package fr.xephi.authme.process.register.executors;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.SyncProcessManager;
import fr.xephi.authme.process.login.AsynchronousLogin;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.PendingRegistrationCache;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.settings.properties.RegistrationSettings;
import org.bukkit.entity.Player;

import javax.inject.Inject;

/**
 * Registration executor for accounts whose password is adopted from the email
 * address: the email is already bound to other accounts and all accounts sharing
 * an email share the same password (v2 rule). No new password is set; the
 * existing hash of the email is reused as is.
 */
class EmailAdoptRegisterExecutor implements RegistrationExecutor<EmailAdoptRegisterParams> {

    /**
     * Number of ticks to wait before running the login action when it is run synchronously.
     */
    private static final int SYNC_LOGIN_DELAY = 5;

    @Inject
    private CommonService commonService;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private SyncProcessManager syncProcessManager;

    @Inject
    private AsynchronousLogin asynchronousLogin;

    @Inject
    private PendingRegistrationCache pendingRegistrationCache;

    @Override
    public boolean isRegistrationAdmitted(EmailAdoptRegisterParams params) {
        // The password comes from the database (already valid), no password validation needed
        return true;
    }

    @Override
    public PlayerAuth buildPlayerAuth(EmailAdoptRegisterParams params) {
        return PlayerAuthBuilderHelper.createPlayerAuth(
            params.getPlayer(), params.getHashedPassword(), params.getEmail());
    }

    @Override
    public void executePostPersistAction(EmailAdoptRegisterParams params) {
        final Player player = params.getPlayer();

        // Inform the player that the email's existing password was reused
        commonService.send(player, MessageKey.REGISTER_PASSWORD_REUSED);

        if (!commonService.getProperty(RegistrationSettings.FORCE_LOGIN_AFTER_REGISTER)) {
            if (commonService.getProperty(PluginSettings.USE_ASYNC_TASKS)) {
                bukkitService.runTaskAsynchronously(() -> asynchronousLogin.forceLogin(player));
            } else {
                bukkitService.scheduleSyncDelayedTask(() -> asynchronousLogin.forceLogin(player), SYNC_LOGIN_DELAY);
            }
        }
        syncProcessManager.processSyncPasswordRegister(player, params.getEmail());
        // The account is persisted: the two-phase registration cache entry is no longer needed
        pendingRegistrationCache.remove(params.getPlayerName());
    }
}
