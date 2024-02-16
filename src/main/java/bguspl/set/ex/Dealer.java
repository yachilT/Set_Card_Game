package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    private final Queue<Integer> claimSetsQ;

    private int[] cardsToRemove;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.claimSetsQ = new LinkedList<>();
        this.cardsToRemove = null;
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
    }
    
    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (Player player : players) {
            new ThreadLogger(player, "player " + player.id, env.logger).startWithLog();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();     
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();

        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis +100;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(checkClaimedSet());
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        for (Player player : players) {
            player.terminate();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (cardsToRemove != null) {
            for(int i = 0; i < cardsToRemove.length; i++)
                table.removeCard(table.cardToSlot[cardsToRemove[i]]);
        }
        cardsToRemove = null;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);
        List<Integer> range = IntStream.range(0, env.config.columns * env.config.rows).boxed().collect(Collectors.toList());
        Collections.shuffle(range);
        synchronized(table){
            for(int i : range){
                if(table.slotToCard[i] == null){
                    int card = deck.remove(deck.size()-1);
                    table.placeCard(card, i);
                }
            }
        }
        
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try{
            Thread.sleep(1000);
        }
        catch(InterruptedException ignored) {}
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset){
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }   
        else {
            long nextTime = reshuffleTime - System.currentTimeMillis();
            env.ui.setCountdown(nextTime, nextTime < env.config.turnTimeoutWarningMillis);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        List<Integer> range = IntStream.range(0, env.config.columns * env.config.rows).boxed().collect(Collectors.toList());
        Collections.shuffle(range);
        synchronized(table){
            for(int i : range){
                int card = table.slotToCard[i];
                table.removeCard(i);
                deck.add(card);
            }
        }
    }   

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = Arrays.stream(players).map(Player::score).max(Integer::compare).get();
        env.ui.announceWinner(Arrays.stream(players).filter(p -> p.score() == maxScore).mapToInt(p-> p.id).toArray());
    }

    public void addClaimSet(int playerId){
        synchronized(claimSetsQ){
            System.out.println("player " + playerId + " claimed set!");
            claimSetsQ.add(playerId);
        }
    }

    private boolean checkClaimedSet() {
        int id = -1;
        synchronized(claimSetsQ) {
            if (!claimSetsQ.isEmpty())
                id = claimSetsQ.remove();
        }
        if (id != -1){
            System.out.println("checking player " + id + " set!");
            int[] cardsToRemove = table.getCardsOfPlayer(id);
            System.out.println(cardsToRemove == null ? "couldn't find card for player: " + id : "found card for player: " + id);
            if (env.util.testSet(cardsToRemove)) {
                
                players[id].point();
                this.cardsToRemove = cardsToRemove;
                return true;
            }
            else
                players[id].penalty();
        }
        return false;
           
    }
}
