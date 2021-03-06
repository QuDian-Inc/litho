/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho;

import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.annotation.IntDef;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import com.facebook.infer.annotation.ReturnsOwnership;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.infer.annotation.ThreadSafe;

import static com.facebook.litho.ComponentLifecycle.StateUpdate;
import static com.facebook.litho.FrameworkLogEvents.EVENT_LAYOUT_CALCULATE;
import static com.facebook.litho.FrameworkLogEvents.EVENT_PRE_ALLOCATE_MOUNT_CONTENT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_IS_BACKGROUND_LAYOUT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_LOG_TAG;
import static com.facebook.litho.FrameworkLogEvents.PARAM_TREE_DIFF_ENABLED;
import static com.facebook.litho.ThreadUtils.assertHoldsLock;
import static com.facebook.litho.ThreadUtils.assertMainThread;
import static com.facebook.litho.ThreadUtils.isMainThread;

/**
 * Represents a tree of components and controls their life cycle. ComponentTree takes in a single
 * root component and recursively invokes its OnCreateLayout to create a tree of components.
 * ComponentTree is responsible for refreshing the mounted state of a component with new props.
 *
 * The usual use case for {@link ComponentTree} is:
 * <code>
 * ComponentTree component = ComponentTree.create(context, MyComponent.create());
 * myHostView.setRoot(component);
 * <code/>
 */
@ThreadSafe
public class ComponentTree {

  private static final String TAG = ComponentTree.class.getSimpleName();
  private static final int SIZE_UNINITIALIZED = -1;
  // MainThread Looper messages:
  private static final int MESSAGE_WHAT_BACKGROUND_LAYOUT_STATE_UPDATED = 1;
  private static final String DEFAULT_LAYOUT_THREAD_NAME = "ComponentLayoutThread";
  private static final int DEFAULT_LAYOUT_THREAD_PRIORITY = Process.THREAD_PRIORITY_BACKGROUND;

  private static final int SCHEDULE_NONE = 0;
  private static final int SCHEDULE_LAYOUT_ASYNC = 1;
  private static final int SCHEDULE_LAYOUT_SYNC = 2;
  private LithoDebugInfo mLithoDebugInfo;
  private boolean mReleased;

  @IntDef({SCHEDULE_NONE, SCHEDULE_LAYOUT_ASYNC, SCHEDULE_LAYOUT_SYNC})
  @Retention(RetentionPolicy.SOURCE)
  private @interface PendingLayoutCalculation {}

  private static final AtomicInteger sIdGenerator = new AtomicInteger(0);
  private static final Handler sMainThreadHandler = new ComponentMainThreadHandler();
  // Do not access sDefaultLayoutThreadLooper directly, use getDefaultLayoutThreadLooper().
  @GuardedBy("ComponentTree.class")
  private static volatile Looper sDefaultLayoutThreadLooper;

  private static final ThreadLocal<WeakReference<Handler>> sSyncStateUpdatesHandler =
      new ThreadLocal<>();

  // Helpers to track view visibility when we are incrementally
  // mounting and partially invalidating
  private static final int[] sCurrentLocation = new int[2];
  private static final int[] sParentLocation = new int[2];
  private static final Rect sParentBounds = new Rect();

  private final Runnable mCalculateLayoutRunnable = new Runnable() {
    @Override
    public void run() {
      calculateLayout(null, false);
    }
  };
  private final Runnable mAnimatedCalculateLayoutRunnable = new Runnable() {
    @Override
    public void run() {
      calculateLayout(null, true);
    }
  };
  private final Runnable mPreAllocateMountContentRunnable = new Runnable() {
    @Override
    public void run() {
      preAllocateMountContent();
    }
  };

  private final Runnable mUpdateStateSyncRunnable = new Runnable() {
    @Override
    public void run() {
      updateStateInternal(false);
    }
  };

  private final ComponentContext mContext;
  private final boolean mCanPrefetchDisplayLists;
  private final boolean mCanCacheDrawingDisplayLists;
  private final boolean mShouldClipChildren;

  // These variables are only accessed from the main thread.
  @ThreadConfined(ThreadConfined.UI)
  private boolean mIsMounting;
  @ThreadConfined(ThreadConfined.UI)
  private final boolean mIncrementalMountEnabled;
  @ThreadConfined(ThreadConfined.UI)
  private final boolean mIsLayoutDiffingEnabled;
  @ThreadConfined(ThreadConfined.UI)
  private boolean mIsAttached;
  @ThreadConfined(ThreadConfined.UI)
  private final boolean mIsAsyncUpdateStateEnabled;
  @ThreadConfined(ThreadConfined.UI)
  private LithoView mLithoView;
  @ThreadConfined(ThreadConfined.UI)
  private LayoutHandler mLayoutThreadHandler;

  @GuardedBy("this")
  private boolean mHasViewMeasureSpec;

  // TODO(6606683): Enable recycling of mComponent.
  // We will need to ensure there are no background threads referencing mComponent. We'll need
  // to keep a reference count or something. :-/
  @GuardedBy("this")
  private Component<?> mRoot;

  @GuardedBy("this")
  private int mWidthSpec = SIZE_UNINITIALIZED;

  @GuardedBy("this")
  private int mHeightSpec = SIZE_UNINITIALIZED;

  // This is written to only by the main thread with the lock held, read from the main thread with
  // no lock held, or read from any other thread with the lock held.
  private LayoutState mMainThreadLayoutState;

  // The semantics here are tricky. Whenever you transfer mBackgroundLayoutState to a local that
  // will be accessed outside of the lock, you must set mBackgroundLayoutState to null to ensure
  // that the current thread alone has access to the LayoutState, which is single-threaded.
  @GuardedBy("this")
  private LayoutState mBackgroundLayoutState;

  @GuardedBy("this")
  private StateHandler mStateHandler;

  @ThreadConfined(ThreadConfined.UI)
  private RenderState mPreviousRenderState;

  @ThreadConfined(ThreadConfined.UI)
  private boolean mPreviousRenderStateSetFromBuilder = false;

  private final Object mLayoutLock;

  protected final int mId;

  @GuardedBy("this")
  private boolean mIsMeasuring;
  @PendingLayoutCalculation
  @GuardedBy("this")
  private int mScheduleLayoutAfterMeasure;

