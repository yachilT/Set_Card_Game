package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
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
    private final ReentrantLock qLock;

    private int[] cardsToRemove;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    public volatile boolean shuffling;

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
        shuffling = true;
        this.qLock = new ReentrantLock(true);
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
        for (ReentrantLock lock : table.slotLocks) {
            lock.lock();
        }
        while (!shouldFinish()) {
            placeCardsOnTable(); 
            shuffling = false;   

            timerLoop();
            updateTimerDisplay(true);
            shuffling = true;
            removeAllCardsFromTable();
        }
        shuffling = false;
        announceWinners();
        //TODO: Plaster - need to think of a better way!!:
        for (ReentrantLock lock : table.slotLocks) {
            lock.unlock();
        }
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
            synchronized(players[i]) {
                players[i].notifyAll();
            }
            players[i].join();
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 100;
        
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {

            Map.Entry<Integer, List<Integer>> e = checkClaimedSet().entrySet().iterator().next();

            List<Integer> slotsToCheck = e.getValue();
            int id = e.getKey();

            Collections.sort(slotsToCheck);
            boolean isSet = false;
            if (!slotsToCheck.isEmpty()) {
                int[] cardsToCheck = new int[slotsToCheck.size()];
                for (int i = 0; i < env.config.featureSize; i++){
                    int slot = slotsToCheck.get(i);
                    table.slotLocks[slot].lock();
                    cardsToCheck[i] = table.slotToCard[slot];
                }
                
                isSet = env.util.testSet(cardsToCheck);
                if (isSet) {
                    players[id].point();
                    this.cardsToRemove = cardsToCheck;
                    synchronized(players[id]){
                        players[id].notifyAll();
                    }
                    removeCardsFromTable();
                    placeCardsOnTable();
                    // System.out.println("--------------------------------------------------------------");
                    // table.hints(); 
                    // System.out.println("***************************************************************");


                }
                else{
                    players[id].penalty();
                    synchronized(players[id]){players[id].notifyAll(); }
                }

                for (int i = env.config.featureSize - 1; i >= 0; i--){
                    int slot = slotsToCheck.get(i);
                    table.slotLocks[slot].unlock();
                }
            }

            updateTimerDisplay(isSet);
            // sleepUntilWokenOrTimeout();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
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
        if(env.util.findSets(deck, 1).isEmpty())
            terminate();
        else {
            Collections.shuffle(deck);
            List<Integer> range = IntStream.range(0, env.config.columns * env.config.rows).boxed().collect(Collectors.toList());
            Collections.shuffle(range);
            for(int i : range){
                if(table.slotToCard[i] == null && !deck.isEmpty()){
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
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 100;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            // table.hints();
            
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
        List<Integer> range = IntStream.range(0, env.config.tableSize).boxed().collect(Collectors.toList());
        Collections.shuffle(range);
            for(int i : range){
                if (table.slotToCard[i] != null) {
                    int card = table.slotToCard[i];
                    table.removeCard(i);
                    deck.add(card);
                }
            }
            for (Player player : players) {
                player.clearTokens();
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
            // System.out.println("player " + playerId + " claimed set!");
            qLock.lock();
            claimSetsQ.add(playerId);
            qLock.unlock();
    }

    private Map<Integer, List<Integer>> checkClaimedSet() {
        int id = -1;
        qLock.lock();
            if (!claimSetsQ.isEmpty())
                id = claimSetsQ.remove();
        qLock.unlock();
        if (id != -1){
            // System.out.println("checking player " + id + " set!");
            return Map.of(id, players[id].getTokens());
        }
        else
            return Map.of(id, new Vector<Integer>());
    }
}
