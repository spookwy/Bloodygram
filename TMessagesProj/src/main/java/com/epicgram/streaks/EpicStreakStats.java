package com.epicgram.streaks;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Chat statistics for the "Огонёк" window (port of the plugin's StatsJob).
 * History is crawled from the FIRST message towards new ones (getHistory with add_offset=-limit and min_id),
 * so initiative points are given in order and progress is saved after every page:
 * closing the window or restarting the app continues from the same place, later opens load only new messages.
 * Runs on {@link EpicStreaks#queue}.
 */
public class EpicStreakStats {

    private static final int PAGE_SIZE = 100;
    private static final int INITIATIVE_WINDOW = 30 * 60;
    private static final int MAX_REQUESTS = 600;
    private static final int CRAWL_DELAY = 300;

    public static class Result {
        public EpicStreaks.Stats stats;
        public int total = -1;
        public boolean done;
        public boolean failed;
        public boolean paused;
    }

    private static final HashMap<String, EpicStreakStats> jobs = new HashMap<>();

    /** Starts (or reuses a running) job; {@code listener} is called on the UI thread. */
    public static void start(EpicStreaks streaks, long dialogId, Utilities.Callback<Result> listener) {
        EpicStreaks.queue.postRunnable(() -> {
            String key = streaks.account + ":" + dialogId;
            EpicStreakStats job = jobs.get(key);
            if (job != null && !job.result.done) {
                job.listener = listener;
                job.emit();
                return;
            }
            job = new EpicStreakStats(streaks, dialogId, listener);
            jobs.put(key, job);
            job.begin();
        });
    }

    /** Detaches the listener of a closed window; the job keeps counting in background. */
    public static void detach(EpicStreaks streaks, long dialogId, Utilities.Callback<Result> listener) {
        EpicStreaks.queue.postRunnable(() -> {
            EpicStreakStats job = jobs.get(streaks.account + ":" + dialogId);
            if (job != null && job.listener == listener) {
                job.listener = null;
            }
        });
    }

    private final EpicStreaks streaks;
    private final long dialogId;
    private final Result result = new Result();
    private Utilities.Callback<Result> listener;
    private TLRPC.InputPeer peer;
    private int requests;

    private EpicStreakStats(EpicStreaks streaks, long dialogId, Utilities.Callback<Result> listener) {
        this.streaks = streaks;
        this.dialogId = dialogId;
        this.listener = listener;
    }

    private void begin() {
        peer = MessagesController.getInstance(streaks.account).getInputPeer(dialogId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            finish(true, false);
            return;
        }
        emit();
        totalCount();
    }

    private void totalCount() {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = 1;
        ConnectionsManager.getInstance(streaks.account).sendRequest(req, (response, error) -> EpicStreaks.queue.postRunnable(() -> {
            if (response instanceof TLRPC.TL_messages_messagesSlice || response instanceof TLRPC.TL_messages_channelMessages) {
                result.total = ((TLRPC.messages_Messages) response).count;
            } else if (response instanceof TLRPC.messages_Messages) {
                result.total = ((TLRPC.messages_Messages) response).messages.size();
            } else {
                streaks.handleFlood(error);
            }
            emit();
            crawl();
        }));
    }

    private void crawl() {
        long wait = streaks.floodUntil - System.currentTimeMillis();
        if (wait > 0) {
            emit();
            EpicStreaks.queue.postRunnable(this::crawl, wait);
            return;
        }
        requests++;
        int last = streaks.stats(dialogId).mx;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        // PAGE_SIZE messages right AFTER last: offset_id = last + 1 and negative add_offset; min_id cuts counted ones
        req.offset_id = last + 1;
        req.add_offset = -PAGE_SIZE;
        req.limit = PAGE_SIZE;
        req.min_id = last;
        ConnectionsManager.getInstance(streaks.account).sendRequest(req, (response, error) -> EpicStreaks.queue.postRunnable(() -> onCrawl(response, error)));
    }

    private void onCrawl(Object response, TLRPC.TL_error error) {
        if (!(response instanceof TLRPC.messages_Messages)) {
            streaks.handleFlood(error);
            if (streaks.floodUntil > System.currentTimeMillis()) {
                crawl(); // waits for FLOOD_WAIT and retries
            } else {
                finish(true, false);
            }
            return;
        }
        ArrayList<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
        EpicStreaks.Stats s = streaks.stats(dialogId);
        int last = s.mx;
        ArrayList<TLRPC.Message> batch = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            TLRPC.Message m = messages.get(i);
            if (m.id > last) {
                batch.add(m);
            }
        }
        if (batch.isEmpty()) {
            finish(false, false);
            return;
        }
        Collections.sort(batch, (a, b) -> Integer.compare(a.id, b.id));
        for (int i = 0; i < batch.size(); i++) {
            TLRPC.Message m = batch.get(i);
            s.mx = Math.max(s.mx, m.id);
            if (m instanceof TLRPC.TL_messageService || m instanceof TLRPC.TL_messageEmpty) {
                continue;
            }
            if (s.first == 0) {
                s.first = m.date;
            }
            if (m.out) {
                s.cm++;
            } else {
                s.ct++;
            }
            // a point goes to whoever wrote first after the 30-minute timer since the previous point
            if (s.lp == 0 || m.date - s.lp >= INITIATIVE_WINDOW) {
                if (m.out) {
                    s.me++;
                } else {
                    s.th++;
                }
                s.lp = m.date;
            }
        }
        streaks.saveStats(dialogId, s);
        emit();

        if (messages.size() < PAGE_SIZE) {
            finish(false, false);
        } else if (requests >= MAX_REQUESTS) {
            finish(false, true); // very long history: continue on next open
        } else {
            EpicStreaks.queue.postRunnable(this::crawl, CRAWL_DELAY);
        }
    }

    private void finish(boolean failed, boolean paused) {
        result.done = true;
        result.failed = failed;
        result.paused = paused;
        emit();
        jobs.remove(streaks.account + ":" + dialogId);
    }

    private void emit() {
        Utilities.Callback<Result> l = listener;
        if (l == null) {
            return;
        }
        Result snapshot = new Result();
        snapshot.stats = streaks.stats(dialogId).copy();
        snapshot.total = result.total;
        snapshot.done = result.done;
        snapshot.failed = result.failed;
        snapshot.paused = result.paused;
        AndroidUtilities.runOnUIThread(() -> l.run(snapshot));
    }
}