  // This flag is so we use the correct shouldAnimateTransitions flag when calculating
  // the LayoutState in measure -- we should respect the most recent setRoot* call.
  private volatile boolean mLastShouldAnimateTransitions;

  public static Builder create(ComponentContext context, Component.Builder<?> root) {
    return create(context, root.build());
  }

  public static Builder create(ComponentContext context, Component<?> root) {
    return ComponentsPools.acquireComponentTreeBuilder(context, root);
  }

  protected ComponentTree(Builder builder) {
    mContext = ComponentContext.withComponentTree(builder.context, this);
    mRoot = builder.root;

    mIncrementalMountEnabled = builder.incrementalMountEnabled;
    mIsLayoutDiffingEnabled = builder.isLayoutDiffingEnabled;
    mLayoutThreadHandler = builder.layoutThreadHandler;
    mLayoutLock = builder.layoutLock;
    mIsAsyncUpdateStateEnabled = builder.asyncStateUpdates;
    mCanPrefetchDisplayLists = builder.canPrefetchDisplayLists;
    mCanCacheDrawingDisplayLists = builder.canCacheDrawingDisplayLists;
    mShouldClipChildren = builder.shouldClipChildren;

    if (mLayoutThreadHandler == null) {
      mLayoutThreadHandler = new DefaultLayoutHandler(getDefaultLayoutThreadLooper());
    }

    final StateHandler builderStateHandler = builder.stateHandler;
    mStateHandler = builderStateHandler == null
        ? StateHandler.acquireNewInstance(null)
        : builderStateHandler;

    if (builder.previousRenderState != null) {
      mPreviousRenderState = builder.previousRenderState;
      mPreviousRenderStateSetFromBuilder = true;
    }

    if (builder.overrideComponentTreeId != -1) {
      mId = builder.overrideComponentTreeId;
    } else {
      mId = generateComponentTreeId();
    }
  }

  @ThreadConfined(ThreadConfined.UI)
  LayoutState getMainThreadLayoutState() {
    return mMainThreadLayoutState;
  }

  @VisibleForTesting
  protected LayoutState getBackgroundLayoutState() {
    return mBackgroundLayoutState;
  }

  /**
   * Picks the best LayoutState and sets it in mMainThreadLayoutState. The return value
   * is a LayoutState that must be released (after the lock is released). This
   * awkward contract is necessary to ensure thread-safety.
   */
  @CheckReturnValue
  @ReturnsOwnership
  @ThreadConfined(ThreadConfined.UI)
  private LayoutState setBestMainThreadLayoutAndReturnOldLayout() {
    assertHoldsLock(this);

    // If everything matches perfectly then we prefer mMainThreadLayoutState
    // because that means we don't need to remount.
    boolean isMainThreadLayoutBest;
    if (isCompatibleComponentAndSpec(mMainThreadLayoutState)) {
      isMainThreadLayoutBest = true;
    } else if (isCompatibleSpec(mBackgroundLayoutState, mWidthSpec, mHeightSpec)
        || !isCompatibleSpec(mMainThreadLayoutState, mWidthSpec, mHeightSpec)) {
      // If mMainThreadLayoutState isn't a perfect match, we'll prefer
      // mBackgroundLayoutState since it will have the more recent create.
      isMainThreadLayoutBest = false;
    } else {
      // If the main thread layout is still compatible size-wise, and the
      // background one is not, then we'll do nothing. We want to keep the same
      // main thread layout so that we don't force main thread re-layout.
      isMainThreadLayoutBest = true;
    }

    if (isMainThreadLayoutBest) {
      // We don't want to hold onto mBackgroundLayoutState since it's unlikely
      // to ever be used again. We return mBackgroundLayoutState to indicate it
      // should be released after exiting the lock.
      LayoutState toRelease = mBackgroundLayoutState;
      mBackgroundLayoutState = null;
      return toRelease;
    } else {
      // Since we are changing layout states we'll need to remount.
      if (mLithoView != null) {
        mLithoView.setMountStateDirty();
      }

      LayoutState toRelease = mMainThreadLayoutState;
      mMainThreadLayoutState = mBackgroundLayoutState;
      mBackgroundLayoutState = null;

      return toRelease;
    }
  }

  private void backgroundLayoutStateUpdated() {
    assertMainThread();

    // If we aren't attached, then we have nothing to do. We'll handle
    // everything in onAttach.
    if (!mIsAttached) {
      return;
    }

    LayoutState toRelease;
    boolean layoutStateUpdated;
    int componentRootId;
    synchronized (this) {
      if (mRoot == null) {
        // We have been released. Abort.
        return;
      }

      LayoutState oldMainThreadLayoutState = mMainThreadLayoutState;
      toRelease = setBestMainThreadLayoutAndReturnOldLayout();
      layoutStateUpdated = (mMainThreadLayoutState != oldMainThreadLayoutState);
      componentRootId = mRoot.getId();
    }

    if (toRelease != null) {
      toRelease.releaseRef();
      toRelease = null;
    }

    if (!layoutStateUpdated) {
      return;
    }

    // We defer until measure if we don't yet have a width/height
    int viewWidth = mLithoView.getMeasuredWidth();
    int viewHeight = mLithoView.getMeasuredHeight();
    if (viewWidth == 0 && viewHeight == 0) {
      // The host view has not been measured yet.
      return;
    }

    final boolean needsAndroidLayout =
        !isCompatibleComponentAndSize(
            mMainThreadLayoutState,
            componentRootId,
            viewWidth,
            viewHeight);

    if (needsAndroidLayout) {
      mLithoView.requestLayout();
    } else {
      mountComponentIfDirty();
    }
  }

  void attach() {
    assertMainThread();

    if (mLithoView == null) {
      throw new IllegalStateException("Trying to attach a ComponentTree without a set View");
    }

    LayoutState toRelease;
    int componentRootId;
    synchronized (this) {
      // We need to track that we are attached regardless...
      mIsAttached = true;

      // ... and then we do state transfer
      toRelease = setBestMainThreadLayoutAndReturnOldLayout();
      componentRootId = mRoot.getId();
    }

    if (toRelease != null) {
      toRelease.releaseRef();
      toRelease = null;
    }

    // We defer until measure if we don't yet have a width/height
    int viewWidth = mLithoView.getMeasuredWidth();
    int viewHeight = mLithoView.getMeasuredHeight();
    if (viewWidth == 0 && viewHeight == 0) {
      // The host view has not been measured yet.
      return;
    }

    final boolean needsAndroidLayout =
        !isCompatibleComponentAndSize(
            mMainThreadLayoutState,
            componentRootId,
            viewWidth,
            viewHeight);

    if (needsAndroidLayout || mLithoView.isMountStateDirty()) {
      mLithoView.requestLayout();
    } else {
      mLithoView.rebind();
    }
  }

