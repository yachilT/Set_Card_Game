package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    public final boolean[][] playersTokens; // 2d array that holds the token of each player

    public final ReentrantLock[] slotLocks;
    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.playersTokens = new boolean[env.config.players][env.config.tableSize];
        slotLocks = new ReentrantLock[env.config.tableSize];
        for (int i = 0; i < slotLocks.length; i++) {
            slotLocks[i] = new ReentrantLock(true);
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        env.ui.placeCard(card, slot);
        slotLocks[slot].unlock();
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        slotLocks[slot].lock();
            try {
                Thread.sleep(env.config.tableDelayMillis);
            } catch (InterruptedException ignored) {}
            for (int i = 0; i < playersTokens.length; i++) {
                playersTokens[i][slot] = false;
                env.ui.removeToken(i, slot);
            }
            int card = slotToCard[slot];
            slotToCard[slot] = null;
            cardToSlot[card] = null; 

            env.ui.removeCard(slot);
        
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        slotLocks[slot].lock();
        if (slotToCard[slot] != null){
            playersTokens[player][slot] = true;
            env.ui.placeToken(player, slot);
        }
        slotLocks[slot].unlock();   
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        slotLocks[slot].lock();
        if (!playersTokens[player][slot]){
            slotLocks[slot].unlock();
            return false;
        }
        playersTokens[player][slot] = false;
        env.ui.removeToken(player, slot);
        slotLocks[slot].unlock();
        return true;
    }

    public int[] getCardsOfPlayer(int id) {
        int[] cards = new int[env.config.featureSize];
        int cardsCounter = 0;
        for (int slot = 0; slot < playersTokens[id].length; slot++)
        {
            slotLocks[slot].lock();
            if (playersTokens[id][slot]) {
                cards[cardsCounter++] = slotToCard[slot];
            }
            slotLocks[slot].unlock();
        }

        return cardsCounter == 0 ? null : cards;
    }
}
