package brave.propagation;

import brave.Tracer;
import brave.Tracing;
import brave.internal.Nullable;
import java.io.Closeable;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * This makes a given span the current span by placing it in scope (usually but not always a thread
 * local scope).
 *
 * <p>This type is an SPI, and intended to be used by implementors looking to change thread-local
 * storage, or integrate with other contexts such as logging (MDC).
 *
 * <h3>Design</h3>
 *
 * This design was inspired by com.google.instrumentation.trace.ContextUtils,
 * com.google.inject.servlet.RequestScoper and com.github.kristofa.brave.CurrentSpan
 */
public abstract class CurrentTraceContext {
  /** Returns the current span in scope or null if there isn't one. */
  public abstract @Nullable TraceContext get();

  /**
   * Sets the current span in scope until the returned object is closed. It is a programming
   * error to drop or never close the result. Using try-with-resources is preferred for this reason.
   *
   * @param currentSpan span to place into scope or null to clear the scope
   */
  public abstract Scope newScope(@Nullable TraceContext currentSpan);

  /**
   * Like {@link #newScope(TraceContext)}, except returns {@link Scope#NOOP} if the given context is
   * already in scope. This can reduce overhead when scoping callbacks. However, this will not apply
   * any changes, notably in {@link TraceContext#extra()}. As such, it should be used carefully and
   * only in conditions where redundancy is possible and the intent is primarily to facilitate
   * {@link Tracer#currentSpan}. Most often, this is used to eliminate redundant scopes by wrappers.
   *
   * <p>For example, RxJava includes hooks to wrap types that represent an asynchronous functional
   * composition. For example, {@code flowable.parallel().flatMap(Y).sequential()} Assembly hooks
   * can ensure each stage of this operation can see the initial trace context. However, other tools
   * can also instrument the stages, including vert.x or even agent instrumentation. When wrapping
   * callbacks, it can reduce overhead to use {@code maybeScope} as opposed to {@code newScope}.
   *
   * <p>Generally speaking, this is best used for wrappers, such as executor services or lifecycle
   * hooks, which usually have no current trace context when invoked.
   *
   * <h3>Implementors note</h3>
   * <p>For those overriding this method, you must compare {@link TraceContext#traceIdHigh()},
   * {@link TraceContext#traceId()} and {@link TraceContext#spanId()} to decide if the contexts are
   * equivalent. Due to details of propagation, other data like parent ID are not considered in
   * equivalence checks.
   *
   * @param currentSpan span to place into scope or null to clear the scope
   * @return a new scope object or {@link Scope#NOOP} if the input is already the case
   */
  public Scope maybeScope(@Nullable TraceContext currentSpan) {
    TraceContext currentScope = get();
    if (currentSpan == null) {
      if (currentScope == null) return Scope.NOOP;
      return newScope(null);
    }
    return currentSpan.equals(currentScope) ? Scope.NOOP : newScope(currentSpan);
  }

  /** A span remains in the scope it was bound to until close is called. */
  public interface Scope extends Closeable {
    /**
     * Returned when {@link CurrentTraceContext#maybeScope(TraceContext)} detected scope redundancy.
     */
    Scope NOOP = new Scope() {
      @Override public void close() {
      }

      @Override public String toString() {
        return "NoopScope";
      }
    };

    /** No exceptions are thrown when unbinding a span scope. */
    @Override void close();
  }

