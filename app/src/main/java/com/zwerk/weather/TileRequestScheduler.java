package com.zwerk.weather;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded, URL-deduplicated work. A camera change drops queued work but keeps active downloads. */
final class TileRequestScheduler {
    private final Object lock = new Object();
    private final int capacity;
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private final HashMap<String, Task> outstanding = new HashMap<>();
    private final ArrayList<Thread> workers = new ArrayList<>();
    private Map<String, Integer> demand = new HashMap<>();
    private long sequence;
    private boolean closed;

    private static final class Task implements Comparable<Task> {
        final String key;
        final Runnable work;
        final long sequence;
        int priority;
        Task(String key, int priority, long sequence, Runnable work) {
            this.key = key; this.priority = priority; this.sequence = sequence; this.work = work;
        }
        @Override public int compareTo(Task other) {
            int rank = Integer.compare(priority, other.priority);
            return rank != 0 ? rank : Long.compare(sequence, other.sequence);
        }
    }

    TileRequestScheduler(String name, int workerCount, int capacity) {
        this.capacity = capacity;
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(this::workLoop, name + "-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    boolean isNeeded(String key) {
        synchronized (lock) { return !closed && demand.containsKey(key); }
    }

    boolean submit(String key, Runnable work) {
        synchronized (lock) {
            Integer priority = demand.get(key);
            if (closed || priority == null || outstanding.containsKey(key) || queue.size() >= capacity) return false;
            Task task = new Task(key, priority, sequence++, work);
            outstanding.put(key, task);
            queue.add(task);
            lock.notifyAll();
            return true;
        }
    }

    /** Returns cancelled URLs so their owner's pending/callback records can be discarded too. */
    ArrayList<String> setDemand(Map<String, Integer> next) {
        synchronized (lock) {
            demand = new HashMap<>(next);
            ArrayList<String> removed = new ArrayList<>();
            ArrayList<Task> retained = new ArrayList<>();
            while (!queue.isEmpty()) {
                Task task = queue.remove();
                Integer priority = demand.get(task.key);
                if (priority == null) {
                    outstanding.remove(task.key, task);
                    removed.add(task.key);
                } else {
                    task.priority = priority;
                    retained.add(task);
                }
            }
            queue.addAll(retained);
            return removed;
        }
    }

    private void workLoop() {
        while (true) {
            Task task;
            synchronized (lock) {
                while (!closed && queue.isEmpty()) {
                    try { lock.wait(); }
                    catch (InterruptedException ignored) { if (closed) return; }
                }
                if (closed) return;
                task = queue.remove();
            }
            try { task.work.run(); }
            catch (RuntimeException ignored) { /* One failed task must not permanently remove a worker. */ }
            finally {
                synchronized (lock) { outstanding.remove(task.key, task); }
            }
        }
    }

    void shutdownNow() {
        synchronized (lock) {
            closed = true;
            queue.clear();
            outstanding.clear();
            lock.notifyAll();
        }
        for (Thread worker : workers) worker.interrupt();
    }
}
