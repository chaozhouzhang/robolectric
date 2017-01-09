package org.robolectric.shadows;

import android.os.Looper;
import org.robolectric.RoboSettings;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR2;
import static org.robolectric.RuntimeEnvironment.isMainThread;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.internal.Shadow.invokeConstructor;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

/**
 * Shadow for {@link android.os.Looper} that enqueues posted {@link Runnable}s to be run
 * (on this thread) later. {@code Runnable}s that are scheduled to run immediately can be
 * triggered by calling {@link #idle()}.
 *
 * @see ShadowMessageQueue
 */
@Implements(Looper.class)
public class ShadowLooper {
  private static final List<Looper> loopingLoopers = new ArrayList<>();

  private static Looper mainLooper;

  private @RealObject Looper realObject;

  boolean quit;

  @Resetter
  public static synchronized void resetThreadLoopers() {
    // Blech. We need to keep the main looper because somebody might refer to it in a static
    // field. The other loopers need to be wrapped in WeakReferences so that they are not prevented from
    // being garbage collected.
    if (!isMainThread()) {
      throw new IllegalStateException("you should only be calling this from the main thread!");
    }

    // Threads on which `Looper.loop()` was called may be blocked at test teardown time; unstick them.
    synchronized (loopingLoopers) {
      for (Looper looper : loopingLoopers) {
        looper.quit();
      }

      loopingLoopers.clear();
    }

    // Because resetStaticState() is called by ParallelUniverse on startup before prepareMainLooper() is
    // called, this might be null on that occasion.
    if (mainLooper != null) {
      shadowOf(mainLooper).reset();
    }
  }

  @Implementation
  public void __constructor__(boolean quitAllowed) {
    invokeConstructor(Looper.class, realObject, from(boolean.class, quitAllowed));
    if (isMainThread()) {
      mainLooper = realObject;
    } else {
      synchronized (loopingLoopers) {
        loopingLoopers.add(realObject);
      }
    }
    resetScheduler();
  }

  @Implementation
  public static Looper getMainLooper() {
    return mainLooper;
  }

  @Implementation
  public static Looper myLooper() {
    return getLooperForThread(Thread.currentThread());
  }

  @Implementation
  public static void loop() {
    shadowOf(Looper.myLooper()).doLoop();
  }

  private void doLoop() {
    if (this != getShadowMainLooper()) {
      synchronized (realObject) {
        while (!quit) {
          try {
            realObject.wait();
          } catch (InterruptedException ignore) {
          }
        }
      }
    }
  }

  @Implementation
  public void quit() {
    if (this == getShadowMainLooper()) throw new RuntimeException("Main thread not allowed to quit");
    quitUnchecked();
  }

  @Implementation(minSdk = JELLY_BEAN_MR2)
  public void quitSafely() {
    quit();
  }

  public void quitUnchecked() {
    synchronized (realObject) {
      quit = true;
      realObject.notifyAll();
      getScheduler().reset();
    }
  }
  
  @HiddenApi @Implementation
  public int postSyncBarrier() {
    return 1;
  }

  public boolean hasQuit() {
    synchronized (realObject) {
      return quit;
    }
  }

  public static ShadowLooper getShadowMainLooper() {
    return shadowOf(Looper.getMainLooper());
  }
  
  public static Looper getLooperForThread(Thread thread) {
    if (isMainThread(thread)) return mainLooper;
    synchronized (loopingLoopers) {
      for (Looper looper : loopingLoopers) {
        if (looper.getThread().equals(thread)) {
          return looper;
        }
      }
    }
    return null;
  }
  
  public static void pauseLooper(Looper looper) {
    shadowOf(looper).pause();
  }

  public static void unPauseLooper(Looper looper) {
    shadowOf(looper).unPause();
  }

  public static void pauseMainLooper() {
    getShadowMainLooper().pause();
  }

  public static void unPauseMainLooper() {
    getShadowMainLooper().unPause();
  }

  public static void idleMainLooper() {
    getShadowMainLooper().idle();
  }

  /** @deprecated Use {@link #idleMainLooper(long, TimeUnit)}. */
  @Deprecated
  public static void idleMainLooper(long interval) {
    idleMainLooper(interval, TimeUnit.MILLISECONDS);
  }

  public static void idleMainLooper(long amount, TimeUnit unit) {
    getShadowMainLooper().idle(amount, unit);
  }

  public static void idleMainLooperConstantly(boolean shouldIdleConstantly) {
    getShadowMainLooper().idleConstantly(shouldIdleConstantly);
  }

  public static void runMainLooperOneTask() {
    getShadowMainLooper().runOneTask();
  }

  public static void runMainLooperToNextTask() {
    getShadowMainLooper().runToNextTask();
  }
    
  /**
   * Runs any immediately runnable tasks previously queued on the UI thread,
   * e.g. by {@link android.app.Activity#runOnUiThread(Runnable)} or {@link android.os.AsyncTask#onPostExecute(Object)}.
   *
   * <p>Note: calling this method does not pause or un-pause the scheduler.</p>
   
   * @see #runUiThreadTasksIncludingDelayedTasks
   */
  public static void runUiThreadTasks() {
    getShadowMainLooper().idle();
  }