  private static boolean hasSameBaseContext(Context context1, Context context2) {
    return getBaseContext(context1) == getBaseContext(context2);
  }

  private static Context getBaseContext(Context context) {
    Context baseContext = context;
    while (baseContext instanceof ContextWrapper) {
      baseContext = ((ContextWrapper) baseContext).getBaseContext();
    }

    return baseContext;
  }

  @ThreadConfined(ThreadConfined.UI)
  boolean isMounting() {
    return mIsMounting;
  }

  private boolean mountComponentIfDirty() {
    if (mLithoView.isMountStateDirty()) {
      if (mIncrementalMountEnabled) {
        incrementalMountComponent();
      } else {
        mountComponent(null);
      }

      return true;
    }

    return false;
  }

  void incrementalMountComponent() {
    assertMainThread();

    if (!mIncrementalMountEnabled) {
      throw new IllegalStateException("Calling incrementalMountComponent() but incremental mount" +
          " is not enabled");
    }

    // Per ComponentTree visible area. Because LithoViews can be nested and mounted
    // not in "depth order", this variable cannot be static.
    final Rect currentVisibleArea = ComponentsPools.acquireRect();

    if (getVisibleRect(currentVisibleArea)) {
      mountComponent(currentVisibleArea);
    }
    // if false: no-op, doesn't have visible area, is not ready or not attached
    ComponentsPools.release(currentVisibleArea);
  }

  private boolean getVisibleRect(Rect visibleBounds) {
    assertMainThread();

    getLocationAndBoundsOnScreen(mLithoView, sCurrentLocation, visibleBounds);

    final ViewParent viewParent = mLithoView.getParent();
    if (viewParent instanceof View) {
      View parent = (View) viewParent;
      getLocationAndBoundsOnScreen(parent, sParentLocation, sParentBounds);
      if (!visibleBounds.setIntersect(visibleBounds, sParentBounds)) {
        return false;
      }
    }

    visibleBounds.offset(-sCurrentLocation[0], -sCurrentLocation[1]);

    return true;
  }

  private static void getLocationAndBoundsOnScreen(View view, int[] location, Rect bounds) {
    assertMainThread();

    view.getLocationOnScreen(location);
    bounds.set(
        location[0],
        location[1],
        location[0] + view.getWidth(),
        location[1] + view.getHeight());
  }

  void mountComponent(Rect currentVisibleArea) {
    assertMainThread();

    final boolean isDirtyMount = mLithoView.isMountStateDirty();

    mIsMounting = true;

    if (isDirtyMount) {
      applyPreviousRenderInfo(mMainThreadLayoutState);
    }

    // currentVisibleArea null or empty => mount all
    mLithoView.mount(mMainThreadLayoutState, currentVisibleArea);

    if (isDirtyMount) {
      recordRenderInfo(mMainThreadLayoutState);
    }

    mIsMounting = false;
  }

  private void applyPreviousRenderInfo(LayoutState layoutState) {
    final List<Component> components = layoutState.getComponentsNeedingPreviousRenderInfo();
    if (components == null || components.isEmpty()) {
      return;
    }

    if (mPreviousRenderState == null) {
      return;
    }

    mPreviousRenderState.applyPreviousRenderInfo(components);
  }

  private void recordRenderInfo(LayoutState layoutState) {
    final List<Component> components = layoutState.getComponentsNeedingPreviousRenderInfo();
    if (components == null || components.isEmpty()) {
      return;
    }

    if (mPreviousRenderState == null) {
      mPreviousRenderState = ComponentsPools.acquireRenderState();
    }

    mPreviousRenderState.recordRenderInfo(components);
  }

  void detach() {
    assertMainThread();

    synchronized (this) {
      mIsAttached = false;
      mHasViewMeasureSpec = false;
    }
  }

  /**
   * Set a new LithoView to this ComponentTree checking that they have the same context and
   * clear the ComponentTree reference from the previous LithoView if any.
   * Be sure this ComponentTree is detach first.
   */
  void setLithoView(@NonNull LithoView view) {
    assertMainThread();

    // It's possible that the view associated with this ComponentTree was recycled but was
    // never detached. In all cases we have to make sure that the old references between
    // lithoView and componentTree are reset.
    if (mIsAttached) {
      if (mLithoView != null) {
        mLithoView.setComponentTree(null);
      } else {
        detach();
      }
    } else if (mLithoView != null) {
      // Remove the ComponentTree reference from a previous view if any.
      mLithoView.clearComponentTree();
    }

    if (!hasSameBaseContext(view.getContext(), mContext)) {
      // This would indicate bad things happening, like leaking a context.
      throw new IllegalArgumentException(
          "Base view context differs, view context is: " + view.getContext() +
              ", ComponentTree context is: " + mContext);
    }

    mLithoView = view;
  }

  void clearLithoView() {
    assertMainThread();

    // Crash if the ComponentTree is mounted to a view.
    if (mIsAttached) {
      throw new IllegalStateException(
          "Clearing the LithoView while the ComponentTree is attached");
    }

    mLithoView = null;
  }

