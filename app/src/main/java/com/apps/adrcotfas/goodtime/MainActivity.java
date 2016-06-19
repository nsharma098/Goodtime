package com.apps.adrcotfas.goodtime;

import android.media.MediaPlayer;
import android.support.v7.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.apps.adrcotfas.goodtime.about.AboutActivity;
import com.apps.adrcotfas.goodtime.settings.SettingsActivity;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import im.delight.apprater.AppRater;

public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener{

    private static final int GOODTIME_NOTIFICATION_ID = 1;
    private static final int TIME_INTERVAL = 2000; // # milliseconds, desired time passed between two back presses.
    private long mBackPressed;
    Toast mExitToast;
    PowerManager.WakeLock mWakeLock;
    private int mSessionTime;
    private int mBreakTime;
    private int mRemainingTime;
    private int mLongBreakTime;
    private int mCompletedSessions = 0;
    private int mSessionsBeforeLongBreak;
    private Timer mTimer;
    private TimerState mTimerState;
    private FloatingActionButton mStartButton;
    private Button mPauseButton;
    private Button mStopButton;
    private TextView mTimeLabel;
    private View mHorizontalSeparator;
    private NotificationManager mNotificationManager;
    private SharedPreferences mPref = null;
    private SharedPreferences mPrivatePref = null;
    private AlertDialog mAlertDialog;
    private int mRingerMode;
    private boolean mWifiMode;
    private boolean mDisableSoundAndVibration;
    private boolean mDisableWifi;
    private boolean mKeepScreenOn;

    @Override
    protected void onResume() {
        super.onResume();
        if (mPrivatePref.getBoolean("pref_firstRun", true)) {
            Intent introIntent = new Intent(this, ProductTourActivity.class);
            startActivity(introIntent);
            mPrivatePref.edit().putBoolean("pref_firstRun", false).apply();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle(null);

        PreferenceManager.setDefaultValues(getApplicationContext(), R.xml.preferences, true);
        mPref = PreferenceManager.getDefaultSharedPreferences(this);
        mPref.registerOnSharedPreferenceChangeListener(this);

        mPrivatePref = getSharedPreferences("preferences_private", Context.MODE_PRIVATE);
        mPrivatePref.registerOnSharedPreferenceChangeListener(this);

        Button sessionCounterButton = (Button) findViewById(R.id.totalSessionsButton);
        sessionCounterButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                showSessionCounterDialog();
            }
        });
        sessionCounterButton.setText(String.valueOf(mPrivatePref.getInt("pref_totalSessions", 0)));

        Typeface robotoThin = Typeface.createFromAsset(getAssets(), "fonts/Roboto-Thin.ttf");
        final RelativeLayout buttons = (RelativeLayout)findViewById(R.id.buttons);
        mTimeLabel   = (TextView)findViewById(R.id.textView);
        mTimeLabel.setTypeface(robotoThin);
        mStartButton = (FloatingActionButton) findViewById(R.id.startButton);
        mPauseButton = (Button) findViewById(R.id.pauseButton);
        mStopButton  = (Button) findViewById(R.id.stopButton);
        mHorizontalSeparator = findViewById(R.id.horizontalSeparator);
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mStartButton.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(), R.anim.implode));
                buttons.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade));
                mRemainingTime = mSessionTime * 60;
                mTimerState = TimerState.ACTIVE_WORK;
                mPauseButton.setEnabled(true);
                mPauseButton.setTextColor(Color.parseColor("#ffd180"));
                startTimer(300);
                mStartButton.setEnabled(false); // avoid double-click
                mStartButton.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mStartButton.setEnabled(true);
                    }
                }, 300);
            }
        });
        mPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pauseTimer();
            }
        });
        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Animation fadeReversed = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_reverse);
                buttons.startAnimation(fadeReversed );
                mPauseButton.clearAnimation();
                Animation implodeReversed = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.implode_reverse);
                mStartButton.startAnimation(implodeReversed);
                loadInitialState();
            }
        });

        mSessionTime = Integer.parseInt(mPref.getString("pref_workTime", "25"));
        mBreakTime = Integer.parseInt(mPref.getString("pref_breakTime", "5"));
        mLongBreakTime = Integer.parseInt(mPref.getString("pref_longBreakDuration", "15"));
        mSessionsBeforeLongBreak = Integer.parseInt(mPref.getString("pref_sessionsBeforeLongBreak", "4"));
        mDisableSoundAndVibration = mPref.getBoolean("pref_disableSoundAndVibration", false);
        AudioManager aManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        mRingerMode = aManager.getRingerMode();

        mDisableWifi = mPref.getBoolean("pref_disableWifi", false);
        WifiManager wifiManager  = (WifiManager)this.getSystemService(WIFI_SERVICE);
        mWifiMode = wifiManager.isWifiEnabled();
        mKeepScreenOn = mPref.getBoolean("pref_keepScreenOn", false);

        setupAppRater();
        if (savedInstanceState != null) {
            mTimerState = (TimerState) savedInstanceState.getSerializable("timerState");
            mRemainingTime = savedInstanceState.getInt("remainingTime");
            mSessionsBeforeLongBreak = savedInstanceState.getInt("sessionsBeforeLongBreak");
            mRingerMode = savedInstanceState.getInt("ringerMode");
            mWifiMode = savedInstanceState.getBoolean("wifiMode");
            mDisableSoundAndVibration = savedInstanceState.getBoolean("disableSoundAndVibration");
            mDisableWifi = savedInstanceState.getBoolean("disableWifi");
            mKeepScreenOn = savedInstanceState.getBoolean("keepScreenOn");

            switch (mTimerState){
                case ACTIVE_WORK:
                    mPauseButton.setEnabled(true);
                    mPauseButton.setTextColor(Color.parseColor("#ffd180"));
                    startTimer(0);
                    break;
                case ACTIVE_BREAK:
                    mPauseButton.setEnabled(false);
                    mPauseButton.setTextColor(Color.parseColor("#FF9E9E9E"));
                    startTimer(0);
                    break;
                case PAUSED_WORK:
                    mTimerState = TimerState.ACTIVE_WORK;
                    loadRunningTimerUIState();
                    pauseTimer();
                    break;
                case INACTIVE:
                    loadInitialState();
                    break;
                case FINISHED_BREAK:
                    mTimerState = TimerState.ACTIVE_BREAK;
                    loadRunningTimerUIState();
                    showDialog();
                    break;
                case FINISHED_WORK:
                    mTimerState = TimerState.ACTIVE_WORK;
                    loadRunningTimerUIState();
                    showDialog();
                    break;
            }
        }
        else {
            loadInitialState();
        }
    }

    @Override
    protected void onDestroy() {
        if (mTimer != null){
            mTimer.cancel();
            mTimer.purge();
        }
        if (mNotificationManager != null)
            mNotificationManager.cancelAll();
        if (mAlertDialog != null){
            mAlertDialog.dismiss();
        }
        if (mWakeLock != null) {
            try {
                mWakeLock.release();
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
            }
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        if (id == R.id.action_about) {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        mSessionTime = Integer.parseInt(mPref.getString("pref_workTime", "25"));
        mBreakTime = Integer.parseInt(mPref.getString("pref_breakTime", "5"));
        mLongBreakTime = Integer.parseInt(mPref.getString("pref_longBreakDuration", "15"));
        mSessionsBeforeLongBreak = Integer.parseInt(mPref.getString("pref_sessionsBeforeLongBreak", "4"));
        Button button = (Button)findViewById(R.id.totalSessionsButton);
        button.setText(String.valueOf(mPrivatePref.getInt("pref_totalSessions", 0)));
        mDisableSoundAndVibration = mPref.getBoolean("pref_disableSoundAndVibration", false);
        mDisableWifi = mPref.getBoolean("pref_disableWifi", false);
        mKeepScreenOn = mPref.getBoolean("pref_keepScreenOn", false);

        switch (mTimerState){
            case INACTIVE:
                String currentTick = String.format(Locale.US, "%d.00", mSessionTime);
                SpannableString currentFormattedTick = new SpannableString(currentTick);
                currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf("."), 0);
                mTimeLabel.setText(currentFormattedTick);
            break;
            case ACTIVE_WORK:
                break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("timerState", mTimerState);
        outState.putInt("remainingTime", mRemainingTime);
        outState.putInt("sessionsBeforeLongBreak", mSessionsBeforeLongBreak);
        outState.putBoolean("disableSoundAndVibration", mDisableSoundAndVibration);
        outState.putInt("ringerMode", mRingerMode);
        outState.putBoolean("disableWifi", mDisableWifi);
        outState.putBoolean("wifiMode", mWifiMode);
        outState.putBoolean("keepScreenOn", mKeepScreenOn);
    }

    @Override
    public void onBackPressed()
    {
        if (mBackPressed + TIME_INTERVAL > System.currentTimeMillis())
        {
            mExitToast.cancel();
            super.onBackPressed();
            return;
        }
        else {
            try {
                mExitToast = Toast.makeText(getBaseContext(), "Press the back button again to exit", Toast.LENGTH_SHORT);
                mExitToast.show();
            } catch (Throwable th) {
                // ignoring this exception
            }
        }

        mBackPressed = System.currentTimeMillis();
    }

    public void loadInitialState() {
        mTimerState  = TimerState.INACTIVE;
        mRemainingTime = mSessionTime * 60;
        String currentTick = String.format(Locale.US, "%d." + (mRemainingTime % 60 < 10 ? "0%d" : "%d"),
                mRemainingTime / 60,
                mRemainingTime % 60);
        SpannableString currentFormattedTick = new SpannableString(currentTick);
        currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf("."), 0);
        mTimeLabel.setText(currentFormattedTick);
        mTimeLabel.setTextColor(0xFFBDBDBD);

        mStartButton.setVisibility(View.VISIBLE);
        mPauseButton.setVisibility(View.INVISIBLE);
        mPauseButton.setText("PAUSE");
        mStopButton.setVisibility(View.INVISIBLE);
        mHorizontalSeparator.setVisibility(View.INVISIBLE);
        if (mTimer != null){
            mTimer.cancel();
            mTimer.purge();
        }
        if (mNotificationManager != null)
            mNotificationManager.cancelAll();

        if (mWakeLock != null) {
            try {
                mWakeLock.release();
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
            }
        }
        if (mKeepScreenOn) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mDisableSoundAndVibration){
            AudioManager aManager=(AudioManager)getSystemService(AUDIO_SERVICE);
            aManager.setRingerMode(mRingerMode);
        }
        if(mDisableWifi){
            WifiManager wifiManager = (WifiManager)this.getSystemService(WIFI_SERVICE);
            wifiManager.setWifiEnabled(mWifiMode);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void loadRunningTimerUIState() {
        String currentTick;
        SpannableString currentFormattedTick;
        if (mRemainingTime >= 60) {
            currentTick = String.format(Locale.US, "%d." + (mRemainingTime % 60 < 10 ? "0%d" : "%d"),
                    mRemainingTime / 60,
                    mRemainingTime % 60);
            currentFormattedTick = new SpannableString(currentTick);
            currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf("."), 0);
        }
        else{
            currentTick = String.format(Locale.US, " ." + (mRemainingTime % 60 < 10 ? "0%d" : "%d"),
                    mRemainingTime % 60);
            currentFormattedTick = new SpannableString(currentTick);
            currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf("."), 0);
        }

        mTimeLabel.setText(currentFormattedTick);
        mStartButton.setVisibility(View.INVISIBLE);
        mPauseButton.setVisibility(View.VISIBLE);
        mStopButton.setVisibility(View.VISIBLE);
        mHorizontalSeparator.setVisibility(View.VISIBLE);
    }

    public void startTimer(long delay) {

        mTimeLabel.setTextColor(Color.WHITE);
        switch (mTimerState){
            case ACTIVE_WORK:
                if (mDisableSoundAndVibration){
                    AudioManager aManager=(AudioManager)getSystemService(AUDIO_SERVICE);
                    aManager.setRingerMode(aManager.RINGER_MODE_SILENT);
                }

                if(mDisableWifi){
                    WifiManager wifiManager  = (WifiManager)this.getSystemService(this.WIFI_SERVICE);
                    wifiManager.setWifiEnabled(false);
                }
                createNotification("Work session in progress.");
                break;
            case ACTIVE_BREAK:
                createNotification("Break session in progress.");
        }
        loadRunningTimerUIState();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (mKeepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                | PowerManager.ON_AFTER_RELEASE
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "starting partial wake lock");
        mWakeLock.acquire();

        mTimer = new Timer();
        mTimer.schedule(new UpdateTask(new Handler(), MainActivity.this), delay, 1000);
    }

    public void pauseTimer() {

        mTimeLabel.setTextColor(0xFFBDBDBD);
        long timeOfButtonPress = System.currentTimeMillis();
        if (mWakeLock != null) {
            try {
                mWakeLock.release();
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
            }
        }
        switch (mTimerState){
            case ACTIVE_WORK:
                mTimerState = TimerState.PAUSED_WORK;
                mPauseButton.setText("RESUME");
                mPauseButton.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(), R.anim.blink));
                if (mTimer != null) {
                    mTimer.cancel();
                    mTimer.purge();
                }
                createNotification("Work session is paused. Resume?");
                break;
            case PAUSED_WORK:
                mTimerState = TimerState.ACTIVE_WORK;
                mPauseButton.setText("PAUSE");
                mPauseButton.clearAnimation();
                startTimer(System.currentTimeMillis() - timeOfButtonPress > 1000 ? 0 : 1000 - (System.currentTimeMillis() - timeOfButtonPress));
                break;
        }
    }

    public void onCountdownFinished() {

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock screenWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ON_AFTER_RELEASE
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wake screen lock");

        screenWakeLock.acquire();
        screenWakeLock.release();

        if (mWakeLock != null)
            mWakeLock.release();

        if (mKeepScreenOn) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mDisableSoundAndVibration){
            AudioManager aManager=(AudioManager)getSystemService(AUDIO_SERVICE);
            aManager.setRingerMode(mRingerMode);
        }
        if(mDisableWifi){
            WifiManager wifiManager = (WifiManager)this.getSystemService(WIFI_SERVICE);
            wifiManager.setWifiEnabled(mWifiMode);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (mTimer != null){
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }

        if (mTimerState == TimerState.ACTIVE_WORK) {
            ++mCompletedSessions;
            int totalSessions = mPrivatePref.getInt("pref_totalSessions", 0);
            mPrivatePref.edit().putInt("pref_totalSessions", ++totalSessions).apply();
        }
        if(mPref.getBoolean("pref_vibrate", true)) {
            final Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = {0, 300, 700, 300};
            vibrator.vibrate(pattern, -1);
        }
        if (mPref.getBoolean("pref_notification", true)){
            MediaPlayer mediaPlayer = MediaPlayer.create(MainActivity.this, R.raw.goodtime_notif);
            mediaPlayer.start();
        }
        bringApplicationToFront();
        showDialog();
    }

    public void showDialog() {
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock((PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.FULL_WAKE_LOCK), "TAG");
        wakeLock.acquire();
        wakeLock.release();

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        switch (mTimerState) {
            case ACTIVE_WORK:
            case FINISHED_WORK:
                loadInitialState();
                mTimerState = TimerState.FINISHED_WORK;
                alertDialogBuilder.setTitle("Session complete");
                alertDialogBuilder.setPositiveButton("Start break", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mPauseButton.setEnabled(false);
                        mPauseButton.setTextColor(Color.parseColor("#FF9E9E9E"));
                        mRemainingTime = (mCompletedSessions >= mSessionsBeforeLongBreak) ?  mLongBreakTime * 60 : mBreakTime * 60;
                        mTimerState = TimerState.ACTIVE_BREAK;
                        startTimer(0);
                    }
                });
                alertDialogBuilder.setNegativeButton("Skip break", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mRemainingTime = mSessionTime * 60;
                        mTimerState = TimerState.ACTIVE_WORK;
                        startTimer(0);
                    }
                });
                alertDialogBuilder.setNeutralButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (mNotificationManager != null)
                            mNotificationManager.cancelAll();
                    }
                });
                mAlertDialog = alertDialogBuilder.create();
                mAlertDialog.setCanceledOnTouchOutside(false);
                mAlertDialog.show();
                createNotification("Session complete. Continue?");
                break;
            case ACTIVE_BREAK:
            case FINISHED_BREAK:
                loadInitialState();
                mPauseButton.setEnabled(true);
                mPauseButton.setTextColor(Color.parseColor("#ffd180"));
                mTimerState = TimerState.FINISHED_BREAK;
                if (mCompletedSessions >= mSessionsBeforeLongBreak)
                    mCompletedSessions = 0;
                alertDialogBuilder.setTitle("Break complete");
                alertDialogBuilder.setPositiveButton("Begin session", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mRemainingTime = mSessionTime * 60;
                        mTimerState = TimerState.ACTIVE_WORK;
                        startTimer(0);
                    }
                });
                alertDialogBuilder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (mNotificationManager != null)
                            mNotificationManager.cancelAll();
                    }
                });
                mAlertDialog = alertDialogBuilder.create();
                mAlertDialog.setCanceledOnTouchOutside(false);
                mAlertDialog.show();
                createNotification("Break complete. Resume work?");
                break;
            default:
                mTimerState = TimerState.INACTIVE;
                break;
        }
    }

    public void runTimer() {
        if (mTimerState != TimerState.INACTIVE){
            if(mRemainingTime == 0){
                onCountdownFinished();
            } else
                --mRemainingTime;

            String currentTick;
            SpannableString currentFormattedTick;
            if (mRemainingTime >= 60) {
                currentTick = String.format(Locale.US, "%d." + (mRemainingTime % 60 < 10 ? "0%d" : "%d"),
                        mRemainingTime / 60,
                        mRemainingTime % 60);
                currentFormattedTick = new SpannableString(currentTick);
                currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf("."), 0);
            }
            else{
                currentTick = String.format(Locale.US, " ." + (mRemainingTime % 60 < 10 ? "0%d" : "%d"),
                        mRemainingTime % 60);
                currentFormattedTick = new SpannableString(currentTick);
                currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf("."), 0);
            }

            mTimeLabel.setText(currentFormattedTick);
        }
    }

    private void createNotification(CharSequence contentText) {

        Notification.Builder notificationBuilder = new Notification.Builder(
                getApplicationContext())
                .setSmallIcon(R.drawable.ic_status_goodtime)
                .setAutoCancel(false)
                .setContentTitle("Goodtime")
                .setContentText(contentText)
                .setOngoing(true)
                .setShowWhen(false);
        notificationBuilder.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0,
                new Intent(getApplicationContext(), MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(GOODTIME_NOTIFICATION_ID, notificationBuilder.build());
    }

    private void bringApplicationToFront()
    {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        try
        {
            pendingIntent.send();
        }
        catch (PendingIntent.CanceledException e)
        {
            e.printStackTrace();
        }
    }

    private enum TimerState {INACTIVE, ACTIVE_WORK, PAUSED_WORK, ACTIVE_BREAK, FINISHED_WORK, FINISHED_BREAK}

    private class UpdateTask extends TimerTask {
        Handler handler;
        MainActivity ref;

        public UpdateTask(Handler handler, MainActivity ref){
            super();
            this.handler = handler;
            this.ref = ref;
        }

        @Override
        public void run() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    ref.runTimer();
                }
            });
        }
    }

    void showSessionCounterDialog() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle("Reset sessions counter?");
        alertDialogBuilder.setMessage("The completed sessions counter will be reset.");
        alertDialogBuilder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mPrivatePref.edit().putInt("pref_totalSessions", 0).apply();
            }
        });
        alertDialogBuilder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        mAlertDialog = alertDialogBuilder.create();
        mAlertDialog.show();
    }

    void setupAppRater() {
        AppRater appRater = new AppRater(this);
        appRater.setPhrases("Rate this app", "If you found this app useful please rate it on Google Play. Thanks for your support!", "Rate now", "Later", "No, thanks");
        appRater.show();
    }
}
