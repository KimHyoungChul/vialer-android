package com.voipgrid.vialer.koin

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import com.voipgrid.vialer.api.ServiceGenerator
import com.voipgrid.vialer.call.NativeCallManager
import com.voipgrid.vialer.util.ConnectivityHelper
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val vialerModule = module {
    single { ConnectivityHelper(get(),get()) }
    single { androidContext().getSystemService(Context.POWER_SERVICE) as PowerManager }
    single { NativeCallManager(get()) }
    single { ServiceGenerator.createRegistrationService(androidContext()) }
    single { androidContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }
    single { androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
}