  void measure(int widthSpec, int heightSpec, int[] measureOutput, boolean forceLayout) {
    assertMainThread();

    Component component = null;
    LayoutState toRelease;
    synchronized (this) {
      mIsMeasuring = true;

      // This widthSpec/heightSpec is fixed until the view gets detached.
      mWidthSpec = widthSpec;
      mHeightSpec = heightSpec;
      mHasViewMeasureSpec = true;

      toRelease = setBestMainThreadLayoutAndReturnOldLayout();

      if (forceLayout || !isCompatibleComponentAndSpec(mMainThreadLayoutState)) {
        // Neither layout was compatible and we have to perform a layout.
        // Since outputs get set on the same object during the lifecycle calls,
        // we need to copy it in order to use it concurrently.
        component = mRoot.makeShallowCopy();
      }
    }

    if (toRelease != null) {
      toRelease.releaseRef();
      toRelease = null;
    }

    if (component != null) {
      // TODO: We should re-use the existing CSSNodeDEPRECATED tree instead of re-creating it.
      if (mMainThreadLayoutState != null) {
        // It's beneficial to delete the old layout state before we start creating a new one since
        // we'll be able to re-use some of the layout nodes.
        LayoutState localLayoutState;
        synchronized (this) {
          localLayoutState = mMainThreadLayoutState;
          mMainThreadLayoutState = null;
        }
        localLayoutState.releaseRef();
      }

      // We have no layout that matches the given spec, so we need to compute it on the main thread.
      LayoutState localLayoutState = calculateLayoutState(
          mLayoutLock,
          mContext,
          component,
          widthSpec,
          heightSpec,
          mIsLayoutDiffingEnabled,
          mLastShouldAnimateTransitions,
          null);

      final StateHandler layoutStateStateHandler =
          localLayoutState.consumeStateHandler();
      synchronized (this) {
        if (layoutStateStateHandler != null) {
          mStateHandler.commit(layoutStateStateHandler);
          ComponentsPools.release(layoutStateStateHandler);
        }

        mMainThreadLayoutState = localLayoutState;
        localLayoutState = null;
      }

      // We need to force remount on layout
      mLithoView.setMountStateDirty();
    }

    measureOutput[0] = mMainThreadLayoutState.getWidth();
    measureOutput[1] = mMainThreadLayoutState.getHeight();

    int layoutScheduleType = SCHEDULE_NONE;
    Component root = null;

    synchronized (this) {
      mIsMeasuring = false;

      if (mScheduleLayoutAfterMeasure != SCHEDULE_NONE) {
        layoutScheduleType = mScheduleLayoutAfterMeasure;
        mScheduleLayoutAfterMeasure = SCHEDULE_NONE;
        root = mRoot.makeShallowCopy();
      }
    }

    if (layoutScheduleType != SCHEDULE_NONE) {
      // shouldAnimateTransitions - This is a scheduled layout from a state update, so we animate it
      setRootAndSizeSpecInternal(
          root,
          SIZE_UNINITIALIZED,
          SIZE_UNINITIALIZED,
          layoutScheduleType == SCHEDULE_LAYOUT_ASYNC,
          true /* = shouldAnimateTransitions */,
          null /*output */);
    }
  }

  /**
   * Returns {@code true} if the layout call mounted the component.
   */
  boolean layout() {
    assertMainThread();

    return mountComponentIfDirty();
  }

  /**
   * Returns whether incremental mount is enabled or not in this component.
   */
  public boolean isIncrementalMountEnabled() {
    return mIncrementalMountEnabled;
  }

  synchronized Component getRoot() {
    return mRoot;
  }

  /**
   * Update the root component. This can happen in both attached and detached states. In each case
   * we will run a layout and then proxy a message to the main thread to cause a
   * relayout/invalidate.
   */
  public void setRoot(Component<?> rootComponent) {
    setRoot(rootComponent, false);
  }

  /**
   * Sets a new component root, specifying whether to animate transitions where transition
   * animations have been specified.
   *
   * @see #setRoot
   */
  public void setRoot(Component<?> rootComponent, boolean shouldAnimateTransitions) {
    if (rootComponent == null) {
      throw new IllegalArgumentException("Root component can't be null");
    }

    setRootAndSizeSpecInternal(
        rootComponent,
        SIZE_UNINITIALIZED,
        SIZE_UNINITIALIZED,
        false /* isAsync */,
        shouldAnimateTransitions,
        null /* output */);
  }

  /**
   * Schedule to asynchronizely pre-allocate the mount content of all MountSpec in this tree.
   * Must be called after layout is created, or after async layout is scheduled.
   */
  @ThreadSafe(enableChecks = false)
  public void preAllocateMountContentAsync() {
    mLayoutThreadHandler.removeCallbacks(mPreAllocateMountContentRunnable);
    mLayoutThreadHandler.post(mPreAllocateMountContentRunnable);
  }

  /**
   * Pre-allocate the mount content of all MountSpec in this tree.
   * Must be called after layout is created.
   */
  @ThreadSafe(enableChecks = false)
  public void preAllocateMountContent() {
    final LayoutState toPrePopulate;

    // Cancel any scheduled preallocate requests we might have in the background queue
    // since we are starting the preallocation.
    mLayoutThreadHandler.removeCallbacks(mPreAllocateMountContentRunnable);

    synchronized (this) {
      if (mMainThreadLayoutState != null) {
        toPrePopulate = mMainThreadLayoutState;
      } else {
        toPrePopulate = mBackgroundLayoutState;
      }
    }
    if (toPrePopulate == null) {
      return;
    }
    toPrePopulate.acquireRef();

    final ComponentsLogger logger = mContext.getLogger();
    LogEvent event = null;
    if (logger != null) {
      event = logger.newPerformanceEvent(EVENT_PRE_ALLOCATE_MOUNT_CONTENT);
      event.addParam(PARAM_LOG_TAG, mContext.getLogTag());
    }

    toPrePopulate.preAllocateMountContent();

    if (logger != null) {
      logger.log(event);
    }

    toPrePopulate.releaseRef();
  }

  public void setRootAsync(Component<?> rootComponent) {
    if (rootComponent == null) {
      throw new IllegalArgumentException("Root component can't be null");
    }

    setRootAndSizeSpecInternal(
        rootComponent,
        SIZE_UNINITIALIZED,
        SIZE_UNINITIALIZED,
        true /* isAsync */,
        false /* shouldAnimateTransitions */,
        null /* output */);
  }

  synchronized void updateStateLazy(String componentKey, StateUpdate stateUpdate) {
    if (mRoot == null) {
      return;
    }

    mStateHandler.queueStateUpdate(componentKey, stateUpdate);
  }

  void updateState(String componentKey, StateUpdate stateUpdate) {

    synchronized (this) {
      if (mRoot == null) {
        return;
      }

      mStateHandler.queueStateUpdate(componentKey, stateUpdate);
    }

    Looper looper = Looper.myLooper();

    if (looper == null) {
      Log.w(
          TAG,
          "You cannot update state synchronously from a thread without a looper, " +
              "using the default background layout thread instead");
      mLayoutThreadHandler.removeCallbacks(mUpdateStateSyncRunnable);
      mLayoutThreadHandler.post(mUpdateStateSyncRunnable);
      return;
    }

    Handler handler;

    synchronized (this) {
      final WeakReference<Handler> handlerWr = sSyncStateUpdatesHandler.get();
      if (handlerWr != null && handlerWr.get() != null) {
        handler = handlerWr.get();
        handler.removeCallbacks(mUpdateStateSyncRunnable);
      } else {
        handler = new Handler(looper);
        sSyncStateUpdatesHandler.set(new WeakReference<>(handler));
      }
    }

    handler.post(mUpdateStateSyncRunnable);
  }

