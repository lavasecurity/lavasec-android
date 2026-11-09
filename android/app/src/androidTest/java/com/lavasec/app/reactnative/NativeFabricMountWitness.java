package com.lavasec.app.reactnative;

import android.app.Activity;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactSoftExceptionLogger;
import com.facebook.react.bridge.UIManager;
import com.facebook.react.uimanager.UIManagerHelper;
import java.io.Closeable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Diagnostic-only observer of RN's actual pre-removal soft-exception boundary. */
public final class NativeFabricMountWitness
    implements Closeable, ReactSoftExceptionLogger.ReactSoftExceptionListener {
  private static final String TAG = "LavaFabricMountWitness";
  private static final String PREFIX = "ParentWitness ";
  private static final int LIMIT = 8;
  private static final Pattern MESSAGE = Pattern.compile(
      "addViewAt: cannot insert view \\[([0-9]{1,10})\\] into parent \\[([0-9]{1,10})\\]: "
          + "View already has a parent: \\[(-?[0-9]{1,10})\\] Parent: "
          + "[A-Za-z_$][A-Za-z0-9_$]{0,79} View: [A-Za-z_$][A-Za-z0-9_$]{0,79}");
  private static final Set<String> CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "ReactViewGroup", "ReactScrollView", "ReactHorizontalScrollView", "ReactTextView",
      "Screen", "ScreenStack", "ScreenContentWrapper", "ScreensCoordinatorLayout",
      "ScreenStackHeaderConfig", "ScreenStackHeaderSubview", "Toolbar", "ReactRootView",
      "ReactSurfaceView", "View", "ViewGroup", "FrameLayout", "LinearLayout", "CoordinatorLayout",
      "ComposeView", "AndroidComposeView", "LavaTextFieldView", "LavaDecorationView",
      "LavaNativePageView", "LavaShareCardSurfaceView", "LavaShareQrView")));
  private static final Set<String> MARKERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "screen.scroll", "sheet.pinned-header", "sheet.native-footer", "plus.context",
      "plus.thank-you.mascot", "plus.thank-you.mascot.gesture", "lava-route-privacy-cover",
      "foreground-flow-privacy-cover", "vpn-editor-privacy-cover")));

  private final Activity activity;
  private final ReactContext originalContext;
  private final Supplier<ReactContext> currentContext;
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AtomicBoolean observing = new AtomicBoolean(false);
  private final AtomicInteger events = new AtomicInteger();

  // Java can call these public JVM methods; the installed RN Kotlin logger is internal.
  public NativeFabricMountWitness(Activity activity, Supplier<ReactContext> currentContext) {
    this.activity = activity;
    this.currentContext = currentContext;
    this.originalContext = currentContext.get();
    ReactSoftExceptionLogger.addListener(this);
  }

  @Override
  public void logSoftException(String category, Throwable cause) {
    try {
      if (!active.get() || !"SurfaceMountingManager".equals(category)
          || !(cause instanceof IllegalStateException)) return;
      String message = cause.getMessage();
      if (message == null || message.length() > 512) return;
      Matcher match = MESSAGE.matcher(message);
      if (!match.matches()) return;
      long child = Long.parseLong(match.group(1));
      long attempted = Long.parseLong(match.group(2));
      long previous = Long.parseLong(match.group(3));
      if (child <= 0 || child > Integer.MAX_VALUE || attempted <= 0
          || attempted > Integer.MAX_VALUE || previous < -1 || previous > Integer.MAX_VALUE) return;
      if (!observing.compareAndSet(false, true)) return;
      try {
        // The acquired observing CAS serializes this saturating count. It never wraps.
        int observed = events.get();
        if (observed < LIMIT) {
          events.set(observed + 1);
          observe((int) child, (int) attempted, (int) previous);
        }
      } finally {
        observing.set(false);
      }
    } catch (Throwable ignored) {
      // No observer failure may replace RN's original exception or mounting behavior.
    } finally {
      // RN only performs its default FLog fallback when there are no listeners.
      // No installed app/RN source registers another listener; preserve that exact fallback once.
      FLog.e(category, "Unhandled SoftException", cause);
    }
  }

  private void observe(int childTag, int attemptedTag, int previousTag) {
    JSONObject record = new JSONObject();
    try {
      record.put("schema", 1);
      record.put("child", childTag);
      record.put("attemptedParent", attemptedTag);
      record.put("previousParent", previousTag);
      record.put("mainThread", Looper.myLooper() == Looper.getMainLooper());
      record.put("currentContext", JSONObject.NULL);
      record.put("sameWindow", JSONObject.NULL);
      empty(record, "observer-error");
      if (Looper.myLooper() != Looper.getMainLooper()) {
        empty(record, "off-main");
        emit(record);
        return;
      }
      ReactContext context = currentContext.get();
      record.put("currentContext", context != null && context == originalContext);
      if (context == null || originalContext == null) {
        empty(record, "no-context");
      } else if (context != originalContext) {
        empty(record, "context-changed");
      } else if (context.getCurrentActivity() != activity || !(activity instanceof LavaReactActivity)
          || activity.isFinishing() || activity.isDestroyed()) {
        empty(record, "wrong-host");
      } else if (!context.hasActiveReactInstance()) {
        empty(record, "inactive-context");
      } else {
        UIManager manager = UIManagerHelper.getUIManagerForReactTag(context, childTag);
        View child = manager == null ? null : manager.resolveView(childTag);
        View attempted = manager == null ? null : manager.resolveView(attemptedTag);
        if (child == null || attempted == null) {
          empty(record, "missing-view");
        } else if (child.getId() != childTag || attempted.getId() != attemptedTag) {
          empty(record, "identity-mismatch");
        } else {
          boolean sameWindow = activity.getWindow() != null
              && attempted.getRootView() == activity.getWindow().getDecorView().getRootView()
              && child.getRootView() == activity.getWindow().getDecorView().getRootView();
          record.put("sameWindow", sameWindow);
          ViewParent parent = child.getParent();
          if (!sameWindow) {
            empty(record, "wrong-window");
          } else if (!(parent instanceof View)) {
            empty(record, "non-view-parent");
          } else if (((View) parent).getId() != previousTag) {
            empty(record, "identity-mismatch");
          } else {
            Chain childChain = ancestors(child);
            Chain attemptedChain = ancestors(attempted);
            record.put("childChain", childChain.rows);
            record.put("attemptedParentChain", attemptedChain.rows);
            record.put("childTruncated", childChain.truncated);
            record.put("attemptedParentTruncated", attemptedChain.truncated);
            record.put("resolved", true);
            record.put("status", "resolved");
          }
        }
      }
      emit(record);
    } catch (Throwable ignored) {
      // Partial chains are discarded, not projected as an observed hierarchy.
      try {
        empty(record, "observer-error");
        emit(record);
      } catch (Throwable alsoIgnored) {
        // The original FLog fallback still runs in logSoftException's finally.
      }
    }
  }

  private static void empty(JSONObject record, String status) throws org.json.JSONException {
    record.put("resolved", false);
    record.put("status", status);
    record.put("childChain", new JSONArray());
    record.put("attemptedParentChain", new JSONArray());
    record.put("childTruncated", false);
    record.put("attemptedParentTruncated", false);
  }

  private static Chain ancestors(View first) throws org.json.JSONException {
    JSONArray rows = new JSONArray();
    View node = first;
    for (int depth = 0; node != null && depth < LIMIT; depth++) {
      ViewParent parent = node.getParent();
      String simpleClass = node.getClass().getSimpleName();
      Object marker = node.getTag(com.facebook.react.R.id.react_test_id);
      JSONObject row = new JSONObject();
      row.put("tag", node.getId());
      row.put("class", CLASSES.contains(simpleClass) ? simpleClass : "other");
      row.put("marker", marker instanceof String && MARKERS.contains(marker) ? marker : "other");
      row.put("parentIndex", parent instanceof ViewGroup ? ((ViewGroup) parent).indexOfChild(node) : -1);
      rows.put(row);
      node = parent instanceof View ? (View) parent : null;
    }
    return new Chain(rows, node != null);
  }

  private static void emit(JSONObject record) {
    String encoded = record.toString();
    // Fixed vocabulary + two eight-row chains fit below 3072 ASCII bytes.
    if (encoded != null && encoded.length() <= 3072) Log.e(TAG, PREFIX + encoded);
  }

  private static final class Chain {
    final JSONArray rows;
    final boolean truncated;
    Chain(JSONArray rows, boolean truncated) {
      this.rows = rows;
      this.truncated = truncated;
    }
  }

  @Override
  public void close() {
    active.set(false);
    ReactSoftExceptionLogger.removeListener(this);
  }
}
