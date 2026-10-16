package fr.xephi.authme.identity;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Inventory holder of the identity menu (/lg). Carries the data the menu was built with so
 * that click handling can map a slot back to an account and browse to other pages.
 */
public class IdentityMenuHolder implements InventoryHolder {

    private final String email;
    private final List<AccountEntry> accounts;
    private final int page;
    private Inventory inventory;

    /**
     * Constructor.
     *
     * @param email the email address bound to the viewing player, or null if not bound
     *        (optional information, no longer required for switching)
     * @param accounts the accounts bound to the viewing player (excluding the current one)
     * @param page the zero-based page number currently displayed
     */
    public IdentityMenuHolder(String email, List<AccountEntry> accounts, int page) {
        this.email = email;
        this.accounts = Collections.unmodifiableList(accounts);
        this.page = page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Sets the inventory created for this holder.
     *
     * @param inventory the inventory
     */
    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * @return the email address bound to the viewing player, or null if not bound
     */
    public String getEmail() {
        return email;
    }

    /**
     * @return the accounts bound to the viewing player, as displayed by the menu
     */
    public List<AccountEntry> getAccounts() {
        return accounts;
    }

    /**
     * @return the zero-based page number currently displayed
     */
    public int getPage() {
        return page;
    }

    /**
     * One entry of the account list shown in the identity menu.
     */
    public static class AccountEntry {

        private final String realName;
        private final UUID uuid;
        private final boolean bedrock;

        /**
         * Constructor.
         *
         * @param realName the account name with database casing
         * @param uuid the UUID of the account
         * @param bedrock whether the account is a Bedrock (Floodgate) account
         */
        public AccountEntry(String realName, UUID uuid, boolean bedrock) {
            this.realName = realName;
            this.uuid = uuid;
            this.bedrock = bedrock;
        }

        /**
         * @return the account name with database casing
         */
        public String getRealName() {
            return realName;
        }

        /**
         * @return the UUID of the account
         */
        public UUID getUuid() {
            return uuid;
        }

        /**
         * @return true if the account is a Bedrock (Floodgate) account
         */
        public boolean isBedrock() {
            return bedrock;
        }
    }
}