  void updateStateAsync(String componentKey, StateUpdate stateUpdate) {
    if (!mIsAsyncUpdateStateEnabled) {
        throw new RuntimeException("Triggering async state updates on this component tree is " +
            "disabled, use sync state updates.");
    }

    synchronized (this) {
      if (mRoot == null) {
        return;
      }

      mStateHandler.queueStateUpdate(componentKey, stateUpdate);
    }

    updateStateInternal(true);
  }

  void updateStateInternal(boolean isAsync) {

    final Component<?> root;

    synchronized (this) {

      if (mIsMeasuring) {
        // If the layout calculation was already scheduled to happen synchronously let's just go
        // with a sync layout calculation.
        if (mScheduleLayoutAfterMeasure == SCHEDULE_LAYOUT_SYNC) {
          return;
        }

        mScheduleLayoutAfterMeasure = isAsync ? SCHEDULE_LAYOUT_ASYNC : SCHEDULE_LAYOUT_SYNC;
        return;
      }

      root = mRoot.makeShallowCopy();
    }

    setRootAndSizeSpecInternal(
        root,
        SIZE_UNINITIALIZED,
        SIZE_UNINITIALIZED,
        isAsync,
        true /* shouldAnimateTransitions */,
        null /*output */);
  }

  /**
   * Update the width/height spec. This is useful if you are currently detached and are responding
   * to a configuration change. If you are currently attached then the HostView is the source of
   * truth for width/height, so this call will be ignored.
   */
  public void setSizeSpec(int widthSpec, int heightSpec) {
    setSizeSpec(widthSpec, heightSpec, null);
  }

  /**
   * Same as {@link #setSizeSpec(int, int)} but fetches the resulting width/height
   * in the given {@link Size}.
   */
  public void setSizeSpec(int widthSpec, int heightSpec, Size output) {
    setRootAndSizeSpecInternal(
        null,
        widthSpec,
        heightSpec,
        false /* isAsync */,
        false /* shouldAnimateTransitions */,
        output /* output */);
  }

  public void setSizeSpecAsync(int widthSpec, int heightSpec) {
    setRootAndSizeSpecInternal(
        null,
        widthSpec,
        heightSpec,
        true /* isAsync */,
        false /* shouldAnimateTransitions */,
        null /* output */);
  }

  /**
   * Compute asynchronously a new layout with the given component root and sizes
   */
  public void setRootAndSizeSpecAsync(Component<?> root, int widthSpec, int heightSpec) {
    setRootAndSizeSpecAsync(root, widthSpec, heightSpec, false);
  }

  /**
   * Like {@link #setRootAndSizeSpecAsync}, allowing specification of whether transitions should be
   * animated where transition animations have been specified.
   */
  public void setRootAndSizeSpecAsync(
      Component<?> root,
      int widthSpec,
      int heightSpec,
      boolean shouldAnimateTransitions) {
    if (root == null) {
      throw new IllegalArgumentException("Root component can't be null");
    }

    setRootAndSizeSpecInternal(
        root,
        widthSpec,
        heightSpec,
        true /* isAsync */,
        shouldAnimateTransitions,
        null /* output */);
  }

  /**
   * Compute a new layout with the given component root and sizes
   */
  public void setRootAndSizeSpec(Component<?> root, int widthSpec, int heightSpec) {
    setRootAndSizeSpec(root, widthSpec, heightSpec, false);
  }

  /**
   * Like {@link #setRootAndSizeSpec}, allowing specification of whether transitions should be
   * animated where transition animations have been specified.
   */
  public void setRootAndSizeSpec(
      Component<?> root,
      int widthSpec,
      int heightSpec,
      boolean shouldAnimateTransitions) {
    if (root == null) {
      throw new IllegalArgumentException("Root component can't be null");
    }

    setRootAndSizeSpecInternal(
        root,
        widthSpec,
        heightSpec,
        false /* isAsync */,
        shouldAnimateTransitions,
        null /* output */);
  }

  public void setRootAndSizeSpec(Component<?> root, int widthSpec, int heightSpec, Size output) {
    if (root == null) {
      throw new IllegalArgumentException("Root component can't be null");
    }

    setRootAndSizeSpecInternal(
        root,
        widthSpec,
        heightSpec,
        false /* isAsync */,
        false /* shouldAnimateTransitions */,
        output);
  }

  /**
   * @return the {@link LithoView} associated with this ComponentTree if any.
   */
  @Keep
  @Nullable
  public LithoView getLithoView() {
    assertMainThread();
    return mLithoView;
  }

  /**
   * Provides a new instance from the StateHandler pool that is initialized with the information
   * from the StateHandler currently held by the ComponentTree. Once the state updates have been
   * applied and we are back in the main thread the state handler gets released to the pool.
   * @return a copy of the state handler instance held by ComponentTree.
   */
  public synchronized StateHandler getStateHandler() {
    return StateHandler.acquireNewInstance(mStateHandler);
  }

  /**
   * Takes ownership of the {@link RenderState} object from this ComponentTree - this allows the
   * RenderState to be persisted somewhere and then set back on another ComponentTree using the
   * {@link Builder}. See {@link RenderState} for more information on the purpose of this object.
   */
  @ThreadConfined(ThreadConfined.UI)
  public RenderState consumePreviousRenderState() {
    final RenderState previousRenderState = mPreviousRenderState;

    mPreviousRenderState = null;
    mPreviousRenderStateSetFromBuilder = false;
    return previousRenderState;
  }

