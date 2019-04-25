package com.voipgrid.vialer.util;

import static com.voipgrid.vialer.middleware.MiddlewareConstants.STATUS_UPDATE_NEEDED;

import android.content.Context;
import android.os.AsyncTask;

import com.voipgrid.vialer.Preferences;
import com.voipgrid.vialer.api.VoipgridApi;
import com.voipgrid.vialer.api.SecureCalling;
import com.voipgrid.vialer.api.ServiceGenerator;
import com.voipgrid.vialer.api.models.PhoneAccount;
import com.voipgrid.vialer.api.models.SystemUser;
import com.voipgrid.vialer.middleware.MiddlewareHelper;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;


/**
 * AsyncTask for updating a users phone account.
 */
public class PhoneAccountHelper {

    private VoipgridApi mVoipgridApi;
    private Context mContext;
    private Preferences mPreferences;
    private JsonStorage mJsonStorage;
    private SecureCalling mSecureCalling;

    public PhoneAccountHelper(Context context) {
        mContext = context;

        mPreferences = new Preferences(context);
        mJsonStorage = new JsonStorage(context);
        mSecureCalling = SecureCalling.fromContext(context);

        mVoipgridApi = ServiceGenerator.createApiService(mContext);
    }

    /**
     * Function to get the current systemuser from the api.
     * @return
     */
    public SystemUser getAndUpdateSystemUser() {
        Call<SystemUser> call = mVoipgridApi.systemUser();
        SystemUser systemUser = (SystemUser) mJsonStorage.get(SystemUser.class);
        if (systemUser == null) {
            systemUser = null;
        }
        try {
            Response<SystemUser> response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                systemUser = response.body();
                updateSystemUser(systemUser);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return systemUser;
    }

    /**
     * Function to update the systemuser information.
     */
    private void updateSystemUser(SystemUser systemUser) {
        SystemUser currentSystemuser = (SystemUser) mJsonStorage.get(SystemUser.class);
        currentSystemuser.setOutgoingCli(systemUser.getOutgoingCli());
        currentSystemuser.setMobileNumber(systemUser.getMobileNumber());
        currentSystemuser.setClient(systemUser.getClient());
        currentSystemuser.setAppAccountUri(systemUser.getAppAccountUri());

        mJsonStorage.save(currentSystemuser);
    }


    /**
     * Function to get the phone linked to the user. This also updates the systemuser.
     * @return PhoneAccount object or null.
     */
    public PhoneAccount getLinkedPhoneAccount() {
        SystemUser systemUser = getAndUpdateSystemUser();
        PhoneAccount phoneAccount = (PhoneAccount) mJsonStorage.get(PhoneAccount.class);
        if (phoneAccount == null) {
            phoneAccount = null;
        }

        if (systemUser != null) {
            mPreferences.setSipPermission(true);
            String phoneAccountId = systemUser.getPhoneAccountId();

            // Get phone account from API if one is provided in the systemuser API.
            if (phoneAccountId != null) {

                // If no PhoneAccountId is returned, remove current PhoneAccount information from jsonstorage.
                new JsonStorage(mContext).remove(PhoneAccount.class);

                Call<PhoneAccount> phoneAccountCall = mVoipgridApi.phoneAccount(phoneAccountId);
                try {
                    Response<PhoneAccount> phoneAccountResponse = phoneAccountCall.execute();
                    if (phoneAccountResponse.isSuccessful() && phoneAccountResponse.body() != null) {
                        phoneAccount = phoneAccountResponse.body();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return phoneAccount;
    }

    public void savePhoneAccountAndRegister(PhoneAccount phoneAccount) {
        // Check if we have something to save and register.
        if (phoneAccount == null) {
            return;
        }

        // Get the phone account currently saved in the local storage.
        PhoneAccount existingPhoneAccount = ((PhoneAccount) mJsonStorage.get(PhoneAccount.class));
        // Save the linked phone account in the local storage.
        mJsonStorage.save(phoneAccount);

        // Check if the user can use sip and if the phone account changed or unregistered.
        if (mPreferences.canUseSip()) {
            boolean register = false;
            if (!phoneAccount.equals(existingPhoneAccount)) {
                // New registration because phone account changed.
                MiddlewareHelper.setRegistrationStatus(mContext, STATUS_UPDATE_NEEDED);
                register = true;
            } else if (MiddlewareHelper.needsRegistration(mContext)) {
                // New registration because we need a registration.
                register = true;
            }

            if (register) {
                startMiddlewareRegistrationService();
            }
        }
    }

    /**
     * Function for start the service that handles the middleware registration.
     */
    private void startMiddlewareRegistrationService() {
        MiddlewareHelper.registerAtMiddleware(mContext);
    }

    /**
     * Function to update the phone account and register at the middleware if it changed.
     */
    public void updatePhoneAccount() {
        // Get the phone account that is linked to user.
        PhoneAccount linkedPhoneAccount = getLinkedPhoneAccount();

        if (linkedPhoneAccount != null) {
            savePhoneAccountAndRegister(linkedPhoneAccount);
            mSecureCalling.updateApiBasedOnCurrentPreferenceSetting(null);
        } else {
            // User has no phone account linked so remove it from the local storage.
            mJsonStorage.remove(PhoneAccount.class);
        }
    }

    public void executeUpdatePhoneAccountTask() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                updatePhoneAccount();
                return null;
            }
        }.execute();
    }
}