  /**
   * Runs all runnable tasks (pending and future) that have been queued on the UI thread. Such tasks may be queued by
   * e.g. {@link android.app.Activity#runOnUiThread(Runnable)} or {@link android.os.AsyncTask#onPostExecute(Object)}.
   *
   * <p>Note: calling this method does not pause or un-pause the scheduler, however the clock is advanced as
   * future tasks are run.</p>
   * 
   * @see #runUiThreadTasks
   */
  public static void runUiThreadTasksIncludingDelayedTasks() {
    getShadowMainLooper().runToEndOfTasks();
  }

  /**
   * Causes {@link Runnable}s that have been scheduled to run immediately to actually run. Does not advance the
   * scheduler's clock;
   */
  public void idle() {
    idle(0, TimeUnit.MILLISECONDS);
  }

  /**
   * Causes {@link Runnable}s that have been scheduled to run within the next {@code intervalMillis} milliseconds to
   * run while advancing the scheduler's clock.
   *
   * @deprecated Use {@link #idle(long, TimeUnit)}.
   */
  @Deprecated
  public void idle(long intervalMillis) {
    idle(intervalMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Causes {@link Runnable}s that have been scheduled to run within the next specified amount of time to run while
   * advancing the scheduler's clock.
   */
  public void idle(long amount, TimeUnit unit) {
    getScheduler().advanceBy(amount, unit);
  }

  public void idleConstantly(boolean shouldIdleConstantly) {
    getScheduler().idleConstantly(shouldIdleConstantly);
  }

  /**
   * Causes all of the {@link Runnable}s that have been scheduled to run while advancing the scheduler's clock to the
   * start time of the last scheduled {@link Runnable}.
   */
  public void runToEndOfTasks() {
    getScheduler().advanceToLastPostedRunnable();
  }

  /**
   * Causes the next {@link Runnable}(s) that have been scheduled to run while advancing the scheduler's clock to its
   * start time. If more than one {@link Runnable} is scheduled to run at this time then they will all be run.
   */
  public void runToNextTask() {
    getScheduler().advanceToNextPostedRunnable();
  }

  /**
   * Causes only one of the next {@link Runnable}s that have been scheduled to run while advancing the scheduler's
   * clock to its start time. Only one {@link Runnable} will run even if more than one has ben scheduled to run at the
   * same time.
   */
  public void runOneTask() {
    getScheduler().runOneTask();
  }

  /**
   * Enqueue a task to be run later.
   *
   * @param runnable    the task to be run
   * @param delayMillis how many milliseconds into the (virtual) future to run it
   * @return true if the runnable is enqueued
   * @see android.os.Handler#postDelayed(Runnable,long)
   * @deprecated Use a {@link android.os.Handler} instance to post to a looper.
   */
  @Deprecated
  public boolean post(Runnable runnable, long delayMillis) {
    if (!quit) {
      getScheduler().postDelayed(runnable, delayMillis, TimeUnit.MILLISECONDS);
      return true;
    } else {
      return false;
    }
  }
  
  /**
   * Enqueue a task to be run ahead of all other delayed tasks.
   *
   * @param runnable    the task to be run
   * @return true if the runnable is enqueued
   * @see android.os.Handler#postAtFrontOfQueue(Runnable)
   * @deprecated Use a {@link android.os.Handler} instance to post to a looper.
   */
  @Deprecated
  public boolean postAtFrontOfQueue(Runnable runnable) {
    if (!quit) {
      getScheduler().postAtFrontOfQueue(runnable);
      return true;
    } else {
      return false;
    }
  }

  public void pause() {
    getScheduler().pause();
  }

  public void unPause() {
    getScheduler().unPause();
  }

  public boolean isPaused() {
    return getScheduler().isPaused();
  }

  public boolean setPaused(boolean shouldPause) {
    boolean wasPaused = isPaused();
    if (shouldPause) {
      pause();
    } else {
      unPause();
    }
    return wasPaused;
  }

  public void resetScheduler() {
    ShadowMessageQueue sQueue = shadowOf(realObject.getQueue());
    if (this == getShadowMainLooper() || RoboSettings.isUseGlobalScheduler()) {
      sQueue.setScheduler(RuntimeEnvironment.getMasterScheduler());
    } else {
      sQueue.setScheduler(new Scheduler());
    }
  }

  /**
   * Causes all enqueued tasks to be discarded, and pause state to be reset
   */
  public void reset() {
    shadowOf(realObject.getQueue()).reset();
    resetScheduler();

    quit = false;
  }

  /**
   * Returns the {@link org.robolectric.util.Scheduler} that is being used to manage the enqueued tasks.
   * This scheduler is managed by the Looper's associated queue.
   *
   * @return the {@link org.robolectric.util.Scheduler} that is being used to manage the enqueued tasks.
   */
  public Scheduler getScheduler() {
    return shadowOf(realObject.getQueue()).getScheduler();
  }
  
  public void runPaused(Runnable r) {
    boolean wasPaused = setPaused(true);
    try {
      r.run();
    } finally {
      if (!wasPaused) unPause();
    }
  }
}