  private void setRootAndSizeSpecInternal(
      Component<?> root,
      int widthSpec,
      int heightSpec,
      boolean isAsync,
      boolean shouldAnimateTransitions,
      Size output) {

    synchronized (this) {

      mLastShouldAnimateTransitions = shouldAnimateTransitions;
      final Map<String, List<StateUpdate>> pendingStateUpdates =
          mStateHandler.getPendingStateUpdates();
      if (pendingStateUpdates != null && pendingStateUpdates.size() > 0 && root != null) {
        root = root.makeShallowCopyWithNewId();
      }
      final boolean rootInitialized = root != null;
      final boolean widthSpecInitialized = widthSpec != SIZE_UNINITIALIZED;
      final boolean heightSpecInitialized = heightSpec != SIZE_UNINITIALIZED;

      if (mHasViewMeasureSpec && !rootInitialized) {
        // It doesn't make sense to specify the width/height while the HostView is attached and it
        // has been measured. We do not throw an Exception only because there can be race conditions
        // that can cause this to happen. In such race conditions, ignoring the setSizeSpec call is
        // the right thing to do.
        return;
      }

      final boolean widthSpecDidntChange = !widthSpecInitialized || widthSpec == mWidthSpec;
      final boolean heightSpecDidntChange = !heightSpecInitialized || heightSpec == mHeightSpec;
      final boolean sizeSpecDidntChange = widthSpecDidntChange && heightSpecDidntChange;
      final LayoutState mostRecentLayoutState =
          mBackgroundLayoutState != null ? mBackgroundLayoutState : mMainThreadLayoutState;
      final boolean allSpecsWereInitialized =
          widthSpecInitialized &&
          heightSpecInitialized &&
          mWidthSpec != SIZE_UNINITIALIZED &&
          mHeightSpec != SIZE_UNINITIALIZED;
      final boolean sizeSpecsAreCompatible =
          sizeSpecDidntChange ||
          (allSpecsWereInitialized &&
          mostRecentLayoutState != null &&
          LayoutState.hasCompatibleSizeSpec(
              mWidthSpec,
              mHeightSpec,
              widthSpec,
              heightSpec,
              mostRecentLayoutState.getWidth(),
              mostRecentLayoutState.getHeight()));
      final boolean rootDidntChange = !rootInitialized || root.getId() == mRoot.getId();

      if (rootDidntChange && sizeSpecsAreCompatible) {
        // The spec and the root haven't changed. Either we have a layout already, or we're
        // currently computing one on another thread.
        if (output != null) {
          output.height = mostRecentLayoutState.getHeight();
          output.width = mostRecentLayoutState.getWidth();
        }
        return;
      }

      if (widthSpecInitialized) {
        mWidthSpec = widthSpec;
      }

      if (heightSpecInitialized) {
        mHeightSpec = heightSpec;
      }

      if (rootInitialized) {
        mRoot = root;
      }
    }

    if (isAsync && output != null) {
      throw new IllegalArgumentException("The layout can't be calculated asynchronously if" +
          " we need the Size back");
    } else if (isAsync) {
      mLayoutThreadHandler.removeCallbacks(mCalculateLayoutRunnable);
      mLayoutThreadHandler.removeCallbacks(mAnimatedCalculateLayoutRunnable);
      mLayoutThreadHandler.post(
          shouldAnimateTransitions ?
              mAnimatedCalculateLayoutRunnable :
              mCalculateLayoutRunnable);
    } else {
      calculateLayout(output, shouldAnimateTransitions);
    }
  }

  /**
   * Calculates the layout.
   * @param output a destination where the size information should be saved
   * @param shouldAnimateTransitions whether component transitions should be animated
   */
  private void calculateLayout(Size output, boolean shouldAnimateTransitions) {
    int widthSpec;
    int heightSpec;
    Component<?> root;
    LayoutState previousLayoutState = null;

    // Cancel any scheduled layout requests we might have in the background queue
    // since we are starting a new layout computation.
    mLayoutThreadHandler.removeCallbacks(mCalculateLayoutRunnable);
    mLayoutThreadHandler.removeCallbacks(mAnimatedCalculateLayoutRunnable);

    synchronized (this) {
      // Can't compute a layout if specs or root are missing
      if (!hasSizeSpec() || mRoot == null) {
        return;
      }

      // Check if we already have a compatible layout.
      if (hasCompatibleComponentAndSpec()) {
        if (output != null) {
          final LayoutState mostRecentLayoutState =
              mBackgroundLayoutState != null ? mBackgroundLayoutState : mMainThreadLayoutState;
          output.width = mostRecentLayoutState.getWidth();
          output.height = mostRecentLayoutState.getHeight();
        }
        return;
      }

      widthSpec = mWidthSpec;
      heightSpec = mHeightSpec;
      root = mRoot.makeShallowCopy();

      if (mMainThreadLayoutState != null) {
        previousLayoutState = mMainThreadLayoutState.acquireRef();
      }
    }

    final ComponentsLogger logger = mContext.getLogger();
    LogEvent layoutEvent = null;
    if (logger != null) {
      layoutEvent = logger.newPerformanceEvent(EVENT_LAYOUT_CALCULATE);
      layoutEvent.addParam(PARAM_LOG_TAG, mContext.getLogTag());
      layoutEvent.addParam(PARAM_TREE_DIFF_ENABLED, String.valueOf(mIsLayoutDiffingEnabled));
      layoutEvent.addParam(PARAM_IS_BACKGROUND_LAYOUT, String.valueOf(!ThreadUtils.isMainThread()));
    }

    LayoutState localLayoutState = calculateLayoutState(
        mLayoutLock,
        mContext,
        root,
        widthSpec,
        heightSpec,
        mIsLayoutDiffingEnabled,
        shouldAnimateTransitions,
        previousLayoutState != null ? previousLayoutState.getDiffTree() : null);

    if (output != null) {
      output.width = localLayoutState.getWidth();
      output.height = localLayoutState.getHeight();
    }

    if (previousLayoutState != null) {
      previousLayoutState.releaseRef();
      previousLayoutState = null;
    }

    boolean layoutStateUpdated = false;
    synchronized (this) {
      // Make sure some other thread hasn't computed a compatible layout in the meantime.
      if (!hasCompatibleComponentAndSpec()
          && isCompatibleSpec(localLayoutState, mWidthSpec, mHeightSpec)) {

        if (localLayoutState != null) {
          final StateHandler layoutStateStateHandler =
              localLayoutState.consumeStateHandler();
          if (layoutStateStateHandler != null) {
            if (mStateHandler != null) { // we could have been released
              mStateHandler.commit(layoutStateStateHandler);
            }
            ComponentsPools.release(layoutStateStateHandler);
          }
        }

        // Set the new layout state, and remember the old layout state so we
        // can release it.
        LayoutState tmp = mBackgroundLayoutState;
        mBackgroundLayoutState = localLayoutState;
        localLayoutState = tmp;
        layoutStateUpdated = true;
      }
    }

    if (localLayoutState != null) {
      localLayoutState.releaseRef();
      localLayoutState = null;
    }

    if (layoutStateUpdated) {
      postBackgroundLayoutStateUpdated();
    }

    if (logger != null) {
      logger.log(layoutEvent);
    }
  }

