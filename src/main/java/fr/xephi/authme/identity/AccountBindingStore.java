package fr.xephi.authme.identity;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.security.crypts.HashedPassword;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Persistence of the account bindings used by the identity switch feature (/lg): pairs of
 * accounts that may be switched between, together with a snapshot of each account's password
 * hash taken when the binding was created. A binding is invalidated automatically as soon
 * as the password of either side no longer matches its snapshot (i.e. the password was
 * changed after the binding was made).
 * <p>
 * Bindings are stored in {@code identity-bindings.yml} in the plugin's data folder. All
 * mutating operations must be called from an async task; the file is rewritten on every
 * change.
 */
public class AccountBindingStore {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AccountBindingStore.class);

    private final File file;
    private final List<AccountBinding> bindings = new ArrayList<>();

    @Inject
    AccountBindingStore(@DataFolder File dataFolder) {
        this.file = new File(dataFolder, "identity-bindings.yml");
        load();
    }

    /**
     * Returns all bindings that involve the given account.
     *
     * @param nameLower the lowercase name of the account
     * @return the bindings involving the account (never null)
     */
    public synchronized List<AccountBinding> getBindingsFor(String nameLower) {
        List<AccountBinding> result = new ArrayList<>();
        for (AccountBinding binding : bindings) {
            if (binding.involves(nameLower)) {
                result.add(binding);
            }
        }
        return result;
    }

    /**
     * Returns the binding between the two given accounts, if any.
     *
     * @param nameLowerA the lowercase name of the first account
     * @param nameLowerB the lowercase name of the second account
     * @return the binding between the two accounts, or null if none exists
     */
    public synchronized AccountBinding getBinding(String nameLowerA, String nameLowerB) {
        for (AccountBinding binding : bindings) {
            if (binding.matches(nameLowerA, nameLowerB)) {
                return binding;
            }
        }
        return null;
    }

    /**
     * Returns whether the two given accounts are bound to each other.
     *
     * @param nameLowerA the lowercase name of the first account
     * @param nameLowerB the lowercase name of the second account
     * @return true if a binding exists between the two accounts
     */
    public synchronized boolean isBound(String nameLowerA, String nameLowerB) {
        return getBinding(nameLowerA, nameLowerB) != null;
    }

    /**
     * Records a binding between the two accounts with a snapshot of each account's password
     * hash, replacing any previous binding between them, and persists the change.
     *
     * @param nameA the lowercase name of the first account
     * @param nameB the lowercase name of the second account
     * @param passwordA password snapshot of the first account at bind time
     * @param passwordB password snapshot of the second account at bind time
     */
    public synchronized void addBinding(String nameA, String nameB,
                                        HashedPassword passwordA, HashedPassword passwordB) {
        removeBindingInternal(nameA, nameB);
        bindings.add(new AccountBinding(nameA, nameB, System.currentTimeMillis(), passwordA, passwordB));
        save();
    }

    /**
     * Removes the binding between the two given accounts, if one exists, and persists
     * the change.
     *
     * @param nameLowerA the lowercase name of the first account
     * @param nameLowerB the lowercase name of the second account
     * @return true if a binding was removed
     */
    public synchronized boolean removeBinding(String nameLowerA, String nameLowerB) {
        boolean removed = removeBindingInternal(nameLowerA, nameLowerB);
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Loads the bindings from the data file. Missing or unreadable files are treated as
     * an empty binding list.
     */
    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        List<?> entries = config.getMapList("bindings").stream()
            .map(entry -> (java.util.Map<?, ?>) entry)
            .collect(java.util.stream.Collectors.toList());
        for (Object entryObj : entries) {
            java.util.Map<?, ?> entry = (java.util.Map<?, ?>) entryObj;
            String nameA = Objects.toString(entry.get("a"), null);
            String nameB = Objects.toString(entry.get("b"), null);
            String hashA = Objects.toString(entry.get("aHash"), null);
            String hashB = Objects.toString(entry.get("bHash"), null);
            if (nameA == null || nameB == null || hashA == null || hashB == null) {
                logger.warning("Skipping malformed entry in " + file.getName());
                continue;
            }
            long boundAt = 0L;
            Object boundAtRaw = entry.get("boundAt");
            if (boundAtRaw instanceof Number) {
                boundAt = ((Number) boundAtRaw).longValue();
            }
            bindings.add(new AccountBinding(
                nameA.toLowerCase(Locale.ROOT), nameB.toLowerCase(Locale.ROOT), boundAt,
                toHashedPassword(hashA, entry.get("aSalt")),
                toHashedPassword(hashB, entry.get("bSalt"))));
        }
        logger.info("Loaded " + bindings.size() + " account binding(s) from " + file.getName());
    }

    /**
     * Writes all bindings to the data file.
     */
    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<java.util.Map<String, Object>> entries = new ArrayList<>();
        for (AccountBinding binding : bindings) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("a", binding.getNameA());
            entry.put("b", binding.getNameB());
            entry.put("boundAt", binding.getBoundAt());
            entry.put("aHash", binding.getPasswordA().getHash());
            entry.put("aSalt", saltToString(binding.getPasswordA()));
            entry.put("bHash", binding.getPasswordB().getHash());
            entry.put("bSalt", saltToString(binding.getPasswordB()));
            entries.add(entry);
        }
        config.set("bindings", entries);
        try {
            config.save(file);
        } catch (IOException e) {
            logger.warning("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Removes the binding between the two accounts from memory without saving.
     *
     * @param nameLowerA the lowercase name of the first account
     * @param nameLowerB the lowercase name of the second account
     * @return true if a binding was removed
     */
    private boolean removeBindingInternal(String nameLowerA, String nameLowerB) {
        return bindings.removeIf(binding -> binding.matches(nameLowerA, nameLowerB));
    }

    /**
     * Rebuilds a password snapshot from its hash and salt values as stored in the file.
     *
     * @param hash the password hash
     * @param salt the salt value, may be null or empty
     * @return the rebuilt password snapshot
     */
    private static HashedPassword toHashedPassword(String hash, Object salt) {
        String saltStr = Objects.toString(salt, null);
        return new HashedPassword(hash, saltStr == null || saltStr.isEmpty() ? null : saltStr);
    }

    /**
     * Returns the string representation of the snapshot's salt (empty string for null).
     *
     * @param password the password snapshot
     * @return the salt as a string, never null
     */
    private static String saltToString(HashedPassword password) {
        return password.getSalt() == null ? "" : password.getSalt();
    }

    /**
     * One binding between two accounts, with the password snapshots taken at bind time.
     */
    public static class AccountBinding {

        private final String nameA;
        private final String nameB;
        private final long boundAt;
        private final HashedPassword passwordA;
        private final HashedPassword passwordB;

        /**
         * Constructor.
         *
         * @param nameA lowercase name of the first account
         * @param nameB lowercase name of the second account
         * @param boundAt the timestamp at which the binding was created
         * @param passwordA password snapshot of the first account at bind time
         * @param passwordB password snapshot of the second account at bind time
         */
        public AccountBinding(String nameA, String nameB, long boundAt,
                              HashedPassword passwordA, HashedPassword passwordB) {
            this.nameA = nameA;
            this.nameB = nameB;
            this.boundAt = boundAt;
            this.passwordA = passwordA;
            this.passwordB = passwordB;
        }

        /**
         * @return the lowercase name of the first account
         */
        public String getNameA() {
            return nameA;
        }

        /**
         * @return the lowercase name of the second account
         */
        public String getNameB() {
            return nameB;
        }

        /**
         * @return the timestamp at which the binding was created
         */
        public long getBoundAt() {
            return boundAt;
        }

        /**
         * @return the password snapshot of the first account at bind time
         */
        public HashedPassword getPasswordA() {
            return passwordA;
        }

        /**
         * @return the password snapshot of the second account at bind time
         */
        public HashedPassword getPasswordB() {
            return passwordB;
        }

        /**
         * Returns the other account of the binding.
         *
         * @param nameLower the lowercase name of one side of the binding
         * @return the lowercase name of the other side, or null if the given name is not
         *         part of this binding
         */
        public String getOther(String nameLower) {
            if (nameA.equals(nameLower)) {
                return nameB;
            } else if (nameB.equals(nameLower)) {
                return nameA;
            }
            return null;
        }

        /**
         * Returns the password snapshot recorded for the given account at bind time.
         *
         * @param nameLower the lowercase name of one side of the binding
         * @return the password snapshot, or null if the given name is not part of this binding
         */
        public HashedPassword getSnapshotOf(String nameLower) {
            if (nameA.equals(nameLower)) {
                return passwordA;
            } else if (nameB.equals(nameLower)) {
                return passwordB;
            }
            return null;
        }

        /**
         * @param nameLower the lowercase name to check
         * @return true if the given account is part of this binding
         */
        public boolean involves(String nameLower) {
            return nameA.equals(nameLower) || nameB.equals(nameLower);
        }

        /**
         * @param nameLowerA the lowercase name of the first account
         * @param nameLowerB the lowercase name of the second account
         * @return true if this binding connects exactly the two given accounts
         */
        public boolean matches(String nameLowerA, String nameLowerB) {
            return (nameA.equals(nameLowerA) && nameB.equals(nameLowerB))
                || (nameA.equals(nameLowerB) && nameB.equals(nameLowerA));
        }
    }
}
