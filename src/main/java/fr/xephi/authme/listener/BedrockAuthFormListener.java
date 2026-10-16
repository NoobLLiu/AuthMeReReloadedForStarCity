package fr.xephi.authme.listener;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.HooksSettings;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import javax.inject.Inject;
import java.util.Locale;
import java.util.Objects;

/**
 * Opens a native Bedrock GUI form (register / login) for Bedrock players connected through
 * Geyser + Floodgate who join without being authenticated. Players who are reconnecting
 * because of an identity switch (/lg) are excluded: their session is handled by the
 * switch's auto login instead.
 * <p>
 * Registered players get a login form; unregistered players get a register form with a
 * password confirmation field. Failed attempts re-open the form so the player can retry;
 * closing the form falls back to the regular chat prompts of AuthMe.
 */
public class BedrockAuthFormListener implements Listener {

    /** Delay before the form is opened, letting the join-time auth listeners run first. */
    private static final long OPEN_DELAY_TICKS = 40L;

    /** Delay after a submit before the form is offered again while still unauthenticated. */
    private static final long RESHOW_DELAY_TICKS = 30L;

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(BedrockAuthFormListener.class);
    private final AuthMeApi authmeApi = AuthMeApi.getInstance();
    private final Settings settings;
    private final Messages messages;
    private final BukkitService bukkitService;
    private final Management management;
    private final ValidationService validationService;
    private final IdentitySwitchManager identitySwitchManager;

    @Inject
    BedrockAuthFormListener(Settings settings, Messages messages, BukkitService bukkitService,
                            Management management, ValidationService validationService,
                            IdentitySwitchManager identitySwitchManager) {
        this.settings = settings;
        this.messages = messages;
        this.bukkitService = bukkitService;
        this.management = management;
        this.validationService = validationService;
        this.identitySwitchManager = identitySwitchManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        bukkitService.runTaskLater(player, () -> {
            if (isFormRequired(player)) {
                openForm(player);
            }
        }, OPEN_DELAY_TICKS);
    }

    /**
     * Returns whether the Bedrock auth form should be shown: the feature is enabled, the
     * player is a Bedrock player, is not authenticated and is not in the identity switch
     * flow. A still-unconsumed auto-login grant of the switch flow is claimed here, which
     * covers grants recorded after the dedicated auto-login listener already ran.
     *
     * @param player the player who joined
     * @return true if the form should be opened
     */
    private boolean isFormRequired(Player player) {
        if (!player.isOnline() || authmeApi.isAuthenticated(player)) {
            return false;
        }
        if (!settings.getProperty(HooksSettings.HOOK_FLOODGATE_PLAYER)
            || !settings.getProperty(HooksSettings.BEDROCK_AUTH_FORM)) {
            return false;
        }
        if (!IdentitySwitchManager.isBedrockPlayer(player)) {
            return false;
        }

        String nameLower = player.getName().toLowerCase(Locale.ROOT);
        String ip = getPlayerIp(player);

        // The identity switch flow granted an auto login for this reconnect: consume it here
        // when the dedicated listener did not see it yet (listener scheduling order)
        if (identitySwitchManager.consumeAutoLogin(nameLower, ip)) {
            authmeApi.forceLogin(player, true);
            messages.send(player, MessageKey.IDENTITY_SWITCHED_AUTO_LOGGED_IN);
            return false;
        }
        // A switch is pending for this player (as source or as target): the identity switch
        // flow owns this session, so no form is opened
        if (identitySwitchManager.getPendingSwitchByTarget(nameLower) != null
            || identitySwitchManager.getPendingSwitch(nameLower) != null) {
            logger.info(String.format("Not opening the Bedrock auth form for '%s': an identity "
                + "switch is pending", player.getName()));
            return false;
        }
        return true;
    }

    /**
     * Opens the auth form for the given player: the login form for a registered account,
     * the register form otherwise.
     *
     * @param player the player to open the form for
     */
    private void openForm(Player player) {
        if (authmeApi.isRegistered(player.getName())) {
            sendLoginForm(player);
        } else {
            sendRegisterForm(player);
        }
    }

