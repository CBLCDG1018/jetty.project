//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

import static java.lang.Long.MAX_VALUE;

/**
 * <p>An abstract implementation of a timeout.</p>
 * <p>Subclasses should implement {@link #onTimeoutExpired()}.</p>
 * <p>This implementation is optimised assuming that the timeout
 * will mostly be cancelled and then reused with a similar value.</p>
 */
public abstract class CyclicTimeout implements Destroyable
{
    private static final Logger LOG = Log.getLogger(CyclicTimeout.class);
    private static final Timeout NOT_SET = new Timeout(MAX_VALUE, null);
    private static final Scheduler.Task DESTROYED = () -> false;

    /* The underlying scheduler to use */
    private final Scheduler _scheduler;
    /* Reference to the current Timeout and chain of Wakeup */
    private final AtomicReference<Timeout> _timeout = new AtomicReference<>(NOT_SET);

    /**
     * @param scheduler A scheduler used to schedule wakeups
     */
    public CyclicTimeout(Scheduler scheduler)
    {
        _scheduler = scheduler;
    }

    public Scheduler getScheduler()
    {
        return _scheduler;
    }

    /**
     * Schedules a timeout, even if already set, cancelled or expired.
     *
     * @param delay The period of time before the timeout expires.
     * @param units The unit of time of the period.
     * @return true if the timer was already set.
     */
    public boolean schedule(long delay, TimeUnit units)
    {
        long now = System.nanoTime();
        long new_timeout_at = now + units.toNanos(delay);

        boolean result;
        Wakeup new_wakeup;
        while (true)
        {
            Timeout timeout = _timeout.get();

            new_wakeup = null;
            result = timeout._at != MAX_VALUE;

            // Is the current wakeup good to use? ie before our timeout time?
            Wakeup wakeup = timeout._wakeup;
            if (wakeup == null || wakeup._at > new_timeout_at)
                // No, we need an earlier wakeup.
                wakeup = new_wakeup = new Wakeup(new_timeout_at, wakeup);

            if (_timeout.compareAndSet(timeout, new Timeout(new_timeout_at, wakeup)))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Installed timeout in {} ms, waking up in {} ms",
                            units.toMillis(delay),
                            TimeUnit.NANOSECONDS.toMillis(wakeup._at - now));
                break;
            }
        }

        // If we created a new wakeup, we need to actually schedule it.
        // Any wakeup that is created and discarded by the failed CAS will not be
        // in the wakeup chain, will not have a scheduler task set and will be GC'd.
        if (new_wakeup != null)
            new_wakeup.schedule(now);

        return result;
    }

    /**
     * <p>Cancels this CyclicTimeout so that it won't expire.</p>
     * <p>After being cancelled, this CyclicTimeout can be scheduled again.</p>
     *
     * @return true if this CyclicTimeout was scheduled to expire
     * @see #destroy()
     */
    public boolean cancel()
    {
        boolean result;
        Timeout timeout;
        Timeout new_timeout;
        while (true)
        {
            timeout = _timeout.get();
            result = timeout._at != MAX_VALUE;
            Wakeup wakeup = timeout._wakeup;
            new_timeout = wakeup == null ? NOT_SET : new Timeout(MAX_VALUE, wakeup);
            if (_timeout.compareAndSet(timeout, new_timeout))
                break;
        }
        return result;
    }

    /**
     * <p>Invoked when the timeout expires.</p>
     */
    public abstract void onTimeoutExpired();

    /**
     * <p>Destroys this CyclicTimeout.</p>
     * <p>After being destroyed, this CyclicTimeout is not used anymore.</p>
     */
    public void destroy()
    {
        Timeout timeout = _timeout.getAndSet(NOT_SET);
        Wakeup wakeup = timeout == null ? null : timeout._wakeup;
        while (wakeup != null)
        {
            wakeup.destroy();
            wakeup = wakeup._next;
        }
    }

    /**
     * A timeout time with a link to a Wakeup chain.
     */
    private static class Timeout
    {
        private final long _at;
        private final Wakeup _wakeup;

        private Timeout(long timeoutAt, Wakeup wakeup)
        {
            _at = timeoutAt;
            _wakeup = wakeup;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x:%d,%s", getClass().getSimpleName(), hashCode(), _at, _wakeup);
        }
    }

    /**
     * A Wakeup chain of real scheduler tasks.
     */
    private class Wakeup implements Runnable
    {
        private final AtomicReference<Scheduler.Task> _task = new AtomicReference<>();
        private final long _at;
        private final Wakeup _next;

        private Wakeup(long wakeupAt, Wakeup next)
        {
            _at = wakeupAt;
            _next = next;
        }

        private void schedule(long now)
        {
            _task.compareAndSet(null, _scheduler.schedule(this, _at - now, TimeUnit.NANOSECONDS));
        }

        private void destroy()
        {
            Scheduler.Task task = _task.getAndSet(DESTROYED);
            if (task != null)
                task.cancel();
        }

        @Override
        public void run()
        {
            long now;
            Wakeup new_wakeup;
            boolean has_expired;

            while (true)
            {
                Timeout timeout = _timeout.get();

                // We must look for ourselves in the current wakeup list.
                // If we find ourselves, then we act and we use our tail for any new
                // wakeup list, effectively removing any wakeup before us in the list (and making them no-ops).
                // If we don't find ourselves, then a wakeup that should have expired after us has already run
                // and removed us from the list, so we become a noop.

                Wakeup wakeup = timeout._wakeup;
                while (wakeup != null)
                {
                    if (wakeup == this)
                        break;
                    // Not us, so look at next wakeup in the list.
                    wakeup = wakeup._next;
                }
                if (wakeup == null)
                    // Not found, we become a noop.
                    return;

                now = System.nanoTime();
                new_wakeup = null;
                has_expired = false;
                Timeout new_timeout;

                // We are in the wakeup list! So we have to act and we know our
                // tail has not expired (else it would have removed us from the list).
                // Remove ourselves (and any prior Wakeup) from the wakeup list.
                wakeup = wakeup._next;

                if (timeout._at <= now)
                {
                    // We have timed out!
                    has_expired = true;
                    new_timeout = wakeup == null ? NOT_SET : new Timeout(MAX_VALUE, wakeup);
                }
                else if (timeout._at != MAX_VALUE)
                {
                    // We have not timed out, but we are set to!
                    // Is the current wakeup good to use? ie before our timeout time?
                    if (wakeup == null || wakeup._at >= timeout._at)
                        // No, we need an earlier wakeup.
                        wakeup = new_wakeup = new Wakeup(timeout._at, wakeup);
                    new_timeout = new Timeout(timeout._at, wakeup);
                }
                else
                {
                    // We don't timeout, preserve scheduled chain.
                    new_timeout = wakeup == null ? NOT_SET : new Timeout(MAX_VALUE, wakeup);
                }

                // Loop until we succeed in changing state or we are a noop!
                if (_timeout.compareAndSet(timeout, new_timeout))
                    break;
            }

            // If we created a new wakeup, we need to actually schedule it.
            if (new_wakeup != null)
                new_wakeup.schedule(now);

            // If we expired, then do the callback.
            if (has_expired)
                onTimeoutExpired();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x:%d->%s", getClass().getSimpleName(), hashCode(), _at, _next);
        }
    }
}