  /**
   * Transfer mBackgroundLayoutState to mMainThreadLayoutState. This will proxy
   * to the main thread if necessary. If the component/size-spec changes in the
   * meantime, then the transfer will be aborted.
   */
  private void postBackgroundLayoutStateUpdated() {
    if (isMainThread()) {
      // We need to possibly update mMainThreadLayoutState. This call will
      // cause the host view to be invalidated and re-laid out, if necessary.
      backgroundLayoutStateUpdated();
    } else {
      // If we aren't on the main thread, we send a message to the main thread
      // to invoke backgroundLayoutStateUpdated.
      sMainThreadHandler.obtainMessage(MESSAGE_WHAT_BACKGROUND_LAYOUT_STATE_UPDATED, this)
          .sendToTarget();
    }
  }

  /**
   * The contract is that in order to release a ComponentTree, you must do so from the main
   * thread, or guarantee that it will never be accessed from the main thread again. Usually
   * HostView will handle releasing, but if you never attach to a host view, then you should call
   * release yourself.
   */
  public void release() {
    LayoutState mainThreadLayoutState;
    LayoutState backgroundLayoutState;
    synchronized (this) {
      mReleased = true;
      if (mLithoView != null) {
        mLithoView.setComponentTree(null);
      }
      mRoot = null;

      mainThreadLayoutState = mMainThreadLayoutState;
      mMainThreadLayoutState = null;

      backgroundLayoutState = mBackgroundLayoutState;
      mBackgroundLayoutState = null;

      // TODO t15532529
      mStateHandler = null;

      if (mPreviousRenderState != null && !mPreviousRenderStateSetFromBuilder) {
        ComponentsPools.release(mPreviousRenderState);
      }
      mPreviousRenderState = null;
      mPreviousRenderStateSetFromBuilder = false;
    }

    if (mainThreadLayoutState != null) {
      mainThreadLayoutState.releaseRef();
      mainThreadLayoutState = null;
    }

    if (backgroundLayoutState != null) {
      backgroundLayoutState.releaseRef();
      backgroundLayoutState = null;
    }
  }

  private boolean isCompatibleComponentAndSpec(LayoutState layoutState) {
    assertHoldsLock(this);

    return mRoot != null && isCompatibleComponentAndSpec(
        layoutState, mRoot.getId(), mWidthSpec, mHeightSpec);
  }

  // Either the MainThreadLayout or the BackgroundThreadLayout is compatible with the current state.
  private boolean hasCompatibleComponentAndSpec() {
    assertHoldsLock(this);

    return isCompatibleComponentAndSpec(mMainThreadLayoutState)
        || isCompatibleComponentAndSpec(mBackgroundLayoutState);
  }

  private boolean hasSizeSpec() {
    assertHoldsLock(this);

    return mWidthSpec != SIZE_UNINITIALIZED
        && mHeightSpec != SIZE_UNINITIALIZED;
  }

  private static synchronized Looper getDefaultLayoutThreadLooper() {
    if (sDefaultLayoutThreadLooper == null) {
      HandlerThread defaultThread =
          new HandlerThread(DEFAULT_LAYOUT_THREAD_NAME, DEFAULT_LAYOUT_THREAD_PRIORITY);
      defaultThread.start();
      sDefaultLayoutThreadLooper = defaultThread.getLooper();
    }

    return sDefaultLayoutThreadLooper;
  }

  private static boolean isCompatibleSpec(
      LayoutState layoutState, int widthSpec, int heightSpec) {
    return layoutState != null
        && layoutState.isCompatibleSpec(widthSpec, heightSpec)
        && layoutState.isCompatibleAccessibility();
  }

  private static boolean isCompatibleComponentAndSpec(
      LayoutState layoutState, int componentId, int widthSpec, int heightSpec) {
    return layoutState != null
        && layoutState.isCompatibleComponentAndSpec(componentId, widthSpec, heightSpec)
        && layoutState.isCompatibleAccessibility();
  }

  private static boolean isCompatibleComponentAndSize(
      LayoutState layoutState, int componentId, int width, int height) {
    return layoutState != null
        && layoutState.isComponentId(componentId)
        && layoutState.isCompatibleSize(width, height)
        && layoutState.isCompatibleAccessibility();
  }

  public synchronized boolean isReleased() {
    return mReleased;
  }

  public ComponentContext getContext() {
    return mContext;
  }

  private static class ComponentMainThreadHandler extends Handler {
    private ComponentMainThreadHandler() {
      super(Looper.getMainLooper());
    }

    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
        case MESSAGE_WHAT_BACKGROUND_LAYOUT_STATE_UPDATED:
          ComponentTree that = (ComponentTree) msg.obj;

