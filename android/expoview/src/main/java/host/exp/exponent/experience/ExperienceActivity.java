// Copyright 2015-present 650 Industries. All rights reserved.

package host.exp.exponent.experience;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.RemoteViews;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.soloader.SoLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.unimodules.core.interfaces.Package;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import de.greenrobot.event.EventBus;
import expo.modules.splashscreen.SplashScreen;
import host.exp.exponent.ABIVersion;
import host.exp.exponent.AppLoader;
import host.exp.exponent.Constants;
import host.exp.exponent.ExpoUpdatesAppLoader;
import host.exp.exponent.ExponentIntentService;
import host.exp.exponent.ExponentManifest;
import host.exp.exponent.LauncherActivity;
import host.exp.exponent.RNObject;
import host.exp.exponent.analytics.Analytics;
import host.exp.exponent.analytics.EXL;
import host.exp.exponent.branch.BranchManager;
import host.exp.exponent.di.NativeModuleDepsProvider;
import host.exp.exponent.experience.loading.LoadingProgressPopupController;
import host.exp.exponent.experience.splashscreen.ManagedAppSplashScreenConfiguration;
import host.exp.exponent.experience.splashscreen.ManagedAppSplashScreenViewProvider;
import host.exp.exponent.kernel.DevMenuManager;
import host.exp.exponent.kernel.ExperienceId;
import host.exp.exponent.kernel.ExponentError;
import host.exp.exponent.kernel.ExponentUrls;
import host.exp.exponent.kernel.Kernel;
import host.exp.exponent.kernel.KernelConstants;
import host.exp.exponent.kernel.KernelProvider;
import host.exp.exponent.notifications.ExponentNotification;
import host.exp.exponent.notifications.ExponentNotificationManager;
import host.exp.exponent.notifications.NotificationConstants;
import host.exp.exponent.notifications.PushNotificationHelper;
import host.exp.exponent.notifications.ReceivedNotificationEvent;
import host.exp.exponent.storage.ExponentSharedPreferences;
import host.exp.exponent.utils.AsyncCondition;
import host.exp.exponent.utils.ExperienceActivityUtils;
import host.exp.exponent.utils.ExpoActivityIds;
import host.exp.expoview.Exponent;
import host.exp.expoview.R;
import versioned.host.exp.exponent.ExponentPackageDelegate;
import versioned.host.exp.exponent.ReactUnthemedRootView;

import static host.exp.exponent.kernel.KernelConstants.IS_OPTIMISTIC_KEY;
import static host.exp.exponent.kernel.KernelConstants.MANIFEST_URL_KEY;

public class ExperienceActivity extends BaseExperienceActivity implements Exponent.StartReactInstanceDelegate {

  public List<Package> expoPackages() {
    // Experience must pick its own modules in ExponentPackage
    return null;
  }

  public List<ReactPackage> reactPackages() {
    return null;
  }

  @Override
  public ExponentPackageDelegate getExponentPackageDelegate() {
    return null;
  }

  private static final String TAG = ExperienceActivity.class.getSimpleName();

  private static final String KERNEL_STARTED_RUNNING_KEY = "experienceActivityKernelDidLoad";
  private static final int NOTIFICATION_ID = 10101;
  private static String READY_FOR_BUNDLE = "readyForBundle";

  private static ExperienceActivity sCurrentActivity;

  private ReactUnthemedRootView mNuxOverlayView;
  private ExponentNotification mNotification;
  private ExponentNotification mTempNotification;
  private boolean mIsShellApp;
  private String mIntentUri;
  private boolean mIsReadyForBundle;

  // TODO: Remove this flag and assume it is always false, once we drop support for SDK37
  private boolean mWillBeReloaded = false;

  private RemoteViews mNotificationRemoteViews;
  private NotificationCompat.Builder mNotificationBuilder;
  private boolean mIsLoadExperienceAllowedToRun = false;
  private boolean mShouldShowLoadingViewWithOptimisticManifest = false;