  /**
   * Default implementation which is backed by a static thread local.
   *
   * <p>A static thread local ensures we have one context per thread, as opposed to one per thread-
   * tracer. This means all tracer instances will be able to see any tracer's contexts.
   *
   * <p>The trade-off of this (instance-based reference) vs the reverse: trace contexts are not
   * separated by tracer by default. For example, to make a trace invisible to another tracer, you
   * have to use a non-default implementation.
   *
   * <p>Sometimes people make different instances of the tracer just to change configuration like
   * the local service name. If we used a thread-instance approach, none of these would be able to
   * see eachother's scopes. This would break {@link Tracing#currentTracer()} scope visibility in a
   * way few would want to debug. It might be phrased as "MySQL always starts a new trace and I
   * don't know why."
   *
   * <p>If you want a different behavior, use a different subtype of {@link CurrentTraceContext},
   * possibly your own, or raise an issue and explain what your use case is.
   */
  public static final class Default extends CurrentTraceContext {
    static final ThreadLocal<TraceContext> DEFAULT = new ThreadLocal<>();
    // Inheritable as Brave 3's ThreadLocalServerClientAndLocalSpanState was inheritable
    static final InheritableThreadLocal<TraceContext> INHERITABLE = new InheritableThreadLocal<>();

    final ThreadLocal<TraceContext> local;

    /** Uses a non-inheritable static thread local */
    public static CurrentTraceContext create() {
      return new Default(DEFAULT);
    }

    /**
     * Uses an inheritable static thread local which allows arbitrary calls to {@link
     * Thread#start()} to automatically inherit this context. This feature is available as it is was
     * the default in Brave 3, because some users couldn't control threads in their applications.
     *
     * <p>This can be a problem in scenarios such as thread pool expansion, leading to data being
     * recorded in the wrong span, or spans with the wrong parent. If you are impacted by this,
     * switch to {@link #create()}.
     */
    public static CurrentTraceContext inheritable() {
      return new Default(INHERITABLE);
    }

    Default(ThreadLocal<TraceContext> local) {
      if (local == null) throw new NullPointerException("local == null");
      this.local = local;
    }

    @Override public TraceContext get() {
      return local.get();
    }

    @Override public Scope newScope(@Nullable TraceContext currentSpan) {
      final TraceContext previous = local.get();
      local.set(currentSpan);
      class DefaultCurrentTraceContextScope implements Scope {
        @Override public void close() {
          local.set(previous);
        }
      }
      return new DefaultCurrentTraceContextScope();
    }
  }

  /** Wraps the input so that it executes with the same context as now. */
  public <C> Callable<C> wrap(Callable<C> task) {
    final TraceContext invocationContext = get();
    class CurrentTraceContextCallable implements Callable<C> {
      @Override public C call() throws Exception {
        try (Scope scope = maybeScope(invocationContext)) {
          return task.call();
        }
      }
    }
    return new CurrentTraceContextCallable();
  }

  /** Wraps the input so that it executes with the same context as now. */
  public Runnable wrap(Runnable task) {
    final TraceContext invocationContext = get();
    class CurrentTraceContextRunnable implements Runnable {
      @Override public void run() {
        try (Scope scope = maybeScope(invocationContext)) {
          task.run();
        }
      }
    }
    return new CurrentTraceContextRunnable();
  }

  /**
   * Decorates the input such that the {@link #get() current trace context} at the time a task is
   * scheduled is made current when the task is executed.
   */
  public Executor executor(Executor delegate) {
    class CurrentTraceContextExecutor implements Executor {
      @Override public void execute(Runnable task) {
        delegate.execute(CurrentTraceContext.this.wrap(task));
      }
    }
    return new CurrentTraceContextExecutor();
  }

  /**
   * Decorates the input such that the {@link #get() current trace context} at the time a task is
   * scheduled is made current when the task is executed.
   */
  public ExecutorService executorService(ExecutorService delegate) {
    class CurrentTraceContextExecutorService extends brave.internal.WrappingExecutorService {

      @Override protected ExecutorService delegate() {
        return delegate;
      }

      @Override protected <C> Callable<C> wrap(Callable<C> task) {
        return CurrentTraceContext.this.wrap(task);
      }

      @Override protected Runnable wrap(Runnable task) {
        return CurrentTraceContext.this.wrap(task);
      }
    }
    return new CurrentTraceContextExecutorService();
  }
}