          that.backgroundLayoutStateUpdated();
          break;
        default:
          throw new IllegalArgumentException();
      }
    }
  }

  protected LayoutState calculateLayoutState(
      @Nullable Object lock,
      ComponentContext context,
      Component<?> root,
      int widthSpec,
      int heightSpec,
      boolean diffingEnabled,
      boolean shouldAnimateTransitions,
      @Nullable DiffNode diffNode) {
    final ComponentContext contextWithStateHandler;
    synchronized (this) {
       contextWithStateHandler =
          new ComponentContext(context, StateHandler.acquireNewInstance(mStateHandler));
    }

    if (lock != null) {
      synchronized (lock) {
        return LayoutState.calculate(
            contextWithStateHandler,
            root,
            mId,
            widthSpec,
            heightSpec,
            diffingEnabled,
            shouldAnimateTransitions,
            diffNode,
            mCanPrefetchDisplayLists,
            mCanCacheDrawingDisplayLists,
            mShouldClipChildren);
      }
    } else {
      return LayoutState.calculate(
          contextWithStateHandler,
          root,
          mId,
          widthSpec,
          heightSpec,
          diffingEnabled,
          shouldAnimateTransitions,
          diffNode,
          mCanPrefetchDisplayLists,
          mCanCacheDrawingDisplayLists,
          mShouldClipChildren);
    }
  }

  /**
   * A default {@link LayoutHandler} that will use a {@link Handler} with a {@link Thread}'s
   * {@link Looper}.
   */
  private static class DefaultLayoutHandler extends Handler implements LayoutHandler {
    private DefaultLayoutHandler(Looper threadLooper) {
      super(threadLooper);
    }
  }

  public static int generateComponentTreeId() {
    return sIdGenerator.getAndIncrement();
  }

  /**
   * A builder class that can be used to create a {@link ComponentTree}.
   */
  public static class Builder {

    // required
    private ComponentContext context;
    private Component<?> root;

    // optional
    private boolean incrementalMountEnabled = true;
    private boolean isLayoutDiffingEnabled = true;
    private LayoutHandler layoutThreadHandler;
    private Object layoutLock;
    private StateHandler stateHandler;
    private RenderState previousRenderState;
    private boolean asyncStateUpdates = true;
    private int overrideComponentTreeId = -1;
    private boolean canPrefetchDisplayLists = false;
    private boolean canCacheDrawingDisplayLists = false;
    private boolean shouldClipChildren = true;

    protected Builder() {
    }

    protected Builder(ComponentContext context, Component<?> root) {
      init(context, root);
    }

    protected void init(ComponentContext context, Component<?> root) {
      this.context = context;
      this.root = root;
    }

    protected void release() {
      context = null;
      root = null;

      incrementalMountEnabled = true;
      isLayoutDiffingEnabled = true;
      layoutThreadHandler = null;
      layoutLock = null;
      stateHandler = null;
      previousRenderState = null;
      asyncStateUpdates = true;
      overrideComponentTreeId = -1;
      canPrefetchDisplayLists = false;
      canCacheDrawingDisplayLists = false;
      shouldClipChildren = true;
    }

    /**
     * Whether or not to enable the incremental mount optimization. True by default.
     * In order to use incremental mount you should disable mount diffing.
     *
     * @Deprecated We will remove this option soon, please consider turning it on (which is on by
     * default)
     */
    public Builder incrementalMount(boolean isEnabled) {
      incrementalMountEnabled = isEnabled;
      return this;
    }

    /**
     * Whether or not to enable layout tree diffing. This will reduce the cost of
     * updates at the expense of using extra memory. True by default.
     *
     * @Deprecated We will remove this option soon, please consider turning it on (which is on by
     * default)
     */
    public Builder layoutDiffing(boolean enabled) {
      isLayoutDiffingEnabled = enabled;
      return this;
    }

    /**
     * Specify the looper to use for running layouts on. Note that in rare cases
     * layout must run on the UI thread. For example, if you rotate the screen,
     * we must measure on the UI thread. If you don't specify a Looper here, the
     * Components default Looper will be used.
     */
    public Builder layoutThreadLooper(Looper looper) {
      if (looper != null) {
        layoutThreadHandler = new DefaultLayoutHandler(looper);
      }

      return this;
    }

    /**
     * Specify the looper to use for running layouts on. Note that in rare cases
     * layout must run on the UI thread. For example, if you rotate the screen,
     * we must measure on the UI thread. If you don't specify a Looper here, the
     * Components default Looper will be used.
     */
    public Builder layoutThreadHandler(LayoutHandler handler) {
      layoutThreadHandler = handler;
      return this;
    }

    /**
     * Specify a lock to be acquired during layout. This is an advanced feature
     * that can lead to deadlock if you don't know what you are doing.
     */
    public Builder layoutLock(Object layoutLock) {
      this.layoutLock = layoutLock;
      return this;
    }

    /**
     * Specify an initial state handler object that the ComponentTree can use to set the current
     * values for states.
     */
    public Builder stateHandler(StateHandler stateHandler) {
      this.stateHandler = stateHandler;
      return this;
    }

    /**
     * Specify an existing previous render state that the ComponentTree can use to set the current
     * values for providing previous versions of @Prop/@State variables.
     */
    public Builder previousRenderState(RenderState previousRenderState) {
      this.previousRenderState = previousRenderState;
      return this;
    }

    /**
     * Specify whether the ComponentTree allows async state updates. This is enabled by default.
     */
    public Builder asyncStateUpdates(boolean enabled) {
      this.asyncStateUpdates = enabled;
      return this;
    }

    /**
     * Gives the ability to override the auto-generated ComponentTree id: this is generally not
     * useful in the majority of circumstances, so don't use it unless you really know what you're
     * doing.
     */
    public Builder overrideComponentTreeId(int overrideComponentTreeId) {
      this.overrideComponentTreeId = overrideComponentTreeId;
      return this;
    }

    /**
     * Specify whether the ComponentTree allows to prefetch display lists of its components
     * on idle time of UI thread.
     *
     * NOTE: To make display lists prefetching work, besides setting this flag
     * {@link com.facebook.litho.utils.DisplayListUtils#prefetchDisplayLists(View)}
     * should be called on scrollable surfaces like {@link android.support.v7.widget.RecyclerView}
     * during scrolling.
     */
    public Builder canPrefetchDisplayLists(boolean canPrefetch) {
      this.canPrefetchDisplayLists = canPrefetch;
      return this;
    }

    /**
     * Specify whether the ComponentTree allows to cache display lists of the components after it
     * was first drawng.
     *
     * NOTE: To make display lists caching work, {@link #canPrefetchDisplayLists(boolean)} should
     * be set to true.
     */
    public Builder canCacheDrawingDisplayLists(boolean canCacheDrawingDisplayLists) {
      this.canCacheDrawingDisplayLists = canCacheDrawingDisplayLists;
      return this;
    }

    /**
     * Specify whether the ComponentHosts created by this tree will clip their children.
     * Default value is 'true' as in Android views.
     */
    public Builder shouldClipChildren(boolean shouldClipChildren) {
      this.shouldClipChildren = shouldClipChildren;
      return this;
    }

    /**
     * Builds a {@link ComponentTree} using the parameters specified in this builder.
     */
    public ComponentTree build() {
      ComponentTree componentTree = new ComponentTree(this);

      ComponentsPools.release(this);

      return componentTree;
    }
  }
}