  /**
   * Controls loadingProgressPopupWindow that is shown above whole activity.
   */
  LoadingProgressPopupController mLoadingProgressPopupController;

  @Nullable
  ManagedAppSplashScreenViewProvider mManagedAppSplashScreenViewProvider;

  @Inject
  ExponentManifest mExponentManifest;

  @Inject
  DevMenuManager mDevMenuManager;

  private DevBundleDownloadProgressListener mDevBundleDownloadProgressListener = new DevBundleDownloadProgressListener() {
    @Override
    public void onProgress(final @Nullable String status, final @Nullable Integer done, final @Nullable Integer total) {
      UiThreadUtil.runOnUiThread(() -> mLoadingProgressPopupController.updateProgress(status, done, total));
    }

    @Override
    public void onSuccess() {
      UiThreadUtil.runOnUiThread(() -> {
        mLoadingProgressPopupController.hide();
        finishLoading();
      });
    }

    @Override
    public void onFailure(Exception error) {
      UiThreadUtil.runOnUiThread(() -> {
        mLoadingProgressPopupController.hide();
        interruptLoading();
      });
    }
  };

  /*
   *
   * Lifecycle
   *
   */

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    mIsLoadExperienceAllowedToRun = true;
    mShouldShowLoadingViewWithOptimisticManifest = true;
    mLoadingProgressPopupController = new LoadingProgressPopupController(this);

    NativeModuleDepsProvider.getInstance().inject(ExperienceActivity.class, this);
    EventBus.getDefault().registerSticky(this);

    mActivityId = ExpoActivityIds.getNextAppActivityId();

    // TODO: audit this now that kernel logic is in Java
    boolean shouldOpenImmediately = true;

    // If our activity was killed for memory reasons or because of "Don't keep activities",
    // try to reload manifest using the savedInstanceState
    if (savedInstanceState != null) {
      String manifestUrl = savedInstanceState.getString(MANIFEST_URL_KEY);
      if (manifestUrl != null) {
        mManifestUrl = manifestUrl;
      }
    }

    // On cold boot to experience, we're given this information from the Java kernel, instead of
    // the JS kernel.
    Bundle bundle = getIntent().getExtras();
    if (bundle != null && mManifestUrl == null) {
      String manifestUrl = bundle.getString(MANIFEST_URL_KEY);
      if (manifestUrl != null) {
        mManifestUrl = manifestUrl;
      }

      // Don't want to get here if savedInstanceState has manifestUrl. Only care about
      // IS_OPTIMISTIC_KEY the first time onCreate is called.
      boolean isOptimistic = bundle.getBoolean(IS_OPTIMISTIC_KEY);
      if (isOptimistic) {
        shouldOpenImmediately = false;
      }
    }

    if (mManifestUrl != null && shouldOpenImmediately) {
      boolean forceCache = getIntent().getBooleanExtra(KernelConstants.LOAD_FROM_CACHE_KEY, false);
      new ExpoUpdatesAppLoader(mManifestUrl, new ExpoUpdatesAppLoader.AppLoaderCallback() {
        @Override
        public void onOptimisticManifest(final JSONObject optimisticManifest) {
          Exponent.getInstance().runOnUiThread(() -> setOptimisticManifest(optimisticManifest));
        }

        @Override
        public void onManifestCompleted(final JSONObject manifest) {
          Exponent.getInstance().runOnUiThread(() -> {
            try {
              String bundleUrl = ExponentUrls.toHttp(manifest.getString("bundleUrl"));

              setManifest(mManifestUrl, manifest, bundleUrl, null);
            } catch (JSONException e) {
              mKernel.handleError(e);
            }
          });
        }

        @Override
        public void onBundleCompleted(String localBundlePath) {
          Exponent.getInstance().runOnUiThread(() -> {
            setBundle(localBundlePath);
          });
        }

        @Override
        public void emitEvent(JSONObject params) {
          emitUpdatesEvent(params);
        }

        @Override
        public void updateStatus(ExpoUpdatesAppLoader.AppLoaderStatus status) {
          maybeSetLoadingProgressStatus(status);
        }

        @Override
        public void onError(Exception e) {
          Exponent.getInstance().runOnUiThread(() -> {
            mKernel.handleError(e);
          });
        }
      }, forceCache).start(this);
    }

