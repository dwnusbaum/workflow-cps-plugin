package com.cloudbees.groovy.cps.green;

import com.cloudbees.groovy.cps.Block;
import com.cloudbees.groovy.cps.Continuable;
import com.cloudbees.groovy.cps.impl.ConstantBlock;
import com.cloudbees.groovy.cps.impl.CpsCallableInvocation;
import com.cloudbees.groovy.cps.Outcome;
import com.cloudbees.groovy.cps.impl.ThrowBlock;
import java.io.Serializable;

/**
 * Represents a green thread.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class GreenThread implements Runnable, Serializable {
    public GreenThread() {
    }

    /**
     * Creates a new green thread that executes the given closure.
     */
    public void start() {
        Block b;
        try {
            run();

            // closure had run synchronously.
            b = Block.NOOP;
        } catch (CpsCallableInvocation inv) {
            // this will create a thread, and resume with the newly created thread
            b = inv.asBlock();
        } catch (Throwable t) {
            // closure had run synchronously and failed
            b = new ThrowBlock(new ConstantBlock(t));
        }

        final Block bb = b;
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                w = w.withNewThread(new GreenThreadState(GreenThread.this,bb));
                return new Result(w, new Outcome(GreenThread.this,null), false);
            }
        });

        // thus the code will never reach here
        throw new AssertionError();
    }

    /**
     * Executes the task and make its result available back to the caller.
     *
     * Bogus return type is just for a convenience
     */
    private static void invoke(ThreadTask task) {
        Continuable.suspend(task);

        // the code will never reach here
        throw new AssertionError();
    }

    private GreenThreadState stateAt(GreenWorld d) {
        return d.resolveThreadState(this);
    }

    public boolean isAlive() {
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                return new Result(w, new Outcome(!stateAt(w).isDead(),null), false);
            }
        });
        throw new AssertionError();
    }

//    public boolean isDead() {
//        return invoke(new ThreadTask<Boolean>() {
//            public Boolean eval(GreenWorld w) {
//                return stateAt(w).isDead();
//            }
//        });
//    }

//    // TODO: this is not very useful because it doesn't block for the completion
//    public Object getResult() throws InvocationTargetException {
//        Continuable.suspend(new ThreadTask() {
//            public Object eval(GreenWorld w) throws Throwable {
//                return w.resolveThreadState(id).getResult().replay();
//            }
//        });
//
//        // the code will never reach here
//        throw new AssertionError();
//    }

    public static GreenThread currentThread() {
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                return new Result(w, new Outcome(w.currentThread().g,null), false);
            }
        });
        throw new AssertionError();
    }

    public static void monitorEnter(final Object o) {
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                return new Result(trans(w),null,false);
            }
            public GreenWorld trans(GreenWorld d) {
                GreenThreadState cur = d.currentThread();
                for (GreenThreadState t : d.threads) {
                    if (t!=cur && t.hasMonitor(o)) {
                        // someone else has lock, so we need to wait
                        return d.with(cur.withCond(Cond.MONITOR_ENTER, o));
                    }
                }
                // no one else has a lock, so we acquire the lock and move on
                return d.with(cur.pushMonitor(o));
            }
        });
        throw new AssertionError();
    }

    public static void monitorLeave() {
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                GreenThreadState cur = w.currentThread();
                final Object o = cur.monitor.o;

                // the current thread will release the monitor.
                cur=cur.popMonitor();
                w = w.with(cur);

                if (!cur.hasMonitor(o)) {
                    // this thread has fully released a monitor.
                    // if another thread is waiting for this monitor, he gets one right away
                    OUTER:
                    for (GreenThreadState t : w.threads) {
                        if (t.wait==o) {
                            switch(t.cond) {
                            case MONITOR_ENTER:
                                // acquire a new monitor
                                w = w.with(t.withCond(null,null).pushMonitor(o));
                                break OUTER;
                            case NOTIFIED:
                                // reacquire a monitor
                                w = w.with(t.withCond(null,null));
                                break OUTER;
                            }
                        }
                    }
                }

                return new Result(w,null,false);
            }
        });
        throw new AssertionError();
    }

    public static void wait(final Object o) {
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                GreenThreadState cur = w.currentThread();

                if (!cur.hasMonitor(o))
                    throw new IllegalStateException("Thread doesn't have a lock of "+o);

                // wait for the notification to arrive
                w = w.with(cur.withCond(Cond.WAIT, o));

                return new Result(w,null,false);
            }
        });
        throw new AssertionError();
    }

    public static void notify(final Object o, final boolean all) {
        invoke(new ThreadTask() {
            public Result eval(GreenWorld w) {
                GreenThreadState cur = w.currentThread();

                if (!cur.hasMonitor(o))
                    throw new IllegalStateException("Thread doesn't have a lock of "+o);

                // let other waiting threads come back to life
                for (GreenThreadState t : w.threads) {
                    if (t.wait==o) {
                        w = w.with(t.withCond(Cond.NOTIFIED, o));
                        if (!all)
                            break;
                    }
                }

                return new Result(w,null,false);
            }
        });
        throw new AssertionError();
    }
}
