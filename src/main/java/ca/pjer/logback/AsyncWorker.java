package ca.pjer.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.amazonaws.services.logs.model.InputLogEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class AsyncWorker extends Worker implements Runnable {

    private final AtomicBoolean running;
    private final BlockingQueue<InputLogEvent> queue;
    private final AtomicLong lostCount;

    private Thread thread;

    AsyncWorker(AwsLogsAppender awsLogsAppender) {
        super(awsLogsAppender);
        running = new AtomicBoolean(false);
        queue = new ArrayBlockingQueue<InputLogEvent>(awsLogsAppender.getMaxQueueSize());
        lostCount = new AtomicLong(0);
    }

    @Override
    public synchronized void start() {
        super.start();
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this);
            thread.setDaemon(true);
            thread.setName(getAwsLogsAppender().getName() + " Async Worker");
            thread.start();
        }
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            synchronized (running) {
                running.notifyAll();
            }
            if (thread != null) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    thread.interrupt();
                }
                thread = null;
            }
            queue.clear();
        }
        super.stop();
    }

    @Override
    public void append(ILoggingEvent event) {
        // don't bother trying to log if queue is full and event is not important (< WARN)
        if (queue.remainingCapacity() == 0 && !event.getLevel().isGreaterOrEqual(Level.WARN)) {
            lostCount.incrementAndGet();
            synchronized (running) {
                running.notifyAll();
            }
            return;
        }
        InputLogEvent logEvent = asInputLogEvent(event);
        // are we allowed to block ?
        if (getAwsLogsAppender().getMaxBlockTimeMillis() > 0) {
            // we are allowed to block, offer uninterruptibly for the configured maximum blocking time
            boolean interrupted = false;
            long until = System.currentTimeMillis() + getAwsLogsAppender().getMaxBlockTimeMillis();
            try {
                long now = System.currentTimeMillis();
                while (now < until) {
                    try {
                        if (!queue.offer(logEvent, until - now, TimeUnit.MILLISECONDS)) {
                            lostCount.incrementAndGet();
                        }
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                        now = System.currentTimeMillis();
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            // we are not allowed to block, offer without blocking
            if (!queue.offer(logEvent)) {
                lostCount.incrementAndGet();
            }
        }
        // trigger a flush if queue is full
        if (queue.remainingCapacity() == 0) {
            synchronized (running) {
                running.notifyAll();
            }
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            flush(false);
            try {
                synchronized (running) {
                    if (running.get()) {
                        running.wait(getAwsLogsAppender().getMaxFlushTimeMillis());
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
        flush(true);
    }

    private void flush(boolean all) {
        try {
            long lostCount = this.lostCount.getAndSet(0);
            if (lostCount > 0) {
                getAwsLogsAppender().addWarn(lostCount + " events lost");
            }
            if (!queue.isEmpty()) {
                int size = queue.size();
                List<InputLogEvent> events = new ArrayList<InputLogEvent>(size);
                while (true) {
                    queue.drainTo(events, size);
                    getAwsLogsAppender().getAwsLogsStub().logEvents(events);
                    size = queue.size();
                    if (size == 0 || !all) {
                        break;
                    }
                    events.clear();
                }
            }
        } catch (Exception e) {
            getAwsLogsAppender().addError("Unable to flush events to AWS", e);
        }
    }
}
