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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "MyWatchFace";
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
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

    public static int getIconResourceForWeatherCondition(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }
        return -1;
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String PATH_WEATHER = "/weather";
        private static final String KEY_HIGH = "high_temp";
        private static final String KEY_LOW = "low_temp";
        private static final String KEY_ID = "weather_id";

        private String mHighTemp;
        private String mLowTemp;
        private int mWeatherId;


        private GoogleApiClient mGoogleApiClient;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mTempLowTextPaint;
        Paint mTempHighTextPaint;
        Paint mDateTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mTimeXOffset;
        float mTimeYOffset;
        float mYOffset;
        float mDateYOffset;
        float mDateXOffset;
        float mLineYOffset;
        float mTempYOffset;
        float mHighXOffset;
        float mLowXOffset;
        float mWeatherYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);


            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            Resources resources = MyWatchFace.this.getResources();

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineYOffset = resources.getDimension(R.dimen.line_y_offset);
            mWeatherYOffset =  resources.getDimension(R.dimen.weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.primary));

            mTimeTextPaint = new Paint();
            mTimeTextPaint = createTimeTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));


            mDateTextPaint = new Paint();
            mDateTextPaint = createDateTextPaint();

            mTempLowTextPaint = new Paint();
            mTempLowTextPaint = createTempTextPaint(ContextCompat.getColor(getApplicationContext(),R.color.primary_light));
            mTempLowTextPaint.setTypeface(NORMAL_TYPEFACE);

            mTempHighTextPaint = new Paint();
            mTempHighTextPaint = createTempTextPaint(ContextCompat.getColor(getApplicationContext(),R.color.digital_text));
            mTempHighTextPaint.setTypeface(BOLD_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTimeTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createDateTextPaint() {
            Paint paint = new Paint();
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(getResources().getDimension(R.dimen.date_text_size));
            return paint;
        }
        private Paint createTempTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setAntiAlias(true);
            paint.setTextSize(getResources().getDimension(R.dimen.temp_text_size));
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
//            if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onVisibilityChanged: " + visible);
//        }

            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimeTextPaint.setTextSize(textSize);
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
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
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
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
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

            String timeText = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            mTimeXOffset = mTimeTextPaint.measureText(timeText) / 2;
            mTimeYOffset = mYOffset/2;

            canvas.drawText(timeText, bounds.centerX() - mTimeXOffset , bounds.centerY() - mTimeYOffset, mTimeTextPaint);

            String dayName = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            String monthName = mCalendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault());
            int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
            int year = mCalendar.get(Calendar.YEAR);
            String dateText = String.format("%s, %s %d, %d", dayName.toUpperCase(), monthName.toUpperCase(), dayOfMonth, year);
            mDateXOffset = mDateTextPaint.measureText(dateText) / 2;
            mDateYOffset = mTimeTextPaint.descent() - mTimeYOffset + mYOffset ;
            if (isInAmbientMode()) {
                mDateTextPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            } else {
                mDateTextPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.primary_light));
            }
            canvas.drawText(dateText, bounds.centerX() - mDateXOffset, bounds.centerY() + mDateYOffset  , mDateTextPaint);
            if (mHighTemp != null && mLowTemp != null) {
                mLineYOffset = mDateYOffset + mYOffset ;
                mTempYOffset = mLineYOffset + (2 * mYOffset);
                mHighXOffset = mTempHighTextPaint.measureText(mHighTemp);
                mLowXOffset = mTempHighTextPaint.measureText(mLowTemp);
                canvas.drawLine(bounds.centerX() - mXOffset - mHighXOffset , bounds.centerY() + mLineYOffset , bounds.centerX() + mXOffset + mLowXOffset , bounds.centerY() + mLineYOffset , mDateTextPaint);

                if (isInAmbientMode()) {
                    mTempLowTextPaint.setColor(ContextCompat.getColor(getApplicationContext(),R.color.digital_text));
                } else {
                    mTempLowTextPaint.setColor(ContextCompat.getColor(getApplicationContext(),R.color.primary_light));
                    Drawable b = getResources().getDrawable(getIconResourceForWeatherCondition(mWeatherId));
                    Bitmap icon = ((BitmapDrawable) b).getBitmap();
                    canvas.drawBitmap(icon, bounds.centerX() - mXOffset, mYOffset, null);
                }
                canvas.drawText(mHighTemp, bounds.centerX() - mXOffset - mHighXOffset, bounds.centerY() + mTempYOffset, mTempHighTextPaint);
                canvas.drawText(mLowTemp, bounds.centerX() + mXOffset , bounds.centerY() + mTempYOffset, mTempLowTextPaint);
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

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + bundle);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

        }

        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + connectionResult);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType()  == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(PATH_WEATHER) == 0) {
                        DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                        DataMap config = dataMapItem.getDataMap();
                        mHighTemp = config.getString(KEY_HIGH);
                        mLowTemp = config.getString(KEY_LOW);
                        mWeatherId = config.getInt(KEY_ID);
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Config DataItem updated:" + config);
                        }
                        invalidate();
                    }
                }
            }
        }

    }
}
