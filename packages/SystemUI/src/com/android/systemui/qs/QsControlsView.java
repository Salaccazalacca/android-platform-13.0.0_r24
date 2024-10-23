/*
 * Copyright (C) 2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.qs;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.PorterDuff;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionLegacyHelper;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.graphics.ColorUtils;

import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.Utils;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.animation.view.LaunchableImageView;
import com.android.systemui.lockscreen.ActivityLauncherUtils;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.tiles.dialog.BluetoothDialogFactory;
import com.android.systemui.qs.tiles.dialog.InternetDialogFactory;
import com.android.systemui.qs.VerticalSlider;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.NotificationMediaManager;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import android.os.SystemClock;
import android.view.KeyEvent;

public class QsControlsView extends FrameLayout implements BluetoothCallback {

    private List<View> mMediaPlayerViews = new ArrayList<>();
    private List<View> mWidgetViews = new ArrayList<>();
    private List<Runnable> metadataCheckRunnables = new ArrayList<>();

    private View mMediaCard;
    private View mPagerLayout, mMediaLayout;
    
    private QsControlsPageIndicator mMediaPageIndicator;
    private VerticalSlider mBrightnessSlider, mVolumeSlider;

    private final ActivityStarter mActivityStarter;    
    private final FalsingManager mFalsingManager;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;
    private final NotificationMediaManager mNotifManager;
    private final ActivityLauncherUtils mActivityLauncherUtils;
    
    private final ConnectivityManager mConnectivityManager;
    private final SubscriptionManager mSubManager;
    private final WifiManager mWifiManager;
    
    private final BluetoothDialogFactory mBluetoothDialogFactory;
    private final InternetDialogFactory mInternetDialogFactory;
    private final AccessPointController mAccessPointController;

    private ViewPager mViewPager;
    private PagerAdapter pagerAdapter;
    
    private int colorActive = Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorAccent);
    private int colorInactive = Utils.getColorAttrDefaultColor(mContext, R.attr.offStateColor);
    private int colorLabelActive = Utils.getColorAttrDefaultColor(mContext, com.android.internal.R.attr.textColorPrimaryInverse);
    private int colorLabelInactive = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
    private int colorSecondaryLabelActive = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorSecondaryInverse);
    private int colorSecondaryLabelInactive = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorSecondary);

    private int mAccentColor, mBgColor, mTintColor, mContainerColor;
    
    private Context mContext;
    
    private ViewGroup mBluetoothButton;
    private ImageView mBluetoothIcon;
    private TextView mBluetoothTitle;
    private TextView mBluetoothSummary;
    private boolean mBluetoothEnabled;
    
    private ViewGroup mInternetButton;
    private ImageView mInternetIcon;
    private TextView mInternetTitle;
    private TextView mInternetSummary;
    private boolean mInternetEnabled;

    private TextView mMediaTitle, mMediaArtist;
    private ImageView mMediaPrevBtn, mMediaPlayBtn, mMediaNextBtn, mMediaAlbumArtBg, mPlayerIcon;
    
    private MediaController mController;
    private MediaMetadata mMediaMetadata;
    private boolean mInflated = false;
    private Bitmap mAlbumArt = null;
    
    private boolean isClearingMetadata = false;
    
    private Handler mHandler;
    private Runnable mMediaUpdater;
    
    private Runnable mUpdateRunnableBluetooth;
    private Runnable mUpdateRunnableInternet;

    public QsControlsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mBluetoothEnabled = false;
        mInternetEnabled = false;
        mPagerLayout = LayoutInflater.from(mContext).inflate(R.layout.qs_controls_tile_pager, null);
        mActivityLauncherUtils = new ActivityLauncherUtils(context);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mFalsingManager = Dependency.get(FalsingManager.class);
        mMediaOutputDialogFactory = Dependency.get(MediaOutputDialogFactory.class);
        mNotifManager = Dependency.get(NotificationMediaManager.class);       
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mSubManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
            updateMediaController();
        }
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            mMediaMetadata = metadata;
            updateMediaController();
        }
    };

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE && isAttachedToWindow()) {
            updateMediaController();
            updateResources();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mInflated = true;
        mInternetButton = findViewById(R.id.qs_controls_internet_button);
        mInternetIcon = findViewById(R.id.qs_controls_internet_icon);
        mInternetTitle = findViewById(R.id.qs_controls_internet_title);
        mInternetSummary = findViewById(R.id.qs_controls_internet_summary);
        mBluetoothButton = findViewById(R.id.qs_controls_bluetooth_button);
        mBluetoothIcon = findViewById(R.id.qs_controls_bluetooth_icon);
        mBluetoothTitle = findViewById(R.id.qs_controls_bluetooth_title);
        mBluetoothSummary = findViewById(R.id.qs_controls_bluetooth_summary);
		mViewPager = findViewById(R.id.qs_controls_pager);
        mBrightnessSlider = findViewById(R.id.qs_controls_brightness_slider);
        mVolumeSlider = findViewById(R.id.qs_controls_volume_slider);
        mMediaLayout = mPagerLayout.findViewById(R.id.qs_controls_media);
        mMediaAlbumArtBg = mMediaLayout.findViewById(R.id.media_art_bg);
        mMediaAlbumArtBg.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mMediaTitle = mMediaLayout.findViewById(R.id.media_title);
        mMediaArtist = mMediaLayout.findViewById(R.id.artist_name);
        mMediaPrevBtn = mMediaLayout.findViewById(R.id.previous_button);
        mMediaPlayBtn = mMediaLayout.findViewById(R.id.play_button);
        mMediaNextBtn = mMediaLayout.findViewById(R.id.next_button);
        mPlayerIcon = mMediaLayout.findViewById(R.id.player_icon);
        mMediaCard = mMediaLayout.findViewById(R.id.media_cardview);
        mMediaPageIndicator = mMediaLayout.findViewById(R.id.media_page_indicator);
        collectViews(mMediaPlayerViews, mMediaPrevBtn, mMediaPlayBtn, mMediaNextBtn, 
                mMediaAlbumArtBg, mPlayerIcon, mMediaTitle, mMediaArtist);
        collectViews(mWidgetViews, mMediaLayout);
        initBluetoothManager();
        setupViewPager();
        startUpdateInternetTileStateAsync();
        startUpdateBluetoothTileStateAsync();
        mHandler = Dependency.get(Dependency.MAIN_HANDLER);
        mMediaUpdater = new Runnable() {
            @Override
            public void run() {
                updateMediaController();
                mHandler.postDelayed(this, 1000);
            }
        };
        updateMediaController();
	}

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mInflated) {
            return;
        }
        setClickListeners();
        updateResources();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    private void setClickListeners() {
        mInternetButton.setOnClickListener(v -> { showInternetDialog(v); return true; });
        mInternetButton.setOnLongClickListener(v -> { mActivityStarter.postStartActivityDismissingKeyguard(new Intent(Settings.ACTION_WIFI_SETTINGS), 0); return true; });
        mBluetoothButton.setOnClickListener(v -> { showBluetoothDialog(v); return true; });
        mBluetoothButton.setOnLongClickListener(v -> { mActivityStarter.postStartActivityDismissingKeyguard(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0); return true; });
        mMediaPlayBtn.setOnClickListener(view -> performMediaAction(MediaAction.TOGGLE_PLAYBACK));
        mMediaPrevBtn.setOnClickListener(view -> performMediaAction(MediaAction.PLAY_PREVIOUS));
        mMediaNextBtn.setOnClickListener(view -> performMediaAction(MediaAction.PLAY_NEXT));
        mMediaAlbumArtBg.setOnClickListener(view -> mActivityLauncherUtils.launchMediaPlayerApp()); 
        ((LaunchableImageView) mMediaAlbumArtBg).setOnLongClickListener(view -> {
            showMediaOutputDialog();
            return true;
        });
    }
    
    private void initBluetoothManager() {
        LocalBluetoothManager localBluetoothManager = LocalBluetoothManager.getInstance(mContext, null);

        if (localBluetoothManager != null) {
            localBluetoothManager.getEventManager().registerCallback(this);
            LocalBluetoothAdapter localBluetoothAdapter = localBluetoothManager.getBluetoothAdapter();
            int bluetoothState = BluetoothAdapter.STATE_DISCONNECTED;

            synchronized (localBluetoothAdapter) {
                if (localBluetoothAdapter.getAdapter().getState() != localBluetoothAdapter.getBluetoothState()) {
                    localBluetoothAdapter.setBluetoothStateInt(localBluetoothAdapter.getAdapter().getState());
                }
                bluetoothState = localBluetoothAdapter.getBluetoothState();
            }
            updateBluetoothState(bluetoothState);
        }
    }

    @Override
    public void onBluetoothStateChanged(@AdapterState int bluetoothState) {
        updateBluetoothState(bluetoothState);
    }

    private void updateBluetoothState(@AdapterState int bluetoothState) {
        mBluetoothEnabled = bluetoothState == BluetoothAdapter.STATE_ON
                || bluetoothState == BluetoothAdapter.STATE_TURNING_ON;
        updateBluetoothTile();
    }

    private void updateBluetoothTile() {
        if (mBluetoothButton == null
                || mBluetoothIcon == null
                || mBluetoothTitle == null
                || mBluetoothSummary == null)
            return;
            
        Drawable background = mBluetoothButton.getBackground();
        
        if (mBluetoothEnabled) {
            background.setTint(colorActive);
            mBluetoothIcon.setColorFilter(colorLabelActive);
            mBluetoothTitle.setTextColor(colorLabelActive);
            mBluetoothSummary.setText(getConnectedBluetoothDeviceName());  
            mBluetoothSummary.setColorFilter(colorSecondaryLabelActive);
        } else {
            background.setTint(colorInactive);
            mBluetoothIcon.setColorFilter(colorLabelInactive);
            mBluetoothTitle.setTextColor(colorLabelInactive);
            mBluetoothSummary.setText("Off");
            mBluetoothSummary.setColorFilter(colorSecondaryLabelInactive);
        }
    }

    private void updateInternetTile() {
        if (mInternetButton == null
                || mInternetIcon == null
                || mInternetTitle == null
                || mInternetSummary == null)
            return;
            
        if (isMobileConnected()) {
            mInternetEnabled = true;
            mInternetIcon.setImageResource(mContext.getResources().getIdentifier("ic_signal_cellular_4_4_bar", "drawable", "android"));
            mInternetSummary.setText(getSlotCarrierName());
        } else {
            mInternetEnabled = false;
            mInternetIcon.setImageResource(mContext.getResources().getIdentifier("ic_signal_cellular_0_4_bar", "drawable", "android"));
            mInternetSummary.setText("Off");
        }
        
        if (isWifiConnected()) {
            mInternetEnabled = true;
            mInternetIcon.setImageResource(mContext.getResources().getIdentifier("ic_wifi_signal_4", "drawable", "android"));
            mInternetSummary.setText(getWifiSsid());
        } else {
            mInternetEnabled = false;
            mInternetIcon.setImageResource(mContext.getResources().getIdentifier("ic_wifi_signal_0", "drawable", "android"));
            mInternetSummary.setText("Off");
        }

        Drawable background = mInternetButton.getBackground();

        if (mInternetEnabled) {
            background.setTint(colorActive);
            mInternetIcon.setColorFilter(colorLabelActive);
            mInternetTitle.setTextColor(colorLabelActive);
            mInternetSummary.setColorFilter(colorSecondaryLabelActive);
        } else {
            background.setTint(colorInactive);
            mInternetIcon.setColorFilter(colorLabelInactive);
            mInternetTitle.setTextColor(colorLabelInactive);
            mInternetSummary.setColorFilter(colorSecondaryLabelInactive);
        }
    }
    
    private String getConnectedBluetoothDeviceName() {
    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        synchronized (bluetoothAdapter) {
            if (bluetoothAdapter.isEnabled()) {
                for (BluetoothDevice bluetoothDevice : mBluetoothAdapter.getBondedDevices()) {
                    if (bluetoothDevice.isConnected()) { 
                        String name = next.getName();
                        return name;
                    }
                }
            }
            return null;
        }
    }
    
    public boolean isMobileConnected() {
        try {
            NetworkInfo mobile = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            return mobile.isConnected();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    private boolean isWifiConnected() {
        final Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            return false;
        }
    }

    private String getSlotCarrierName() {
        CharSequence result = mContext.getResources().getString(R.string.quick_settings_internet_label);
        int subId = mSubManager.getDefaultDataSubscriptionId();
        final List<SubscriptionInfo> subInfoList = mSubManager.getActiveSubscriptionInfoList(true);
        if (subInfoList != null) {
            for (SubscriptionInfo subInfo : subInfoList) {
                if (subId == subInfo.getSubscriptionId()) {
                    result = subInfo.getDisplayName();
                    break;
                }
            }
        }
        return result.toString();
    }

    private String getWifiSsid() {
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        if (wifiInfo.getHiddenSSID() || wifiInfo.getSSID() == WifiManager.UNKNOWN_SSID) {
            return mContext.getResources().getString(R.string.quick_settings_wifi_label);
        } else {
            return wifiInfo.getSSID().replace("\"", "");
        }
    }
    
    public void startUpdateBluetoothTileStateAsync() {
        AsyncTask.execute(new Runnable() {
            public void run() {
                startUpdateBluetoothTileState();
            }
        });
    }
    
    public void startUpdateBluetoothTileState() {
        Runnable runnable = mUpdateRunnableBluetooth;
        
        if (runnable == null) {
            mUpdateRunnableBluetooth = new Runnable() {
                public void run() {
                    updateBluetoothTile();
                    scheduleBluetoothUpdate();
                }
            };
        } else {
            new Handler(Looper.getMainLooper()).removeCallbacks(runnable);
        }
        scheduleBluetoothUpdate();
    }
    
    public void scheduleBluetoothUpdate() {
        Runnable runnable;
        if ((runnable = mUpdateRunnableBluetooth) != null) {
            new Handler(Looper.getMainLooper()).postDelayed(runnable, 1000);
        }
    }
    
    public void startUpdateInternetTileStateAsync() {
        AsyncTask.execute(new Runnable() {
            public void run() {
                startUpdateInternetTileState();
            }
        });
    }

    public void startUpdateInternetTileState() {
        Runnable runnable = mUpdateRunnableInternet;
        if (runnable == null) {
            mUpdateRunnableInternet = new Runnable() {
                public void run() {
                    updateInternetTile();
                    scheduleInternetUpdate();
                }
            };
        } else {
            new Handler(Looper.getMainLooper()).removeCallbacks(runnable);
        }
        scheduleInternetUpdate();
    }

    public void scheduleInternetUpdate() {
        Runnable runnable;
        if ((runnable = mUpdateRunnableInternet) != null) {
            new Handler(Looper.getMainLooper()).postDelayed(runnable, 1000);
        }
    }

    private void clearMediaMetadata() {
        if (isClearingMetadata) return;
        isClearingMetadata = true;
        mMediaMetadata = null;
        mAlbumArt = null; 
        isClearingMetadata = false;
        if (mMediaPlayBtn != null) {
            mMediaPlayBtn.setImageResource(R.drawable.ic_media_play);
        }
    }
    
    private void updateMediaController() {
        MediaController localController = getActiveLocalMediaController();
        if (localController != null && !mNotifManager.sameSessions(mController, localController)) {
            if (mController != null) {
                mController.unregisterCallback(mMediaCallback);
                mController = null;
            }
            mController = localController;
            mController.registerCallback(mMediaCallback);
        }
        mMediaMetadata = isMediaControllerAvailable() ? mController.getMetadata() : null;
        updateMediaPlaybackState();
    }

    private MediaController getActiveLocalMediaController() {
        MediaSessionManager mediaSessionManager =
                mContext.getSystemService(MediaSessionManager.class);
        MediaController localController = null;
        final List<String> remoteMediaSessionLists = new ArrayList<>();
        for (MediaController controller : mediaSessionManager.getActiveSessions(null)) {
            final MediaController.PlaybackInfo pi = controller.getPlaybackInfo();
            if (pi == null) {
                continue;
            }
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState == null) {
                continue;
            }
            if (playbackState.getState() != PlaybackState.STATE_PLAYING) {
                continue;
            }
            if (pi.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                if (localController != null
                        && TextUtils.equals(
                                localController.getPackageName(), controller.getPackageName())) {
                    localController = null;
                }
                if (!remoteMediaSessionLists.contains(controller.getPackageName())) {
                    remoteMediaSessionLists.add(controller.getPackageName());
                }
                continue;
            }
            if (pi.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
                if (localController == null
                        && !remoteMediaSessionLists.contains(controller.getPackageName())) {
                    localController = controller;
                }
            }
        }
        return localController;
    }

    private boolean isMediaControllerAvailable() {
        final MediaController mediaController = getActiveLocalMediaController();
        return mediaController != null && !TextUtils.isEmpty(mediaController.getPackageName());
    }

    private void updateMediaPlaybackState() {
        updateMediaMetadata();
        postDelayed(() -> {
            updateMediaMetadata();
        }, 250);
    }

    private void updateMediaMetadata() {
        Bitmap albumArt = mMediaMetadata == null ? null : mMediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (albumArt != null) {
            new ProcessArtworkTask().execute(albumArt);
        } else {
            mMediaAlbumArtBg.setImageBitmap(null);
        }
        updateMediaViews();
    }
    
    private boolean isMediaPlaying() {
        return isMediaControllerAvailable() 
            && PlaybackState.STATE_PLAYING == mNotifManager.getMediaControllerPlaybackState(mController);
    }

    private void updateMediaViews() {
        if (!isMediaPlaying()) {
            clearMediaMetadata();
        }
        if (mMediaPlayBtn != null) {
            mMediaPlayBtn.setImageResource(isMediaPlaying() ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        }
        CharSequence title = mMediaMetadata == null ? null : mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = mMediaMetadata == null ? null : mMediaMetadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        mMediaTitle.setText(title != null ? title : mContext.getString(R.string.no_media_playing));
        mMediaArtist.setText(artist != null ? artist : "");
        mPlayerIcon.setImageIcon(mNotifManager == null ? null : mNotifManager.getMediaIcon());
        final int mediaItemColor = getMediaItemColor();
        for (View view : mMediaPlayerViews) {
            if (view instanceof TextView) {
                ((TextView) view).setTextColor(mediaItemColor);
            } else if (view instanceof ImageView) {
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(mediaItemColor));
            }
        }
    }

    private class ProcessArtworkTask extends AsyncTask<Bitmap, Void, Bitmap> {
        protected Bitmap doInBackground(Bitmap... bitmaps) {
            Bitmap bitmap = bitmaps[0];
            if (bitmap == null) {
                return null;
            }
            int width = mMediaAlbumArtBg.getWidth();
            int height = mMediaAlbumArtBg.getHeight();
            return getScaledRoundedBitmap(bitmap, width, height);
        }
        protected void onPostExecute(Bitmap result) {
            if (result == null) return;
            if (mAlbumArt == null || mAlbumArt != result) {
                mAlbumArt = result;
                final int mediaFadeLevel = mContext.getResources().getInteger(R.integer.media_player_fade);
                final int fadeFilter = ColorUtils.blendARGB(Color.BLACK, mNotifManager == null ? Color.BLACK : mNotifManager.getMediaBgColor(), mediaFadeLevel / 100f);
                mMediaAlbumArtBg.setColorFilter(fadeFilter, PorterDuff.Mode.SRC_ATOP);
                mMediaAlbumArtBg.setImageBitmap(mAlbumArt);
            }
        }
    }

    private Bitmap getScaledRoundedBitmap(Bitmap bitmap, int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        float radius = mContext.getResources().getDimensionPixelSize(R.dimen.qs_controls_slider_corner_radius);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (scaledBitmap == null) {
            return null;
        }
        Bitmap output = Bitmap.createBitmap(scaledBitmap.getWidth(), scaledBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setShader(new BitmapShader(scaledBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        RectF rect = new RectF(0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight());
        canvas.drawRoundRect(rect, radius, radius, paint);
        return output;
    }

    private void setupViewPager() {
        if (mViewPager == null) return;
        pagerAdapter = new PagerAdapter() {
            @Override
            public int getCount() {
                return mWidgetViews.size();
            }
            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }
            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View view = mWidgetViews.get(position);
                container.addView(view);
                return view;
            }
            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        };
        mViewPager.setAdapter(pagerAdapter);
        mViewPager.setCurrentItem(1);
        mMediaPageIndicator.setupWithViewPager(mViewPager);
        mMediaLayout.setVisibility(View.VISIBLE);
    }

    public void updateColors() {
        mAccentColor = mContext.getResources().getColor(isNightMode() ? R.color.qs_controls_active_color_dark : R.color.qs_controls_active_color_light);
        mBgColor = mContext.getResources().getColor(isNightMode() ? R.color.qs_controls_bg_color_dark : R.color.qs_controls_bg_color_light);
        mTintColor = mContext.getResources().getColor(isNightMode() ? R.color.qs_controls_bg_color_light : R.color.qs_controls_bg_color_dark);
        mContainerColor = mContext.getResources().getColor(isNightMode() ? R.color.qs_controls_container_bg_color_dark : R.color.qs_controls_container_bg_color_light);
        if (mMediaCard != null) {
            mMediaCard.getBackground().setTint(mContainerColor);
        }
        if (mMediaPageIndicator != null) {
            mMediaPageIndicator.updateColors(isNightMode());
        }
        updateMediaPlaybackState();
    }
    
    private boolean isNightMode() {
        return (mContext.getResources().getConfiguration().uiMode 
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    
    private int getMediaItemColor() {
        return isMediaPlaying() ? Color.WHITE : mTintColor;
    }

    public void updateResources() {
        if (mBrightnessSlider != null && mVolumeSlider != null) {
            mBrightnessSlider.updateSliderPaint();
            mVolumeSlider.updateSliderPaint();
        }
        updateColors();
    }

    private void collectViews(List<View> viewList, View... views) {
        for (View view : views) {
            if (!viewList.contains(view)) {
                viewList.add(view);
            }
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void performMediaAction(MediaAction action) {
        updateMediaController();
        switch (action) {
            case TOGGLE_PLAYBACK:
                toggleMediaPlaybackState();
                break;
            case PLAY_PREVIOUS:
                dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case PLAY_NEXT:
                dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
        }
        updateMediaPlaybackState();
    }
    
    private void toggleMediaPlaybackState() {
        if (isMediaPlaying()) {
            mHandler.removeCallbacks(mMediaUpdater);
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE);
            updateMediaController();
            if (mMediaPlayBtn != null) {
                mMediaPlayBtn.setImageResource(R.drawable.ic_media_play);
            }
        } else {
            mMediaUpdater.run();
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY);
            if (mMediaPlayBtn != null) {
                mMediaPlayBtn.setImageResource(R.drawable.ic_media_pause);
            }
        }
    }
    
    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) {
            return;
        }
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
    }
    
    private void showBluetoothDialog(View view) {
        mHandler.post(() -> mBluetoothDialogFactory.create(true, view));
    }
    
    private void showInternetDialog(View view) {
        mHandler.post(() -> mInternetDialogFactory.create(true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi(), view));
    }

    private void showMediaOutputDialog() {
        String packageName = mActivityLauncherUtils.getActiveMediaPackage();
        if (!packageName.isEmpty()) {
            mMediaOutputDialogFactory.create(packageName, true, (LaunchableImageView) mMediaAlbumArtBg);
        }
    }
    
    private enum MediaAction {
        TOGGLE_PLAYBACK,
        PLAY_PREVIOUS,
        PLAY_NEXT
    }
}