    /**
     * Builds and sends the login form with a single password input.
     *
     * @param player the player to send the form to
     */
    private void sendLoginForm(Player player) {
        try {
            CustomForm form = CustomForm.builder()
                .title(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_LOGIN_TITLE))
                .label(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_LOGIN_LABEL))
                .input(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_PASSWORD_INPUT), "")
                .validResultHandler(response -> handleLoginResponse(player, response.asInput(0)))
                .closedResultHandler(() -> { })
                .build();
            deliverForm(player, form);
        } catch (NoClassDefFoundError | Exception e) {
            // Floodgate or Cumulus classes unavailable or the form could not be built:
            // the regular chat prompts still guide the player
            logger.warning("Could not build the Bedrock login form: " + e.getMessage());
        }
    }

    /**
     * Builds and sends the register form with a password input and a confirmation input.
     *
     * @param player the player to send the form to
     */
    private void sendRegisterForm(Player player) {
        try {
            CustomForm form = CustomForm.builder()
                .title(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_REGISTER_TITLE))
                .label(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_REGISTER_LABEL))
                .input(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_PASSWORD_INPUT), "")
                .input(messages.retrieveSingle(player, MessageKey.BEDROCK_AUTH_PASSWORD_CONFIRM_INPUT), "")
                .validResultHandler(response ->
                    handleRegisterResponse(player, response.asInput(0), response.asInput(1)))
                .closedResultHandler(() -> { })
                .build();
            deliverForm(player, form);
        } catch (NoClassDefFoundError | Exception e) {
            logger.warning("Could not build the Bedrock register form: " + e.getMessage());
        }
    }

    /**
     * Delivers a built form to the given player via Floodgate.
     *
     * @param player the player to deliver the form to
     * @param form the form to send
     */
    private void deliverForm(Player player, CustomForm form) {
        FloodgatePlayer fgPlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (fgPlayer == null || !fgPlayer.sendForm(form)) {
            logger.warning(String.format("Could not deliver the Bedrock auth form to '%s'",
                player.getName()));
        }
    }

    /**
     * Handles a login form response: performs the login through the regular login process.
     * Runs on the main thread, as Floodgate invokes the handler on a network thread.
     *
     * @param player the player who responded
     * @param password the entered password
     */
    private void handleLoginResponse(Player player, String password) {
        bukkitService.runTask(player, () -> {
            if (!player.isOnline() || authmeApi.isAuthenticated(player)) {
                return;
            }
            if (password == null || password.isEmpty()) {
                messages.send(player, MessageKey.BEDROCK_AUTH_PASSWORD_EMPTY);
            } else {
                // The login process reports a wrong password itself
                management.performLogin(player, password);
            }
            scheduleReshow(player);
        });
    }

    /**
     * Handles a register form response: checks the two passwords, validates the password
     * and registers the player through the regular registration process (which also logs
     * the player in afterwards).
     *
     * @param player the player who responded
     * @param password the entered password
     * @param confirm the entered password confirmation
     */
    private void handleRegisterResponse(Player player, String password, String confirm) {
        bukkitService.runTask(player, () -> {
            if (!player.isOnline() || authmeApi.isAuthenticated(player)) {
                return;
            }
            if (authmeApi.isRegistered(player.getName())) {
                // Registered meanwhile, e.g. by an admin: offer the login form instead
                openForm(player);
                return;
            }
            if (password == null || password.isEmpty() || !Objects.equals(password, confirm)) {
                messages.send(player, MessageKey.BEDROCK_AUTH_PASSWORD_MISMATCH);
                sendRegisterForm(player);
                return;
            }
            ValidationService.ValidationResult result =
                validationService.validatePassword(password, player.getName());
            if (result.hasError()) {
                messages.send(player, result.getMessageKey(), result.getArgs());
                sendRegisterForm(player);
                return;
            }
            authmeApi.forceRegister(player, password, true);
            scheduleReshow(player);
        });
    }

    /**
     * Offers the form again after a short delay when the player is still not authenticated,
     * e.g. after a failed login attempt. Skipped automatically once the player is logged in,
     * has left the server or entered the identity switch flow.
     *
     * @param player the player to offer the form to again
     */
    private void scheduleReshow(Player player) {
        bukkitService.runTaskLater(player, () -> {
            if (isFormRequired(player)) {
                openForm(player);
            }
        }, RESHOW_DELAY_TICKS);
    }

    /**
     * Returns the IP address the given player is connected from.
     *
     * @param player the player to check
     * @return the IP address, or null if unavailable
     */
    private static String getPlayerIp(Player player) {
        return player.getAddress() == null || player.getAddress().getAddress() == null
            ? null
            : player.getAddress().getAddress().getHostAddress();
    }
}
