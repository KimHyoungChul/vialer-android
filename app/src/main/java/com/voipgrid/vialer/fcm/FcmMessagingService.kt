package com.voipgrid.vialer.fcm

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.voipgrid.vialer.User
import com.voipgrid.vialer.api.Middleware
import com.voipgrid.vialer.call.NativeCallManager
import com.voipgrid.vialer.logging.LogHelper
import com.voipgrid.vialer.logging.Logger
import com.voipgrid.vialer.middleware.MiddlewareHelper
import com.voipgrid.vialer.notifications.VoipDisabledNotification
import com.voipgrid.vialer.sip.SipConstants
import com.voipgrid.vialer.sip.SipService
import com.voipgrid.vialer.sip.SipUri
import com.voipgrid.vialer.statistics.VialerStatistics
import com.voipgrid.vialer.util.ConnectivityHelper
import com.voipgrid.vialer.util.PhoneNumberUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.KoinComponent

class FcmMessagingService : FirebaseMessagingService(), KoinComponent {

    private val logger = Logger(this)
    private val connectivityHelper: ConnectivityHelper by inject()
    private val powerManager: PowerManager by inject()
    private val nativeCallManager: NativeCallManager by inject()
    private val middleware: Middleware by inject()

    companion object {
        private const val MAX_MIDDLEWARE_PUSH_ATTEMPTS = 8
        private var lastHandledCall: String? = null
        const val VOIP_HAS_BEEN_DISABLED = "com.voipgrid.vialer.voip_disabled"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val remoteMessageData = RemoteMessageData(remoteMessage.data)
        LogHelper.using(logger).logMiddlewareMessageReceived(remoteMessage, remoteMessageData.requestType)
        VialerStatistics.pushNotificationWasReceived(remoteMessage)

        when {
            !remoteMessageData.hasRequestType() -> logger.e("No requestType")
            remoteMessageData.isCallRequest -> handleCall(remoteMessage, remoteMessageData)
            remoteMessageData.isMessageRequest -> handleMessage(remoteMessage, remoteMessageData)
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        logger.d("Message deleted on the FCM server.")
    }

    private fun handleCall(remoteMessage: RemoteMessage, remoteMessageData: RemoteMessageData) {
        logCurrentState(remoteMessageData)

        when {
            !isConnectionSufficient() -> rejectDueToVialerCallAlreadyInProgress(remoteMessage, remoteMessageData)
            isAVialerCallAlreadyInProgress() -> rejectDueToVialerCallAlreadyInProgress(remoteMessage, remoteMessageData)
            nativeCallManager.isBusyWithNativeCall ->  rejectDueToNativeCallAlreadyInProgress(remoteMessage, remoteMessageData)
            else -> {
                lastHandledCall = remoteMessageData.requestToken

                logger.d("Payload processed, calling startService method")

                startSipService(remoteMessageData)
            }
        }
    }

    private fun handleMessage(remoteMessage: RemoteMessage, remoteMessageData: RemoteMessageData) {

        if (!remoteMessageData.isRegisteredOnOtherDeviceMessage) {
            return
        }

        if (User.voip.hasEnabledSip) {
            VoipDisabledNotification().display()
            User.voip.hasEnabledSip = false
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(VOIP_HAS_BEEN_DISABLED))
    }

    private fun handleInsufficientConnection(remoteMessage: RemoteMessage, remoteMessageData: RemoteMessageData) {
        if (hasExceededMaximumAttempts(remoteMessageData)) {
            VialerStatistics.incomingCallFailedDueToInsufficientNetwork(remoteMessage)
        }
        logger.e(when(isDeviceInIdleMode()){
            true -> "Device in idle mode and connection insufficient. For now do nothing wait for next middleware push."
            false -> "Connection is insufficient. For now do nothing and wait for next middleware push"
        })
    }

    private fun isConnectionSufficient() = connectivityHelper.hasNetworkConnection() && connectivityHelper.hasFastData()

    private fun isAVialerCallAlreadyInProgress() = SipService.sipServiceActive;

    private fun hasExceededMaximumAttempts(remoteMessageData: RemoteMessageData) = remoteMessageData.getAttemptNumber() >= MAX_MIDDLEWARE_PUSH_ATTEMPTS

    private fun rejectDueToVialerCallAlreadyInProgress(remoteMessage: RemoteMessage, remoteMessageData: RemoteMessageData) {
        logger.d("Reject due to call already in progress")

        replyServer(remoteMessageData, false)

        sendCallFailedDueToOngoingVialerCallMetric(remoteMessage, remoteMessageData.requestToken)
    }

    private fun rejectDueToNativeCallAlreadyInProgress(remoteMessage: RemoteMessage, remoteMessageData: RemoteMessageData) {
        logger.d("Reject due to native call already in progress")

        replyServer(remoteMessageData, false)

        VialerStatistics.incomingCallFailedDueToOngoingGsmCall(remoteMessage)
    }

    private fun sendCallFailedDueToOngoingVialerCallMetric(remoteMessage: RemoteMessage, requestToken: String) {
        if (lastHandledCall != null && lastHandledCall == requestToken) {
            logger.i("Push notification ($lastHandledCall) is being rejected because there is a Vialer call already in progress but not sending metric because it was already handled successfully")
            return
        }

        VialerStatistics.incomingCallFailedDueToOngoingVialerCall(remoteMessage)
    }

    private fun replyServer(remoteMessageData: RemoteMessageData, isAvailable: Boolean) = GlobalScope.launch {
        val response =  middleware.reply(remoteMessageData.requestToken, isAvailable, remoteMessageData.messageStartTime).execute()
        if (response.isSuccessful) {
            logger.i("response was successful")
        }
    }

    private fun startSipService(remoteMessageData: RemoteMessageData) {
        val intent = Intent(this, SipService::class.java).apply {
            action = SipService.Actions.HANDLE_INCOMING_CALL
            data = SipUri.sipAddressUri(this@FcmMessagingService,PhoneNumberUtils.format(remoteMessageData.phoneNumber))
            putExtra(SipConstants.EXTRA_RESPONSE_URL, remoteMessageData.responseUrl);
            putExtra(SipConstants.EXTRA_REQUEST_TOKEN, remoteMessageData.requestToken)
            putExtra(SipConstants.EXTRA_PHONE_NUMBER, remoteMessageData.phoneNumber)
            putExtra(SipConstants.EXTRA_CONTACT_NAME, remoteMessageData.callerId)
            putExtra(RemoteMessageData.MESSAGE_START_TIME, remoteMessageData.messageStartTime)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun isDeviceInIdleMode() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager.isDeviceIdleMode

    private fun logCurrentState(remoteMessageData: RemoteMessageData) {
        listOf(
                "SipService Active: " + SipService.sipServiceActive,    "CurrentConnection: " + connectivityHelper.getConnectionTypeString(),
                "Payload: " + remoteMessageData.getRawData().toString()
        ).forEach{
            logger.d(it)
        }
    }

    override fun onNewToken(s: String) {
        super.onNewToken(s)
        logger.d("onTokenRefresh")
        MiddlewareHelper.setRegistrationStatus(
                com.voipgrid.vialer.persistence.Middleware.RegistrationStatus.UNREGISTERED)
        MiddlewareHelper.registerAtMiddleware(this)
    }

}