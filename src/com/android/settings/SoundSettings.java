/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.settings;

import java.util.List;

import com.android.settings.bluetooth.DockEventReceiver;

import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.util.Log;

import android.view.VolumePanel;

import java.util.Date;
import java.util.Calendar;

public class SoundSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {
    private static final String TAG = "SoundSettings";

    private static final int DIALOG_NOT_DOCKED = 1;

    /** If there is no setting in the provider, use this. */
    private static final int FALLBACK_EMERGENCY_TONE_VALUE = 0;

    private static final String KEY_VOLUME_OVERLAY = "volume_overlay";
    private static final String KEY_SILENT_MODE = "silent_mode";
    private static final String KEY_VIBRATE = "vibrate_when_ringing";
    private static final String KEY_RING_VOLUME = "ring_volume";
    private static final String KEY_INCREASING_RING = "increasing_ring";
    private static final String KEY_MUSICFX = "musicfx";
    private static final String KEY_DTMF_TONE = "dtmf_tone";
    private static final String KEY_SOUND_EFFECTS = "sound_effects";
    private static final String KEY_QUIET_HOURS = "quiet_hours";
    private static final String KEY_VOLUME_ADJUST_SOUNDS = "volume_adjust_sounds";
    private static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";
    private static final String KEY_EMERGENCY_TONE = "emergency_tone";
    private static final String KEY_SOUND_SETTINGS = "sound_settings";
    private static final String KEY_LOCK_SOUNDS = "lock_sounds";
    private static final String KEY_RINGTONE = "ringtone";
    private static final String KEY_NOTIFICATION_SOUND = "notification_sound";
    private static final String KEY_CATEGORY_CALLS = "category_calls_and_notification";
    private static final String KEY_DOCK_CATEGORY = "dock_category";
    private static final String KEY_DOCK_AUDIO_SETTINGS = "dock_audio";
    private static final String KEY_DOCK_SOUNDS = "dock_sounds";
    private static final String KEY_DOCK_AUDIO_MEDIA_ENABLED = "dock_audio_media_enabled";
    private static final String KEY_CONVERT_SOUND_TO_VIBRATE = "notification_convert_sound_to_vibration";
    private static final String KEY_CAMERA_SOUNDS = "camera_sounds";
    private static final String PROP_CAMERA_SOUND = "persist.sys.camera-sound";
    private static final String KEY_VIBRATE_DURING_CALLS = "notification_vibrate_during_calls";
    private static final String KEY_SAFE_HEADSET_VOLUME = "safe_headset_volume";

    private static final String SILENT_MODE_OFF = "off";
    private static final String SILENT_MODE_VIBRATE = "vibrate";
    private static final String SILENT_MODE_MUTE = "mute";

    private static final String[] NEED_VOICE_CAPABILITY = {
            KEY_RINGTONE, KEY_DTMF_TONE, KEY_CATEGORY_CALLS,
            KEY_EMERGENCY_TONE, KEY_INCREASING_RING
    };

    private static final int MSG_UPDATE_RINGTONE_SUMMARY = 1;
    private static final int MSG_UPDATE_NOTIFICATION_SUMMARY = 2;

    private CheckBoxPreference mVibrateWhenRinging;
    private ListPreference mVolumeOverlay;
    private ListPreference mSilentMode;
    private CheckBoxPreference mDtmfTone;
    private CheckBoxPreference mSoundEffects;
    private CheckBoxPreference mHapticFeedback;
    private CheckBoxPreference mConvertSoundToVibration;
    private CheckBoxPreference mCameraSounds;
    private Preference mMusicFx;
    private CheckBoxPreference mLockSounds;
    private CheckBoxPreference mVibrateDuringCalls;
    private Preference mRingtonePreference;
    private Preference mNotificationPreference;
    private CheckBoxPreference mSafeHeadsetVolume;
    private CheckBoxPreference mVolumeAdjustSounds;
    private PreferenceScreen mQuietHours;

