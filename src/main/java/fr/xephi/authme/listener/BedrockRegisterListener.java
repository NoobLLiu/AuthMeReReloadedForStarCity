package fr.xephi.authme.listener;
/* Inspired by DongShaoNB/BedrockPlayerSupport **/

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.process.register.executors.PasswordRegisterParams;
import fr.xephi.authme.process.register.executors.RegistrationMethod;
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
import java.util.UUID;

/**
 * Bedrock registration form: unregistered players connected through Geyser + Floodgate get a
 * native form to enter their password and its confirmation. On submit, the exact same
 * registration flow as the Java chat command {@code /register <password> <confirmPassword>}
 * is run ({@link RegistrationMethod#PASSWORD_REGISTRATION}); email binding stays optional
 * and can be done later with {@code /email add}.
 */
public class BedrockRegisterListener implements Listener {

    private final AuthMeApi authmeApi = AuthMeApi.getInstance();

    @Inject
    private BukkitService bukkitService;
    @Inject
    private Management management;
    @Inject
    private Messages messages;
    @Inject
    private Settings settings;
    @Inject
    private ValidationService validationService;

    public BedrockRegisterListener() {
    }

    private boolean isBedrockPlayer(UUID uuid) {
        return settings.getProperty(HooksSettings.HOOK_FLOODGATE_PLAYER)
            && FloodgateApi.getInstance().isFloodgateId(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        bukkitService.runTaskLater(player, () -> {
            if (player.isOnline() && isBedrockPlayer(uuid) && !authmeApi.isRegistered(player.getName())) {
                openRegisterForm(player);
            }
        }, 30L);
    }

    /**
     * Sends the registration form with the password and its confirmation. On validation
     * failure the form is reopened; on success the shared registration process is started.
     * Falls back silently to the chat command when the form cannot be delivered.
     *
     * @param player the player to send the form to
     */
    private void openRegisterForm(Player player) {
        try {
            CustomForm.Builder builder = CustomForm.builder()
                .title(messages.retrieveSingle(player, MessageKey.BEDROCK_REGISTER_FORM_TITLE))
                .input(messages.retrieveSingle(player, MessageKey.BEDROCK_REGISTER_FORM_PASSWORD),
                    messages.retrieveSingle(player, MessageKey.BEDROCK_REGISTER_FORM_PASSWORD_HINT))
                .input(messages.retrieveSingle(player, MessageKey.BEDROCK_REGISTER_FORM_CONFIRM),
                    messages.retrieveSingle(player, MessageKey.BEDROCK_REGISTER_FORM_CONFIRM_HINT));
            builder.validResultHandler(response -> {
                String password = response.asInput(0);
                String confirmation = response.asInput(1);
                bukkitService.runTask(player, () -> handleFormResult(player, password, confirmation));
            });
            builder.closedResultHandler(() -> { });

            FloodgatePlayer fgPlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
            if (fgPlayer == null || !fgPlayer.sendForm(builder.build())) {
                messages.send(player, MessageKey.REGISTER_MESSAGE);
            }
        } catch (NoClassDefFoundError | Exception e) {
            // Floodgate or Cumulus classes unavailable, or the form could not be delivered:
            // the chat command /register <password> <confirmPassword> still works
        }
    }

    /**
     * Handles a form submission on the main thread: validates the input the same way the
     * register command does and then runs the shared registration process. Reopens the form
     * on invalid input so the player can retry.
     *
     * @param player the player who submitted the form
     * @param password the entered password
     * @param confirmation the entered password confirmation
     */
    private void handleFormResult(Player player, String password, String confirmation) {
        if (!player.isOnline() || authmeApi.isRegistered(player.getName())) {
            return;
        }
        if (password == null || password.trim().isEmpty()) {
            messages.send(player, MessageKey.BEDROCK_REGISTER_FORM_INVALID);
            openRegisterForm(player);
            return;
        }
        if (!password.equals(confirmation)) {
            messages.send(player, MessageKey.PASSWORD_MATCH_ERROR);
            openRegisterForm(player);
            return;
        }
        ValidationService.ValidationResult result =
            validationService.validatePassword(password, player.getName());
        if (result.hasError()) {
            messages.send(player, result.getMessageKey(), result.getArgs());
            openRegisterForm(player);
            return;
        }
        management.performRegister(RegistrationMethod.PASSWORD_REGISTRATION,
            PasswordRegisterParams.of(player, password, null));
    }
}