    mKernel.setOptimisticActivity(this, getTaskId());
  }

  @Override
  protected void onResume() {
    super.onResume();

    sCurrentActivity = this;

    // Resume home's host if needed.
    mDevMenuManager.maybeResumeHostWithActivity(this);

    soloaderInit();

    addNotification(null);
    Analytics.logEventWithManifestUrl(Analytics.EXPERIENCE_APPEARED, mManifestUrl);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    // Check for manifest to avoid calling this when first loading an experience
    if (hasFocus && mManifest != null) {
      runOnUiThread(() -> {
        ExperienceActivityUtils.setNavigationBar(mManifest, ExperienceActivity.this);
      });
    }
  }

  public void soloaderInit() {
    if (mDetachSdkVersion != null) {
      SoLoader.init(this, false);
    }
  }

  public void shouldCheckOptions() {
    if (mManifestUrl != null && mKernel.hasOptionsForManifestUrl(mManifestUrl)) {
      handleOptions(mKernel.popOptionsForManifestUrl(mManifestUrl));
    }
  }

  @Override
  protected void onPause() {
    super.onPause();

    if (getCurrentActivity() == this) {
      sCurrentActivity = null;
    }

    removeNotification();
    Analytics.clearTimedEvents();
  }

  @Override
  public void onSaveInstanceState(Bundle savedInstanceState) {
    savedInstanceState.putString(MANIFEST_URL_KEY, mManifestUrl);

    super.onSaveInstanceState(savedInstanceState);
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);

    Uri uri = intent.getData();
    if (uri != null) {
      handleUri(uri.toString());
    }
  }

  /**
   * Handles command line command `adb shell input keyevent 82` that toggles the dev menu on the current experience activity.
   */
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_MENU && mReactInstanceManager != null && mReactInstanceManager.isNotNull() && !mIsCrashed) {
      mDevMenuManager.toggleInActivity(this);
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  /**
   * Closes the dev menu when pressing back button when it is visible on this activity.
   */
  @Override
  public void onBackPressed() {
    if (sCurrentActivity == this && mDevMenuManager.isShownInActivity(this)) {
      mDevMenuManager.requestToClose(this);
      return;
    }
    super.onBackPressed();
  }

  public void onEventMainThread(Kernel.KernelStartedRunningEvent event) {
    AsyncCondition.notify(KERNEL_STARTED_RUNNING_KEY);
  }

  @Override
  protected void onDoneLoading() {
    Analytics.markEvent(Analytics.TimedEvent.FINISHED_LOADING_REACT_NATIVE);
    Analytics.sendTimedEvents(mManifestUrl);
  }

  public void onEvent(ExperienceDoneLoadingEvent event) {
    if (event.getActivity() == this) {
      mLoadingProgressPopupController.hide();
    }
  }

  /*
   *
   * Experience Loading
   *
   */

  public void startLoading() {
    mIsLoading = true;
    showOrReconfigureManagedAppSplashScreen(mManifest);
    maybeSetLoadingProgressStatus();
  }

  /**
   * This method is being called twice:
   * - first time for optimistic manifest
   * - seconds time for real manifest
   */
  protected void showOrReconfigureManagedAppSplashScreen(final JSONObject manifest) {
    this.hideLoadingView();
    ManagedAppSplashScreenConfiguration config = ManagedAppSplashScreenConfiguration.parseManifest(manifest);
    if (mManagedAppSplashScreenViewProvider == null) {
      mManagedAppSplashScreenViewProvider = new ManagedAppSplashScreenViewProvider(config);
      SplashScreen.show(this, mManagedAppSplashScreenViewProvider, getRootViewClass(manifest), true);
    } else {
      mManagedAppSplashScreenViewProvider.updateSplashScreenViewWithManifest(this, manifest);
    }
  }

  public void maybeSetLoadingProgressStatus() {
    ExpoUpdatesAppLoader appLoader = mKernel.getAppLoaderForManifestUrl(mManifestUrl);
    if (appLoader != null) {
      maybeSetLoadingProgressStatus(appLoader.getStatus());
    }
  }

  public void maybeSetLoadingProgressStatus(ExpoUpdatesAppLoader.AppLoaderStatus status) {
    if (Constants.isStandaloneApp()) {
      return;
    }
    if (status == null) {
      return;
    }
    ExpoUpdatesAppLoader appLoader = mKernel.getAppLoaderForManifestUrl(mManifestUrl);
    if (appLoader != null && appLoader.shouldShowAppLoaderStatus()) {
      UiThreadUtil.runOnUiThread(() -> mLoadingProgressPopupController.setLoadingProgressStatus(status));
    }
  }

  public void setOptimisticManifest(final JSONObject optimisticManifest) {
    runOnUiThread(() -> {
      if (!isInForeground()) {
        return;
      }

      if (!mShouldShowLoadingViewWithOptimisticManifest) {
        return;
      }

      ExperienceActivityUtils.configureStatusBar(optimisticManifest, ExperienceActivity.this);
      ExperienceActivityUtils.setNavigationBar(optimisticManifest, ExperienceActivity.this);
      ExperienceActivityUtils.setTaskDescription(mExponentManifest, optimisticManifest, ExperienceActivity.this);
      showOrReconfigureManagedAppSplashScreen(optimisticManifest);
      maybeSetLoadingProgressStatus();
    });
  }

  public void setManifest(String manifestUrl, final JSONObject manifest, final String bundleUrl, final JSONObject kernelOptions) {
    if (!isInForeground()) {
      return;
    }

    if (!mIsLoadExperienceAllowedToRun) {
      return;
    }
    // Only want to run once per onCreate. There are some instances with ShellAppActivity where this would be called
    // twice otherwise. Turn on "Don't keep activites", trigger a notification, background the app, and then
    // press on the notification in a shell app to see this happen.
    mIsLoadExperienceAllowedToRun = false;

    mIsReadyForBundle = false;

    mManifestUrl = manifestUrl;
    mManifest = manifest;

    new ExponentNotificationManager(this).maybeCreateNotificationChannelGroup(mManifest);

    Kernel.ExperienceActivityTask task = mKernel.getExperienceActivityTask(mManifestUrl);
    task.taskId = getTaskId();
    task.experienceActivity = new WeakReference<>(this);
    task.activityId = mActivityId;
    task.bundleUrl = bundleUrl;

    mSDKVersion = manifest.optString(ExponentManifest.MANIFEST_SDK_VERSION_KEY);
    mIsShellApp = manifestUrl.equals(Constants.INITIAL_URL);

    // Sometime we want to release a new version without adding a new .aar. Use TEMPORARY_ABI_VERSION
    // to point to the unversioned code in ReactAndroid.
    if (Constants.TEMPORARY_ABI_VERSION != null && Constants.TEMPORARY_ABI_VERSION.equals(mSDKVersion)) {
      mSDKVersion = RNObject.UNVERSIONED;
    }
    // In detach/shell, we always use UNVERSIONED as the ABI.
    mDetachSdkVersion = Constants.isStandaloneApp() ? RNObject.UNVERSIONED : mSDKVersion;

    if (!RNObject.UNVERSIONED.equals(mSDKVersion)) {
      boolean isValidVersion = false;
      for (final String version : Constants.SDK_VERSIONS_LIST) {
        if (version.equals(mSDKVersion)) {
          isValidVersion = true;
          break;
        }
      }

      if (!isValidVersion) {
        KernelProvider.getInstance().handleError(mSDKVersion + " is not a valid SDK version. Options are " +
          TextUtils.join(", ", Constants.SDK_VERSIONS_LIST) + ", " + RNObject.UNVERSIONED + ".");
        return;
      }
    }

    soloaderInit();

    try {
      mExperienceIdString = manifest.getString(ExponentManifest.MANIFEST_ID_KEY);
      mExperienceId = ExperienceId.create(mExperienceIdString);
      AsyncCondition.notify(KernelConstants.EXPERIENCE_ID_SET_FOR_ACTIVITY_KEY);
    } catch (JSONException e) {
      KernelProvider.getInstance().handleError("No ID found in manifest.");
      return;
    }
    mIsCrashed = false;

    Analytics.logEventWithManifestUrlSdkVersion(Analytics.LOAD_EXPERIENCE, mManifestUrl, mSDKVersion);

    ExperienceActivityUtils.updateOrientation(mManifest, this);
    ExperienceActivityUtils.updateSoftwareKeyboardLayoutMode(mManifest, this);

    if (ABIVersion.toNumber(mSDKVersion) >= ABIVersion.toNumber("38.0.0")) {
      ExperienceActivityUtils.overrideUiMode(mManifest, this);
      mWillBeReloaded = false;
    } else {
      mWillBeReloaded = ExperienceActivityUtils.overrideUserInterfaceStyle(mManifest, this);
    }
    addNotification(kernelOptions);

    ExponentNotification notificationObject = null;
    // Activity could be restarted due to Dark Mode change, only pop options if that will not happen
    if (mKernel.hasOptionsForManifestUrl(manifestUrl) && !mWillBeReloaded) {
      KernelConstants.ExperienceOptions options = mKernel.popOptionsForManifestUrl(manifestUrl);

      // if the kernel has an intent for our manifest url, that's the intent that triggered
      // the loading of this experience.
      if (options.uri != null) {
        mIntentUri = options.uri;
      }

      notificationObject = options.notificationObject;
    }

    // if we have an embedded initial url, we never need any part of this in the initial url
    // passed to the JS, so we check for that and filter it out here.
    // this can happen in dev mode on a detached app, for example, because the intent will have
    // a url like customscheme://localhost:19000 but we don't care about the localhost:19000 part.
    if (mIntentUri == null || mIntentUri.equals(Constants.INITIAL_URL)) {
      if (Constants.SHELL_APP_SCHEME != null) {
        mIntentUri = Constants.SHELL_APP_SCHEME + "://";
      } else {
        mIntentUri = mManifestUrl;
      }
    }

    final ExponentNotification finalNotificationObject = notificationObject;

    BranchManager.handleLink(this, mIntentUri, mDetachSdkVersion);

    runOnUiThread(() -> {
      if (!isInForeground()) {
        return;
      }

      if (mReactInstanceManager.isNotNull()) {
        mReactInstanceManager.onHostDestroy();
        mReactInstanceManager.assign(null);
      }

      mReactRootView = new RNObject("host.exp.exponent.ReactUnthemedRootView");
      mReactRootView.loadVersion(mDetachSdkVersion).construct(ExperienceActivity.this);
      setReactRootView((View) mReactRootView.get());

      String id;
      try {
        id = Exponent.getInstance().encodeExperienceId(mExperienceIdString);
      } catch (UnsupportedEncodingException e) {
        KernelProvider.getInstance().handleError("Can't URL encode manifest ID");
        return;
      }

      if (isDebugModeEnabled()) {
        mNotification = finalNotificationObject;
        mJSBundlePath = "";
        startReactInstance();
      } else {
        mTempNotification = finalNotificationObject;
        mIsReadyForBundle = true;
        AsyncCondition.notify(READY_FOR_BUNDLE);
      }

      ExperienceActivityUtils.configureStatusBar(manifest, ExperienceActivity.this);
      ExperienceActivityUtils.setNavigationBar(manifest, ExperienceActivity.this);
      ExperienceActivityUtils.setTaskDescription(mExponentManifest, manifest, ExperienceActivity.this);
      showOrReconfigureManagedAppSplashScreen(manifest);
      maybeSetLoadingProgressStatus();
    });
  }

  public void setBundle(final String localBundlePath) {
    // by this point, setManifest should have also been called, so prevent
    // setOptimisticManifest from showing a rogue splash screen
    mShouldShowLoadingViewWithOptimisticManifest = false;

    // To prevents starting application twice, we start react instance only if we know that the current activity won't be restarted.
    // Restart of the activity could be triggered by dark mode change.
    if (!isDebugModeEnabled() && !mWillBeReloaded) {
      final boolean finalIsReadyForBundle = mIsReadyForBundle;
      AsyncCondition.wait(READY_FOR_BUNDLE, new AsyncCondition.AsyncConditionListener() {
        @Override
        public boolean isReady() {
          return finalIsReadyForBundle;
        }

        @Override
        public void execute() {
          mNotification = mTempNotification;
          mTempNotification = null;
          mJSBundlePath = localBundlePath;
          startReactInstance();
          AsyncCondition.remove(READY_FOR_BUNDLE);
        }
      });
    }

    if (!Constants.isStandaloneApp()) {
      ExpoUpdatesAppLoader appLoader = mKernel.getAppLoaderForManifestUrl(mManifestUrl);
      if (appLoader != null && !appLoader.isUpToDate()) {
        new AlertDialog.Builder(this)
          .setTitle("Loading app from cache")
          .setMessage("Expo was unable to fetch the latest version of this app. A previously downloaded version has been launched. To ensure you're up-to-date, check your network connection and reload the app.")
          .setPositiveButton(android.R.string.ok, null)
          .show();
      }
    }
  }

  public void onEventMainThread(ReceivedNotificationEvent event) {
    if (event.experienceId.equals(mExperienceIdString)) {
      try {
        RNObject rctDeviceEventEmitter = new RNObject("com.facebook.react.modules.core.DeviceEventManagerModule$RCTDeviceEventEmitter");
        rctDeviceEventEmitter.loadVersion(mDetachSdkVersion);

        mReactInstanceManager.callRecursive("getCurrentReactContext")
          .callRecursive("getJSModule", rctDeviceEventEmitter.rnClass())
          .call("emit", "Exponent.notification", event.toWriteableMap(mDetachSdkVersion, "received"));
      } catch (Throwable e) {
        EXL.e(TAG, e);
      }
    }
  }

  public void handleOptions(KernelConstants.ExperienceOptions options) {
    try {
      if (options.uri != null) {
        handleUri(options.uri);
        String uri = options.uri;

        if (uri != null) {
          RNObject rctDeviceEventEmitter = new RNObject("com.facebook.react.modules.core.DeviceEventManagerModule$RCTDeviceEventEmitter");
          rctDeviceEventEmitter.loadVersion(mDetachSdkVersion);

          mReactInstanceManager.callRecursive("getCurrentReactContext")
            .callRecursive("getJSModule", rctDeviceEventEmitter.rnClass())
            .call("emit", "Exponent.openUri", uri);
        }

        BranchManager.handleLink(this, options.uri, mDetachSdkVersion);
      }

      if ((options.notification != null || options.notificationObject != null) && mDetachSdkVersion != null) {
        RNObject rctDeviceEventEmitter = new RNObject("com.facebook.react.modules.core.DeviceEventManagerModule$RCTDeviceEventEmitter");
        rctDeviceEventEmitter.loadVersion(mDetachSdkVersion);

        mReactInstanceManager.callRecursive("getCurrentReactContext")
          .callRecursive("getJSModule", rctDeviceEventEmitter.rnClass())
          .call("emit", "Exponent.notification", options.notificationObject.toWriteableMap(mDetachSdkVersion, "selected"));
      }
    } catch (Throwable e) {
      EXL.e(TAG, e);
    }
  }

  private void handleUri(String uri) {
    // Emits a "url" event to the Linking event emitter
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
    super.onNewIntent(intent);
  }

  public void emitUpdatesEvent(JSONObject params) {
    // event for SDK 39+
    KernelProvider.getInstance().addEventForExperience(mManifestUrl, new KernelConstants.ExperienceEvent(ExpoUpdatesAppLoader.UPDATES_EVENT_NAME, params.toString()));

    // legacy event for SDK 38 and below
    // TODO: remove once SDK 38 is phased out
    try {
      if (ExpoUpdatesAppLoader.UPDATE_AVAILABLE_EVENT.equals(params.getString("type"))) {
        params.put("type", AppLoader.UPDATE_DOWNLOAD_FINISHED_EVENT);
      }
      KernelProvider.getInstance().addEventForExperience(mManifestUrl, new KernelConstants.ExperienceEvent(AppLoader.UPDATES_EVENT_NAME, params.toString()));
    } catch (Exception e) {
      EXL.e(TAG, e);
    }
  }

  @Override
  public boolean isDebugModeEnabled() {
    return ExponentManifest.isDebugModeEnabled(mManifest);
  }

  @Override
  protected void startReactInstance() {
    Exponent.getInstance().testPackagerStatus(isDebugModeEnabled(), mManifest, new Exponent.PackagerStatusCallback() {
      @Override
      public void onSuccess() {
        mReactInstanceManager = startReactInstance(ExperienceActivity.this, mIntentUri, mDetachSdkVersion, mNotification, mIsShellApp, reactPackages(), expoPackages(), mDevBundleDownloadProgressListener);
      }

      @Override
      public void onFailure(final String errorMessage) {
        KernelProvider.getInstance().handleError(errorMessage);
      }
    });
  }

  @Override
  public void handleUnreadNotifications(JSONArray unreadNotifications) {
    PushNotificationHelper pushNotificationHelper = PushNotificationHelper.getInstance();
    if (pushNotificationHelper != null) {
      pushNotificationHelper.removeNotifications(this, unreadNotifications);
    }
  }

  /*
   *
   * Notification
   *
   */

  private void addNotification(final JSONObject options) {
    if (mManifestUrl == null || mManifest == null) {
      return;
    }

    String name = mManifest.optString(ExponentManifest.MANIFEST_NAME_KEY, null);
    if (name == null) {
      return;
    }

    if (!mManifest.optBoolean(ExponentManifest.MANIFEST_SHOW_EXPONENT_NOTIFICATION_KEY) && mIsShellApp) {
      return;
    }

    RemoteViews remoteViews = new RemoteViews(getPackageName(), mIsShellApp ? R.layout.notification_shell_app : R.layout.notification);
    remoteViews.setCharSequence(R.id.home_text_button, "setText", name);

    // Home
    Intent homeIntent = new Intent(this, LauncherActivity.class);
    remoteViews.setOnClickPendingIntent(R.id.home_image_button, PendingIntent.getActivity(this, 0,
      homeIntent, 0));

    // Info
    // Doing PendingIntent.getActivity doesn't work here - it opens the activity in the main
    // stack and not in the experience's stack
    remoteViews.setOnClickPendingIntent(R.id.home_text_button, PendingIntent.getService(this, 0,
      ExponentIntentService.getActionInfoScreen(this, mManifestUrl), PendingIntent.FLAG_UPDATE_CURRENT));

    if (!mIsShellApp) {
      // Share
      // TODO: add analytics
      Intent shareIntent = new Intent(Intent.ACTION_SEND);
      shareIntent.setType("text/plain");
      shareIntent.putExtra(Intent.EXTRA_SUBJECT, name + " on Exponent");
      shareIntent.putExtra(Intent.EXTRA_TEXT, mManifestUrl);
      remoteViews.setOnClickPendingIntent(R.id.share_button, PendingIntent.getActivity(this, 0,
        Intent.createChooser(shareIntent, "Share a link to " + name), PendingIntent.FLAG_UPDATE_CURRENT));

      // Save
      remoteViews.setOnClickPendingIntent(R.id.save_button, PendingIntent.getService(this, 0,
        ExponentIntentService.getActionSaveExperience(this, mManifestUrl),
        PendingIntent.FLAG_UPDATE_CURRENT));
    }

    // Reload
    remoteViews.setOnClickPendingIntent(R.id.reload_button, PendingIntent.getService(this, 0,
      ExponentIntentService.getActionReloadExperience(this, mManifestUrl), PendingIntent.FLAG_UPDATE_CURRENT));

    mNotificationRemoteViews = remoteViews;

    // Build the actual notification
    final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    notificationManager.cancel(NOTIFICATION_ID);

    new ExponentNotificationManager(this).maybeCreateExpoPersistentNotificationChannel();
    mNotificationBuilder = new NotificationCompat.Builder(this, NotificationConstants.NOTIFICATION_EXPERIENCE_CHANNEL_ID)
      .setContent(mNotificationRemoteViews)
      .setSmallIcon(R.drawable.notification_icon)
      .setShowWhen(false)
      .setOngoing(true)
      .setPriority(Notification.PRIORITY_MAX);

    mNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimary));
    notificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
  }

  private void removeNotification() {
    mNotificationRemoteViews = null;
    mNotificationBuilder = null;
    removeNotification(this);
  }

  public static void removeNotification(Context context) {
    NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
    notificationManager.cancel(NOTIFICATION_ID);
  }

  public void onNotificationAction() {
    dismissNuxViewIfVisible(true);
  }

  /**
   * @param isFromNotification true if this is the result of the user taking an
   *                           action in the notification view.
   */
  public void dismissNuxViewIfVisible(final boolean isFromNotification) {
    if (mNuxOverlayView != null) {
      runOnUiThread(() -> {
        Animation fadeOut = new AlphaAnimation(1, 0);
        fadeOut.setInterpolator(new AccelerateInterpolator());
        fadeOut.setDuration(500);

        fadeOut.setAnimationListener(new Animation.AnimationListener() {
          public void onAnimationEnd(Animation animation) {
            if (mNuxOverlayView.getParent() != null) {
              ((ViewGroup) mNuxOverlayView.getParent()).removeView(mNuxOverlayView);
            }
            mNuxOverlayView = null;

            JSONObject eventProperties = new JSONObject();
            try {
              eventProperties.put("IS_FROM_NOTIFICATION", isFromNotification);
            } catch (JSONException e) {
              EXL.e(TAG, e.getMessage());
            }
            Analytics.logEvent("NUX_EXPERIENCE_OVERLAY_DISMISSED", eventProperties);
          }

          public void onAnimationRepeat(Animation animation) {
          }

          public void onAnimationStart(Animation animation) {
          }
        });

        mNuxOverlayView.startAnimation(fadeOut);
      });
    }
  }

  /*
   *
   * Errors
   *
   */

  @Override
  protected void onError(final Intent intent) {
    if (mManifestUrl != null) {
      intent.putExtra(ErrorActivity.MANIFEST_URL_KEY, mManifestUrl);
    }
  }

  @Override
  protected void onError(final ExponentError error) {
    if (mManifest == null) {
      return;
    }

    JSONObject errorJson = error.toJSONObject();
    if (errorJson == null) {
      return;
    }

    String experienceId = mManifest.optString(ExponentManifest.MANIFEST_ID_KEY);
    if (experienceId == null) {
      return;
    }

    JSONObject metadata = mExponentSharedPreferences.getExperienceMetadata(experienceId);
    if (metadata == null) {
      metadata = new JSONObject();
    }

    JSONArray errors = metadata.optJSONArray(ExponentSharedPreferences.EXPERIENCE_METADATA_LAST_ERRORS);
    if (errors == null) {
      errors = new JSONArray();
    }

    errors.put(errorJson);

    try {
      metadata.put(ExponentSharedPreferences.EXPERIENCE_METADATA_LAST_ERRORS, errors);
      mExponentSharedPreferences.updateExperienceMetadata(experienceId, metadata);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  public String getExperienceId() {
    return mExperienceIdString;
  }

  /**
   * Returns the currently active ExperienceActivity, that is the one that is currently being used by the user.
   */
  public static ExperienceActivity getCurrentActivity() {
    return sCurrentActivity;
  }
}
