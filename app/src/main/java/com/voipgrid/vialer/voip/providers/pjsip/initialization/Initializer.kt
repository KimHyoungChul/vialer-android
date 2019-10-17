package com.voipgrid.vialer.voip.providers.pjsip.initialization

import com.voipgrid.vialer.sip.SipLogWriter
import com.voipgrid.vialer.voip.core.Configuration
import com.voipgrid.vialer.voip.providers.pjsip.PjsipProvider
import com.voipgrid.vialer.voip.providers.pjsip.core.PjsipEndpoint
import com.voipgrid.vialer.voip.providers.pjsip.initialization.config.CodecConfigurator
import com.voipgrid.vialer.voip.providers.pjsip.initialization.config.endpoint.LogConfigurator
import com.voipgrid.vialer.voip.providers.pjsip.initialization.config.endpoint.MediaConfigurator
import com.voipgrid.vialer.voip.providers.pjsip.initialization.config.endpoint.UAConfigurator
import com.voipgrid.vialer.voip.providers.pjsip.logging.PjsipLogWriter
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e

class Initializer {

    private val endpointConfigurators = listOf(
            MediaConfigurator(),
            UAConfigurator(),
            LogConfigurator()
    )

    /**
     * Initialize and instantiate the sip library, and provide all configuration necessary
     * based on the config object.
     *
     */
    fun initialize(configuration: Configuration): PjsipEndpoint {
        loadLibrary()
        return setupEndpoint(configuration)
    }

    /**
     * Load the native pjsip library.
     *
     */
    private fun loadLibrary() {
        try {
            System.loadLibrary(PJSIP_LIBRARY_NAME)
        } catch (error: UnsatisfiedLinkError) {
            throw Exception("Library failed to load")
        }
    }

    /**
     * Setup the endpoint, applying all endpoint configurators to it.
     *
     */
    private fun setupEndpoint(configuration: Configuration): PjsipEndpoint {
        val endpoint = PjsipEndpoint()
        endpoint.libCreate()
        PjsipProvider.logWriter = SipLogWriter()
        val endpointConfig = EpConfig()
        endpointConfigurators.forEach { it.configure(configuration, endpointConfig) }

        endpoint.apply {
            libInit(endpointConfig)
            transportCreate(convertTransportTypeToPjSipTransportType(configuration), TransportConfig())
            libStart()
        }

        CodecConfigurator().configure(endpoint, configuration)
        return endpoint
    }

    /**
     * Convert our transport representation to the pjsip representation.
     *
     */
    private fun convertTransportTypeToPjSipTransportType(configuration: Configuration) = when(configuration.transport) {
        Configuration.Transport.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
        Configuration.Transport.TCP -> pjsip_transport_type_e.PJSIP_TRANSPORT_TCP
        Configuration.Transport.TLS -> pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
    }

    companion object {
        const val PJSIP_LIBRARY_NAME = "pjsua2"
    }
}