    private Runnable mRingtoneLookupRunnable;

    private AudioManager mAudioManager;

    private Preference mDockAudioSettings;
    private CheckBoxPreference mDockSounds;
    private Intent mDockIntent;
    private CheckBoxPreference mDockAudioMediaEnabled;

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_RINGTONE_SUMMARY:
                mRingtonePreference.setSummary((CharSequence) msg.obj);
                break;
            case MSG_UPDATE_NOTIFICATION_SUMMARY:
                mNotificationPreference.setSummary((CharSequence) msg.obj);
                break;
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_DOCK_EVENT)) {
                handleDockChange(intent);
            } else if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                updateState(false);
            }
        }
    };

    private PreferenceGroup mSoundSettings;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ContentResolver resolver = getContentResolver();
        int activePhoneType = TelephonyManager.getDefault().getCurrentPhoneType();

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        addPreferencesFromResource(R.xml.sound_settings);

        if (TelephonyManager.PHONE_TYPE_CDMA != activePhoneType) {
            // device is not CDMA, do not display CDMA emergency_tone
            getPreferenceScreen().removePreference(findPreference(KEY_EMERGENCY_TONE));
        }

        mVolumeOverlay = (ListPreference) findPreference(KEY_VOLUME_OVERLAY);
        mVolumeOverlay.setOnPreferenceChangeListener(this);
        int volumeOverlay = Settings.System.getInt(getContentResolver(),
                Settings.System.MODE_VOLUME_OVERLAY,
                VolumePanel.VOLUME_OVERLAY_EXPANDABLE);
        mVolumeOverlay.setValue(Integer.toString(volumeOverlay));
        mVolumeOverlay.setSummary(mVolumeOverlay.getEntry());

        mQuietHours = (PreferenceScreen) findPreference(KEY_QUIET_HOURS);
        if (Settings.System.getInt(resolver, Settings.System.QUIET_HOURS_ENABLED, 0) == 1) {
            mQuietHours.setSummary(getString(R.string.quiet_hours_active_from) + " " +
                    returnTime(Settings.System.getString(resolver, Settings.System.QUIET_HOURS_START))
                    + " " + getString(R.string.quiet_hours_active_to) + " " +
                    returnTime(Settings.System.getString(resolver, Settings.System.QUIET_HOURS_END)));
        } else {
           mQuietHours.setSummary(getString(R.string.quiet_hours_summary));
        }

        mVolumeAdjustSounds = (CheckBoxPreference) findPreference(KEY_VOLUME_ADJUST_SOUNDS);
        mVolumeAdjustSounds.setChecked(Settings.System.getInt(resolver,
                Settings.System.VOLUME_ADJUST_SOUNDS_ENABLED, 1) != 0);

        mSilentMode = (ListPreference) findPreference(KEY_SILENT_MODE);
        if (!getResources().getBoolean(R.bool.has_silent_mode)) {
            getPreferenceScreen().removePreference(mSilentMode);
            findPreference(KEY_RING_VOLUME).setDependency(null);
        } else {
            mSilentMode.setOnPreferenceChangeListener(this);
        }

        if (getResources().getBoolean(com.android.internal.R.bool.config_useFixedVolume)) {
            // device with fixed volume policy, do not display volumes submenu
            getPreferenceScreen().removePreference(findPreference(KEY_RING_VOLUME));
        }

        mSafeHeadsetVolume = (CheckBoxPreference) findPreference(KEY_SAFE_HEADSET_VOLUME);
        mSafeHeadsetVolume.setPersistent(false);
        boolean safeMediaVolumeEnabled = getResources().getBoolean(
                com.android.internal.R.bool.config_safe_media_volume_enabled);
        mSafeHeadsetVolume.setChecked(Settings.System.getInt(resolver,
                Settings.System.SAFE_HEADSET_VOLUME, safeMediaVolumeEnabled ? 1 : 0) != 0);

        mVibrateWhenRinging = (CheckBoxPreference) findPreference(KEY_VIBRATE);
        mVibrateWhenRinging.setPersistent(false);
        mVibrateWhenRinging.setChecked(Settings.System.getInt(resolver,
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0);

        mDtmfTone = (CheckBoxPreference) findPreference(KEY_DTMF_TONE);
        mDtmfTone.setPersistent(false);
        mDtmfTone.setChecked(Settings.System.getInt(resolver,
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0);
        mSoundEffects = (CheckBoxPreference) findPreference(KEY_SOUND_EFFECTS);
        mSoundEffects.setPersistent(false);
        mSoundEffects.setChecked(Settings.System.getInt(resolver,
                Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0);
        mHapticFeedback = (CheckBoxPreference) findPreference(KEY_HAPTIC_FEEDBACK);
        mHapticFeedback.setPersistent(false);
        mHapticFeedback.setChecked(Settings.System.getInt(resolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0);
        mCameraSounds = (CheckBoxPreference) findPreference(KEY_CAMERA_SOUNDS);
        mCameraSounds.setPersistent(false);
        mCameraSounds.setChecked(SystemProperties.getBoolean(
                PROP_CAMERA_SOUND, true));
        mLockSounds = (CheckBoxPreference) findPreference(KEY_LOCK_SOUNDS);
        mLockSounds.setPersistent(false);
        mLockSounds.setChecked(Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_SOUNDS_ENABLED, 1) != 0);

        mRingtonePreference = findPreference(KEY_RINGTONE);
        mNotificationPreference = findPreference(KEY_NOTIFICATION_SOUND);
        mConvertSoundToVibration = (CheckBoxPreference) findPreference(KEY_CONVERT_SOUND_TO_VIBRATE);
        mConvertSoundToVibration.setPersistent(false);
        mConvertSoundToVibration.setChecked(Settings.System.getBoolean(resolver,
                Settings.System.NOTIFICATION_CONVERT_SOUND_TO_VIBRATION, false));

        mVibrateDuringCalls = (CheckBoxPreference) findPreference(KEY_VIBRATE_DURING_CALLS);
        mVibrateDuringCalls.setChecked(Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_VIBRATE_DURING_ALERTS_DISABLED, 0) != 0);
        
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            removePreference(KEY_VIBRATE);
            removePreference(KEY_HAPTIC_FEEDBACK);
            removePreference(KEY_CONVERT_SOUND_TO_VIBRATE);
            removePreference(KEY_VIBRATE_DURING_CALLS);
        }

        if (TelephonyManager.PHONE_TYPE_CDMA == activePhoneType) {
            ListPreference emergencyTonePreference =
                (ListPreference) findPreference(KEY_EMERGENCY_TONE);
            emergencyTonePreference.setValue(String.valueOf(Settings.Global.getInt(
                resolver, Settings.Global.EMERGENCY_TONE, FALLBACK_EMERGENCY_TONE_VALUE)));
            emergencyTonePreference.setOnPreferenceChangeListener(this);
        }

        mSoundSettings = (PreferenceGroup) findPreference(KEY_SOUND_SETTINGS);

        mMusicFx = mSoundSettings.findPreference(KEY_MUSICFX);
        Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        mMusicFx.setIntent(i);
        PackageManager p = getPackageManager();
        List<ResolveInfo> ris = p.queryIntentActivities(i, PackageManager.GET_DISABLED_COMPONENTS);
        if (ris.size() == 0) {
            mSoundSettings.removePreference(mMusicFx);
        } else if (ris.size() == 1) {
            mMusicFx.setSummary(ris.get(0).loadLabel(p));
        }

        if (!Utils.isVoiceCapable(getActivity())) {
            for (String prefKey : NEED_VOICE_CAPABILITY) {
                Preference pref = findPreference(prefKey);
                if (pref != null) {
                    getPreferenceScreen().removePreference(pref);
                }
            }
        }

        mRingtoneLookupRunnable = new Runnable() {
            public void run() {
                if (mRingtonePreference != null) {
                    updateRingtoneName(RingtoneManager.TYPE_RINGTONE, mRingtonePreference,
                            MSG_UPDATE_RINGTONE_SUMMARY);
                }
                if (mNotificationPreference != null) {
                    updateRingtoneName(RingtoneManager.TYPE_NOTIFICATION, mNotificationPreference,
                            MSG_UPDATE_NOTIFICATION_SUMMARY);
                }
            }
        };

        initDockSettings();
    }

    @Override
    public void onResume() {
        super.onResume();

        lookupRingtoneNames();

        IntentFilter filter = new IntentFilter(Intent.ACTION_DOCK_EVENT);
        getActivity().registerReceiver(mReceiver, filter);

        filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        getActivity().registerReceiver(mReceiver, filter);

        updateState(true);

    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mReceiver);
    }

    // updateState in fact updates the UI to reflect the system state
    private void updateState(boolean force) {
        if (getActivity() == null) return;
        ContentResolver resolver = getContentResolver();

        mSilentMode.setValue(getPhoneSilentModeSettingValue());
        mSilentMode.setSummary(mSilentMode.getEntry());

        if (Settings.System.getInt(resolver, Settings.System.QUIET_HOURS_ENABLED, 0) == 1) {
            mQuietHours.setSummary(getString(R.string.quiet_hours_active_from) + " " +
                    returnTime(Settings.System.getString(resolver, Settings.System.QUIET_HOURS_START))
                    + " " + getString(R.string.quiet_hours_active_to) + " " +
                    returnTime(Settings.System.getString(resolver, Settings.System.QUIET_HOURS_END)));
        } else {
            mQuietHours.setSummary(getString(R.string.quiet_hours_summary));
        }
    }

    private void setPhoneSilentSettingValue(String value) {
        int ringerMode = AudioManager.RINGER_MODE_NORMAL;
        if (value.equals(SILENT_MODE_MUTE)) {
            ringerMode = AudioManager.RINGER_MODE_SILENT;
        } else if (value.equals(SILENT_MODE_VIBRATE)) {
            ringerMode = AudioManager.RINGER_MODE_VIBRATE;
        }
        mAudioManager.setRingerMode(ringerMode);
    }

    private String getPhoneSilentModeSettingValue() {
        switch (mAudioManager.getRingerMode()) {
        case AudioManager.RINGER_MODE_NORMAL:
            return SILENT_MODE_OFF;
        case AudioManager.RINGER_MODE_VIBRATE:
            return SILENT_MODE_VIBRATE;
        case AudioManager.RINGER_MODE_SILENT:
            return SILENT_MODE_MUTE;
        }
        // Shouldn't happen
        return SILENT_MODE_OFF;
    }

    private void updateRingtoneName(int type, Preference preference, int msg) {
        if (preference == null) return;
        Context context = getActivity();
        if (context == null) return;
        Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(context, type);
        CharSequence summary = context.getString(com.android.internal.R.string.ringtone_unknown);
        // Is it a silent ringtone?
        if (ringtoneUri == null) {
            summary = context.getString(com.android.internal.R.string.ringtone_silent);
        } else {
            // Fetch the ringtone title from the media provider
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(ringtoneUri,
                        new String[] { MediaStore.Audio.Media.TITLE }, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        summary = cursor.getString(0);
                    }
                }
             } catch (SQLiteException sqle) {
                 // Unknown title for the ringtone
             } finally {
                if (cursor != null) {
                     cursor.close();
                }
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(msg, summary));
    }

    private void lookupRingtoneNames() {
        new Thread(mRingtoneLookupRunnable).start();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mVibrateWhenRinging) {
            Settings.System.putInt(getContentResolver(), Settings.System.VIBRATE_WHEN_RINGING,
                    mVibrateWhenRinging.isChecked() ? 1 : 0);

        } else if (preference == mDtmfTone) {
            Settings.System.putInt(getContentResolver(), Settings.System.DTMF_TONE_WHEN_DIALING,
                    mDtmfTone.isChecked() ? 1 : 0);

        } else if (preference == mVolumeAdjustSounds) {
            Settings.System.putInt(getContentResolver(), Settings.System.VOLUME_ADJUST_SOUNDS_ENABLED,
            mVolumeAdjustSounds.isChecked() ? 1 : 0);

        } else if (preference == mSoundEffects) {
            if (mSoundEffects.isChecked()) {
                mAudioManager.loadSoundEffects();
            } else {
                mAudioManager.unloadSoundEffects();
            }
            Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED,
                    mSoundEffects.isChecked() ? 1 : 0);

        } else if (preference == mHapticFeedback) {
            Settings.System.putInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    mHapticFeedback.isChecked() ? 1 : 0);

        } else if (preference == mCameraSounds) {
            SystemProperties.set(PROP_CAMERA_SOUND, mCameraSounds.isChecked() ? "1" : "0");

        } else if (preference == mLockSounds) {
            Settings.System.putInt(getContentResolver(), Settings.System.LOCKSCREEN_SOUNDS_ENABLED,
                    mLockSounds.isChecked() ? 1 : 0);

        } else if (preference == mVibrateDuringCalls) {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.NOTIFICATION_VIBRATE_DURING_ALERTS_DISABLED,
                    mVibrateDuringCalls.isChecked() ? 1 : 0);

        } else if (preference == mMusicFx) {
            // let the framework fire off the intent
            return false;
        } else if (preference == mConvertSoundToVibration) {
            Settings.System.putBoolean(getContentResolver(), Settings.System.NOTIFICATION_CONVERT_SOUND_TO_VIBRATION,
                    mConvertSoundToVibration.isChecked());
        } else if (preference == mDockAudioSettings) {
            int dockState = mDockIntent != null
                    ? mDockIntent.getIntExtra(Intent.EXTRA_DOCK_STATE, 0)
                    : Intent.EXTRA_DOCK_STATE_UNDOCKED;

            if (dockState == Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                showDialog(DIALOG_NOT_DOCKED);
            } else {
                boolean isBluetooth = mDockIntent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) != null;

                if (isBluetooth) {
                    Intent i = new Intent(mDockIntent);
                    i.setAction(DockEventReceiver.ACTION_DOCK_SHOW_UI);
                    i.setClass(getActivity(), DockEventReceiver.class);
                    getActivity().sendBroadcast(i);
                } else {
                    PreferenceScreen ps = (PreferenceScreen)mDockAudioSettings;
                    Bundle extras = ps.getExtras();
                    extras.putBoolean("checked",
                            Settings.Global.getInt(getContentResolver(),
                                    Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, 0) == 1);
                    super.onPreferenceTreeClick(ps, ps);
                }
            }
        } else if (preference == mDockSounds) {
            Settings.Global.putInt(getContentResolver(), Settings.Global.DOCK_SOUNDS_ENABLED,
                    mDockSounds.isChecked() ? 1 : 0);

        } else if (preference == mDockAudioMediaEnabled) {
            Settings.Global.putInt(getContentResolver(), Settings.Global.DOCK_AUDIO_MEDIA_ENABLED,
                    mDockAudioMediaEnabled.isChecked() ? 1 : 0);
        } else if (preference == mSafeHeadsetVolume) {
            Settings.System.putInt(getContentResolver(), Settings.System.SAFE_HEADSET_VOLUME,
                    mSafeHeadsetVolume.isChecked() ? 1 : 0);

        }  else {
            // If we didn't handle it, let preferences handle it.
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }
        return true;
    }

    public boolean onPreferenceChange(Preference preference, Object objValue) {
        final String key = preference.getKey();
        if (KEY_EMERGENCY_TONE.equals(key)) {
            try {
                int value = Integer.parseInt((String) objValue);
                Settings.Global.putInt(getContentResolver(),
                        Settings.Global.EMERGENCY_TONE, value);
            } catch (NumberFormatException e) {
                Log.e(TAG, "could not persist emergency tone setting", e);
            }
        } else if (preference == mSilentMode) {
            setPhoneSilentSettingValue(objValue.toString());
        } else if (preference == mVolumeOverlay) {
            final int value = Integer.valueOf((String) objValue);
            final int index = mVolumeOverlay.findIndexOfValue((String) objValue);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.MODE_VOLUME_OVERLAY, value);
            mVolumeOverlay.setSummary(mVolumeOverlay.getEntries()[index]);
        }

        return true;
    }

    @Override
    protected int getHelpResource() {
        return R.string.help_url_sound;
    }

    private boolean needsDockSettings() {
        return getResources().getBoolean(R.bool.has_dock_settings);
    }

    private void initDockSettings() {
        ContentResolver resolver = getContentResolver();

        if (needsDockSettings()) {
            mDockSounds = (CheckBoxPreference) findPreference(KEY_DOCK_SOUNDS);
            mDockSounds.setPersistent(false);
            mDockSounds.setChecked(Settings.Global.getInt(resolver,
                    Settings.Global.DOCK_SOUNDS_ENABLED, 0) != 0);
            mDockAudioSettings = findPreference(KEY_DOCK_AUDIO_SETTINGS);
            mDockAudioSettings.setEnabled(false);
        } else {
            getPreferenceScreen().removePreference(findPreference(KEY_DOCK_CATEGORY));
            getPreferenceScreen().removePreference(findPreference(KEY_DOCK_AUDIO_SETTINGS));
            getPreferenceScreen().removePreference(findPreference(KEY_DOCK_SOUNDS));
            Settings.Global.putInt(resolver, Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, 1);
        }
    }

    private void handleDockChange(Intent intent) {
        if (mDockAudioSettings != null) {
            int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, 0);

            boolean isBluetooth =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) != null;

            mDockIntent = intent;

            if (dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                // remove undocked dialog if currently showing.
                try {
                    removeDialog(DIALOG_NOT_DOCKED);
                } catch (IllegalArgumentException iae) {
                    // Maybe it was already dismissed
                }

                if (isBluetooth) {
                    mDockAudioSettings.setEnabled(true);
                } else {
                    if (dockState == Intent.EXTRA_DOCK_STATE_LE_DESK) {
                        ContentResolver resolver = getContentResolver();
                        mDockAudioSettings.setEnabled(true);
                        if (Settings.Global.getInt(resolver,
                                Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, -1) == -1) {
                            Settings.Global.putInt(resolver,
                                    Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, 0);
                        }
                        mDockAudioMediaEnabled =
                                (CheckBoxPreference) findPreference(KEY_DOCK_AUDIO_MEDIA_ENABLED);
                        mDockAudioMediaEnabled.setPersistent(false);
                        mDockAudioMediaEnabled.setChecked(
                                Settings.Global.getInt(resolver,
                                        Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, 0) != 0);
                    } else {
                        mDockAudioSettings.setEnabled(false);
                    }
                }
            } else {
                mDockAudioSettings.setEnabled(false);
            }
        }
    }

    @Override
    public Dialog onCreateDialog(int id) {
        if (id == DIALOG_NOT_DOCKED) {
            return createUndockedMessage();
        }
        return null;
    }

    private String returnTime(String t) {
        if (t == null || t.equals("")) {
            return "";
        }
        int hr = Integer.parseInt(t.trim());
        int mn = hr;

        hr = hr / 60;
        mn = mn % 60;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hr);
        cal.set(Calendar.MINUTE, mn);
        Date date = cal.getTime();
        return DateFormat.getTimeFormat(getActivity().getApplicationContext()).format(date);
    }

    private Dialog createUndockedMessage() {
        final AlertDialog.Builder ab = new AlertDialog.Builder(getActivity());
        ab.setTitle(R.string.dock_not_found_title);
        ab.setMessage(R.string.dock_not_found_text);
        ab.setPositiveButton(android.R.string.ok, null);
        return ab.create();
    }
}
