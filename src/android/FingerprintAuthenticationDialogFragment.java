/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.innerwave.fingerprint;

import android.app.DialogFragment;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A dialog which uses fingerprint APIs to authenticate the user, and falls back to password
 * authentication if fingerprint is not available.
 */
public class FingerprintAuthenticationDialogFragment extends DialogFragment
        implements FingerprintUiHelper.Callback {

    private static final String TAG = "FingerprintAuthDialog";
    private static final int REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS = 1;

    private Button mCancelButton;
    private Button mSecondDialogButton;
    private View mFingerprintContent;

    private Stage mStage = Stage.FINGERPRINT;

    private KeyguardManager mKeyguardManager;
    private FingerprintManager.CryptoObject mCryptoObject;
    private FingerprintUiHelper mFingerprintUiHelper;
    FingerprintUiHelper.FingerprintUiHelperBuilder mFingerprintUiHelperBuilder;

    public FingerprintAuthenticationDialogFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Do not create a new Fragment when the Activity is re-created such as orientation changes.
        setRetainInstance(true);
        setStyle(DialogFragment.STYLE_NO_TITLE, android.R.style.Theme_Material_Light_Dialog);

        mKeyguardManager = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        mFingerprintUiHelperBuilder = new FingerprintUiHelper.FingerprintUiHelperBuilder(
                getContext(), getContext().getSystemService(FingerprintManager.class));

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Bundle args = getArguments();
        Log.d(TAG, "disableBackup: " + FingerprintPlugin.mDisableBackup);

        // Inflate layout
        int fingerprint_dialog_container_id = getResources()
                .getIdentifier("fingerprint_dialog_container", "layout",
                        FingerprintPlugin.packageName);
        View v = inflater.inflate(fingerprint_dialog_container_id, container, false);
        this.getDialog().getWindow().setGravity(Gravity.CENTER);                            
        // Set dialog Title
        int fingerprint_auth_dialog_title_id = getResources()
                .getIdentifier("fingerprint_auth_dialog_title", "id", FingerprintPlugin.packageName);
        TextView dialogTitleTextView = (TextView) v.findViewById(fingerprint_auth_dialog_title_id);
        if (null != FingerprintPlugin.mDialogTitle) {
            dialogTitleTextView.setText(FingerprintPlugin.mDialogTitle);
        }

        // Set dialog message
        int fingerprint_description_id = getResources()
                .getIdentifier("fingerprint_description", "id", FingerprintPlugin.packageName);
        TextView dialogMessageTextView = (TextView) v.findViewById(fingerprint_description_id);
        if (null != FingerprintPlugin.mDialogMessage) {
            dialogMessageTextView.setText(FingerprintPlugin.mDialogMessage);
        }

        // Set dialog hing
        int fingerprint_hint_id = getResources()
                .getIdentifier("fingerprint_status", "id", FingerprintPlugin.packageName);
        TextView dialogHintTextView = (TextView) v.findViewById(fingerprint_hint_id);
        if (null != FingerprintPlugin.mDialogHint) {
            dialogHintTextView.setText(FingerprintPlugin.mDialogHint);
        }

        int cancel_button_id = getResources()
                .getIdentifier("cancel_button", "id", FingerprintPlugin.packageName);
        mCancelButton = (Button) v.findViewById(cancel_button_id);
        mCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FingerprintPlugin.onCancelled();
                dismissAllowingStateLoss();
            }
        });

        int second_dialog_button_id = getResources()
                .getIdentifier("second_dialog_button", "id", FingerprintPlugin.packageName);
        mSecondDialogButton = (Button) v.findViewById(second_dialog_button_id);
        if (FingerprintPlugin.mDisableBackup) {
            mSecondDialogButton.setVisibility(View.GONE);
        }
        mSecondDialogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goToBackup();
            }
        });
        int fingerprint_container_id = getResources()
                .getIdentifier("fingerprint_container", "id", FingerprintPlugin.packageName);
        mFingerprintContent = v.findViewById(fingerprint_container_id);

        int new_fingerprint_enrolled_description_id = getResources()
                .getIdentifier("new_fingerprint_enrolled_description", "id",
                        FingerprintPlugin.packageName);

        int fingerprint_icon_id = getResources()
                .getIdentifier("fingerprint_icon", "id", FingerprintPlugin.packageName);
        int fingerprint_status_id = getResources()
                .getIdentifier("fingerprint_status", "id", FingerprintPlugin.packageName);
        mFingerprintUiHelper = mFingerprintUiHelperBuilder.build(
                (ImageView) v.findViewById(fingerprint_icon_id),
                (TextView) v.findViewById(fingerprint_status_id), this);
        updateStage();

        // If fingerprint authentication is not available, switch immediately to the backup
        // (password) screen.
        if (!mFingerprintUiHelper.isFingerprintAuthAvailable()) {
            goToBackup();
        }
        return v;
    }


    @Override
    public void onResume() {
        super.onResume();
        if (mStage == Stage.FINGERPRINT) {
            mFingerprintUiHelper.startListening(mCryptoObject);
        }
    }

    public void setStage(Stage stage) {
        mStage = stage;
    }

    @Override
    public void onPause() {
        super.onPause();
        mFingerprintUiHelper.stopListening();
    }

    /**
     * Sets the crypto object to be passed in when authenticating with fingerprint.
     */
    public void setCryptoObject(FingerprintManager.CryptoObject cryptoObject) {
        mCryptoObject = cryptoObject;
    }

    /**
     * Switches to backup (password) screen. This either can happen when fingerprint is not
     * available or the user chooses to use the password authentication method by pressing the
     * button. This can also happen when the user had too many fingerprint attempts.
     */
    private void goToBackup() {
        mStage = Stage.BACKUP;
        updateStage();
    }

    private void updateStage() {
        int cancel_id = getResources()
                .getIdentifier("cancel", "string", FingerprintPlugin.packageName);
        switch (mStage) {
            case FINGERPRINT:
                mCancelButton.setText(cancel_id);
                int use_backup_id = getResources()
                        .getIdentifier("use_backup", "string", FingerprintPlugin.packageName);
                mSecondDialogButton.setText(use_backup_id);
                mFingerprintContent.setVisibility(View.VISIBLE);
                break;
            case NEW_FINGERPRINT_ENROLLED:
                // Intentional fall through
            case BACKUP:
                if (mStage == Stage.NEW_FINGERPRINT_ENROLLED) {

                }
                if (!mKeyguardManager.isKeyguardSecure()) {
                    // Show a message that the user hasn't set up a lock screen.
                    int secure_lock_screen_required_id = getResources()
                            .getIdentifier("secure_lock_screen_required", "string",
                                    FingerprintPlugin.packageName);
                    Toast.makeText(getContext(),
                            getString(secure_lock_screen_required_id),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (FingerprintPlugin.mDisableBackup) {
                    FingerprintPlugin.onError("backup disabled");
                    return;
                }
                showAuthenticationScreen();
                break;
        }
    }

    private void showAuthenticationScreen() {
        // Create the Confirm Credentials screen. You can customize the title and description. Or
        // we will provide a generic one for you if you leave it null
        Intent intent = mKeyguardManager.createConfirmDeviceCredentialIntent(null, null);
        if (intent != null) {
            startActivityForResult(intent, REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS) {
            // Challenge completed, proceed with using cipher
            if (resultCode == getActivity().RESULT_OK) {
                FingerprintPlugin.onAuthenticated(false /* used backup */, null);
            } else {
                // The user canceled or didn’t complete the lock screen
                // operation. Go to error/cancellation flow.
                FingerprintPlugin.onCancelled();
            }
            dismissAllowingStateLoss();
        }
    }

    @Override
    public void onAuthenticated(FingerprintManager.AuthenticationResult result) {
        // Callback from FingerprintUiHelper. Let the activity know that authentication was
        // successful.
        FingerprintPlugin.onAuthenticated(true /* withFingerprint */, result);
        dismissAllowingStateLoss();
    }

    @Override
    public void onError(CharSequence errString) {
        if (!FingerprintPlugin.mDisableBackup) {
            if (getActivity() != null && isAdded()) {
                goToBackup();
            }
        } else {
            FingerprintPlugin.onError(errString);
            dismissAllowingStateLoss();

        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        FingerprintPlugin.onCancelled();
    }

    /**
     * Enumeration to indicate which authentication method the user is trying to authenticate with.
     */
    public enum Stage {
        FINGERPRINT,
        NEW_FINGERPRINT_ENROLLED,
        BACKUP
    }
}
