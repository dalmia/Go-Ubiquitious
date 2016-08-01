/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        String LOW_TEMP = "lowTemp";
        String HIGH_TEMP = "highTemp";
        String WEATHER_ICON = "weatherIcon";
        String PATH = "/sunshine";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mPrimaryTextPaint;
        Paint mSecondaryTextPaint;
        Paint mWeatherIconPaint;


        boolean mAmbient;
        // Variables used to display the time on the watch face
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;

        GoogleApiClient mGoogleApiClient;
        String lowTemp;
        String highTemp;
        Bitmap mWeatherIconBitmap;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initialize();
                invalidate();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        float mTimeTextSize;
        float mMeridiemTextSize;
        float mDateTextSize;
        float mHighTempTextSize;
        float mLowTempTextSize;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.watch_face_background));

            mPrimaryTextPaint = new Paint();
            mPrimaryTextPaint = createTextPaint(resources.getColor(R.color.primary_text));

            mSecondaryTextPaint = new Paint();
            mSecondaryTextPaint = createTextPaint(resources.getColor(R.color.secondary_text));

            mWeatherIconPaint = new Paint();

            mCalendar = Calendar.getInstance();
            initialize();
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            removeListener();
            super.onDestroy();
        }


        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initialize();
            } else {
                unregisterReceiver();
                removeListener();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mTimeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mMeridiemTextSize = resources.getDimension(R.dimen.digital_meridiem_text_size);
            mDateTextSize = resources.getDimension(R.dimen.digital_date_text_size);
            mHighTempTextSize = resources.getDimension(R.dimen.digital_high_temp_text_size);
            mLowTempTextSize = resources.getDimension(R.dimen.digital_low_temp_text_size);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mPrimaryTextPaint.setAntiAlias(!inAmbientMode);
                    mSecondaryTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            String timeText = String.format(Locale.getDefault(), "%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            String meridiemText = getAmOrPm(mCalendar.get(Calendar.AM_PM));
            String dateText = mDateFormat.format(mDate);
            mPrimaryTextPaint.setTextSize(mTimeTextSize);
            canvas.drawText(timeText, mXOffset, mYOffset, mPrimaryTextPaint);

            mPrimaryTextPaint.setTextSize(mMeridiemTextSize);
            canvas.drawText(meridiemText, mXOffset+140, mYOffset, mPrimaryTextPaint);


            // Drawing the components when not in ambient mode
            if (!mAmbient) {
                mPrimaryTextPaint.setTextSize(mDateTextSize);
                canvas.drawText(dateText, mXOffset, mYOffset + 35, mSecondaryTextPaint);

                if(highTemp != null && lowTemp != null) {


                    mPrimaryTextPaint.setTextSize(mHighTempTextSize);
                    canvas.drawText(highTemp, 160, 255, mPrimaryTextPaint);

                    mSecondaryTextPaint.setTextSize(mLowTempTextSize);
                    canvas.drawText(lowTemp, 220, 255, mPrimaryTextPaint);

                    mSecondaryTextPaint.setStrokeWidth(0);
                    canvas.drawLine(120, 190, 200, 190, mPrimaryTextPaint);
                }
                if(mWeatherIconBitmap != null) {
                    float ratio = 50 / (float) mWeatherIconBitmap.getWidth();
                    float middleX = 50 / 2.0f;
                    float middleY = 50 / 2.0f;

                    Matrix scaleMatrix = new Matrix();
                    scaleMatrix.setScale(ratio, ratio, 90+middleX, 260+middleY);
                    canvas.setMatrix(scaleMatrix);
                    mWeatherIconPaint.setFilterBitmap(true);

                    canvas.drawBitmap(mWeatherIconBitmap, 90*ratio, 260*ratio, mWeatherIconPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /**
         * Helper function to initialize the date formats setting
         * mDateFormat to display the current day of the week and
         * mDayFormat to display the date of the current day.
         */
        private void initialize() {
            mDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
            mDate = new Date();
        }

        /**
         * Extracting the initial weather data from the connected nodes
         * and getting the dataMap from the DataMapItem to further extract
         * the required values.
         */
        private void getWeatherData() {
            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(@NonNull NodeApi.GetConnectedNodesResult nodes) {
                    Node nodeAuthority = null;
                    for (Node node : nodes.getNodes()) {
                        nodeAuthority = node;
                    }
                    if (nodeAuthority == null) return;
                    Uri uri = new Uri.Builder()
                            .scheme(PutDataRequest.WEAR_URI_SCHEME)
                            .path(PATH)
                            .authority(nodeAuthority.getId())
                            .build();

                    Wearable.DataApi.getDataItem(mGoogleApiClient, uri)
                            .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                                @Override
                                public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                                    if (dataItemResult.getStatus().isSuccess()
                                            && dataItemResult.getDataItem() != null) {
                                        DataMap map = DataMapItem.fromDataItem
                                                (dataItemResult.getDataItem()).getDataMap();
                                        extractWeatherData(map);
                                    }
                                }
                            });
                }
            });
        }

        /**
         * Helper function to extract the values from the DataMap received
         *
         * @param map - dataMap containing the values received
         */
        private void extractWeatherData(DataMap map) {
            highTemp = map.getString(HIGH_TEMP);
            lowTemp = map.getString(LOW_TEMP);
            getBitmapFromAsset(map.getAsset(WEATHER_ICON));
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            getWeatherData();
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals(PATH)) {
                        DataMap map = DataMapItem.fromDataItem(item).getDataMap();
                        extractWeatherData(map);
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        public void removeListener() {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected())
                mGoogleApiClient.disconnect();
        }

        /**
         * Loads the bitmap from the asset sent by the phone
         *
         * @param asset - asset received from the data map
         */
        public void getBitmapFromAsset(Asset asset) {
            if (asset != null) {
                Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).setResultCallback(
                        new ResultCallback<DataApi.GetFdForAssetResult>() {
                            @Override
                            public void onResult(@NonNull DataApi.GetFdForAssetResult getFdForAssetResult) {
                                InputStream inputStream = getFdForAssetResult.getInputStream();
                                if (inputStream != null) {
                                    mWeatherIconBitmap = BitmapFactory.decodeStream(inputStream);
                                }
                            }
                        });
            }
        }

    }

    /**
     * Returns the appropriate String to be appended to the time displayed in the watch face
     *
     * @param amOrPm - AM_PM value of the current time
     * @return - AM/PM as per amOrPM being the AM value or PM value
     */
    public String getAmOrPm(int amOrPm) {
        return amOrPm == Calendar.AM ? getString(R.string.am) : getString(R.string.pm);
    }
}
