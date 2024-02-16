package bguspl.set.ex;

import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /*
     * The dealer.
     */
    private final Dealer dealer;
    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /*
     * The of incoming key presses
     */
    private final BlockingQueue<Integer> q;

    private final Vector<Integer> tokens;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private long sleep; 

    private boolean shouldClear;

    private boolean isChecked;//Complete This!

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        q = new LinkedBlockingQueue<>(env.config.featureSize);
        this.tokens = new Vector<>(3);
        sleep = -1;
        shouldClear = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if (sleep != -1)
            {
                try {
                    Thread.sleep(sleep);
                }
                catch(InterruptedException ignored) {}
                finally {
                    shouldClear();
                }
                
            }

            if (shouldClear) {
                synchronized(q){q.clear();}
                shouldClear = false;
            }
                
            applyAction();
            if (tokens.size() == env.config.featureSize){
                dealer.addClaimSet(id);
                try {Thread.sleep(100);}catch(InterruptedException ignored){}
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                try {
                    synchronized (this) { wait(); }
                } catch (InterruptedException ignored) {}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) { // inserts an action to the queue
        q.add(slot);
    }

    private void applyAction() {
        Integer slot = null;
        synchronized (q)
        {
            if (!q.isEmpty())
                slot = q.remove();
        }

        if (slot == null)
            return;

        if (tokens.contains(slot)) {
            if (!table.removeToken(id, slot))
                env.logger.warning("unable to remove token in " + slot + " by " + id);
            else
                tokens.remove(slot);
        }
        else if(tokens.size() < env.config.featureSize){
            
            if (table.playersTokens[id][slot])
                env.logger.warning("unable to add token in " + slot + " by " + id);
            else{
                table.placeToken(id, slot);
                tokens.add(slot);
            }
        }
    }
    
    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        sleep = env.config.pointFreezeMillis;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        sleep = env.config.penaltyFreezeMillis;
    }


    public int score() {
        return score;
    }

    //check without synch
    public synchronized void shouldClear() {
        shouldClear = true;
    }
}
