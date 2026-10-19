package fr.xephi.authme.message;

import com.google.common.annotations.VisibleForTesting;
import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.initialization.Reloadable;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.util.FileUtils;
import fr.xephi.authme.util.message.I18NUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static fr.xephi.authme.message.MessagePathHelper.DEFAULT_LANGUAGE;

/**
 * Handles a YAML message file with a default file fallback.
 */
public abstract class AbstractMessageFileHandler implements Reloadable {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AbstractMessageFileHandler.class);

    @DataFolder
    @Inject
    private File dataFolder;

    @Inject
    private Settings settings;

    private String filename;
    private FileConfiguration configuration;
    private Map<String, FileConfiguration> i18nConfiguration;
    private final String defaultFile;

    protected AbstractMessageFileHandler() {
        this.defaultFile = createFilePath(DEFAULT_LANGUAGE);
    }

    @Override
    @PostConstruct
    public void reload() {
        String language = settings.getProperty(PluginSettings.MESSAGES_LANGUAGE);
        filename = createFilePath(language);
        File messagesFile = initializeFile(filename);
        configuration = YamlConfiguration.loadConfiguration(messagesFile);
        i18nConfiguration = null;
    }

    protected String getLanguage() {
        return settings.getProperty(PluginSettings.MESSAGES_LANGUAGE);
    }

    protected File getUserLanguageFile() {
        return new File(dataFolder, filename);
    }

    protected String getFilename() {
        return filename;
    }

    /**
     * Returns whether the message file configuration has an entry at the given path.
     *
     * @param path the path to verify
     * @return true if an entry exists for the path in the messages file, false otherwise
     */
    public boolean hasSection(String path) {
        return configuration.get(path) != null;
    }

    /**
     * Returns the message for the given key.
     *
     * @param key the key to retrieve the message for
     * @return the message
     */
    public String getMessage(String key) {
        String message = configuration.getString(key);
        return message == null
            ? "Error retrieving message '" + key + "'"
            : message;
    }

    /**
     * Returns the i18n message for the given key and given locale.
     *
     * @param key the key to retrieve the message for
     * @param locale the locale that player client setting uses
     * @return the message
     */
    public String getMessageByLocale(String key, String locale) {
        if (locale == null || !settings.getProperty(PluginSettings.I18N_MESSAGES)) {
            return getMessage(key);
        }

        String message = getI18nConfiguration(locale).getString(key);
        return message == null
            ? "Error retrieving message '" + key + "'"
            : message;
    }

    /**
     * Returns the message for the given key only if it exists,
     * i.e. without falling back to the default file.
     *
     * @param key the key to retrieve the message for
     * @return the message, or {@code null} if not available
     */
    public String getMessageIfExists(String key) {
        return configuration.getString(key);
    }

    public FileConfiguration getI18nConfiguration(String locale) {
        if (i18nConfiguration == null) {
            i18nConfiguration = new ConcurrentHashMap<>();
        }

        locale = I18NUtils.localeToCode(locale, settings);

        if (i18nConfiguration.containsKey(locale)) {
            return i18nConfiguration.get(locale);
        } else {
            // Sync with reload();
            String i18nFilename = createFilePath(locale);
            File i18nMessagesFile = initializeFile(i18nFilename);
            FileConfiguration config = YamlConfiguration.loadConfiguration(i18nMessagesFile);

            i18nConfiguration.put(locale, config);

            return config;
        }
    }

    /**
     * Creates the path to the messages file for the given language code.
     *
     * @param language the language code
     * @return path to the message file for the given language
     */
    protected abstract String createFilePath(String language);

    /**
     * Copies the messages file from the JAR to the local messages/ folder if it doesn't exist.
     * An existing file is overwritten when the bundled file declares a newer
     * {@code file_version}: since files are only extracted on first use, a plugin update
     * would otherwise keep serving outdated texts from the previously extracted file.
     *
     * @param filePath path to the messages file to use
     * @return the messages file to use
     */
    @VisibleForTesting
    File initializeFile(String filePath) {
        File file = new File(dataFolder, filePath);
        // Check that JAR file exists to avoid logging an error
        if (FileUtils.getResourceFromJar(filePath) != null && FileUtils.copyFileFromResource(file, filePath)) {
            updateFileIfBundledVersionIsNewer(file, filePath);
            return file;
        }

        if (FileUtils.copyFileFromResource(file, defaultFile)) {
            return file;
        } else {
            logger.warning("Wanted to copy default messages file '" + defaultFile + "' from JAR but it didn't exist");
            return null;
        }
    }

    /**
     * Overwrites the local message file with the bundled one when the bundled file has a
     * higher {@code file_version}. Files without the key (version 0) are never overwritten,
     * so untouched upstream language files remain as they are.
     *
     * @param file the local message file
     * @param filePath the local path to the message file
     */
    private void updateFileIfBundledVersionIsNewer(File file, String filePath) {
        try {
            int bundledVersion = readFileVersion(FileUtils.getResourceFromJar(filePath));
            if (bundledVersion <= 0) {
                return;
            }
            int localVersion = YamlConfiguration.loadConfiguration(file).getInt("file_version", 0);
            if (localVersion >= bundledVersion) {
                return;
            }
            if (FileUtils.forceCopyFileFromResource(file, filePath)) {
                logger.info("Updated message file '" + filePath + "' from version "
                    + localVersion + " to " + bundledVersion);
            }
        } catch (Exception e) {
            logger.logException("Could not check the version of message file '" + filePath + "'", e);
        }
    }

    /**
     * Reads the {@code file_version} of the given YAML stream.
     *
     * @param stream the stream to read from (closed by this method), may be null
     * @return the declared version, or 0 when unavailable
     */
    private int readFileVersion(InputStream stream) throws IOException {
        if (stream == null) {
            return 0;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader).getInt("file_version", 0);
        }
    }
}
