package me.phh.bikefriend

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.DnsSdServiceResponseListener
import android.net.wifi.p2p.WifiP2pManager.DnsSdTxtRecordListener
import android.net.wifi.p2p.WifiP2pManager.EXTRA_NETWORK_INFO
import android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
import android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_GROUP
import android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_INFO
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread


fun l(msg: String) {
    Log.d("BikeFriend", msg)
}

fun l(msg: String, t: Throwable) {
    Log.d("BikeFriend", msg, t)
}

class Main : Activity() {
    lateinit var channel: WifiP2pManager.Channel
    lateinit var p2pService: WifiP2pManager
    lateinit var connectivityManager: ConnectivityManager

    var groupAlreadyFormed = false
    var latestLocation: Location? = null
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        val locationManager = getSystemService(LocationManager::class.java)
        l("Existing location Providers ${locationManager.allProviders.toList()}")
        val bestProvider = locationManager.getBestProvider(
            Criteria().apply {
                speedAccuracy = Criteria.ACCURACY_HIGH
            }, true)
        l("Best enabled provider is $bestProvider")
        locationManager.requestLocationUpdates(bestProvider!!, 10000L, 10.0f) { p0 ->
            val paris = Location("no").apply {
                latitude = 48.8589384
                longitude = 2.264635
            }
            l("Received new location $p0, distance to Paris ${p0.distanceTo(paris)}")
            latestLocation = p0
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        registerReceiver(object: BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(p0: Context, p1: Intent) {
                l("Received intent ${p1.action}")
                if(p1.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                    val newState = p1.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    l("Received new P2P state $newState")
                    return
                }
                if(p1.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                    l("Peer list changed")
                    p2pService.requestPeers(channel) {
                        l("Received peers list ${it.deviceList.size}")
                        val l = it.deviceList.toList()
                        if(l.size == 1 && false) {
                            p2pService.connect(channel, WifiP2pConfig().apply {
                                deviceAddress = l[0].deviceAddress
                                wps.setup = WpsInfo.PBC
                            }, object : ActionListener {
                                override fun onSuccess() {
                                    l("Connected to ${l[0].deviceName}")
                                }

                                override fun onFailure(p0: Int) {
                                    l("Failed to connect to ${l[0].deviceName}")
                                }
                            })
                        }
                        for(i in it.deviceList) {
                            l(" - ${i.deviceName} ${i.deviceAddress} ${i.primaryDeviceType} ${i.secondaryDeviceType} ${i.isGroupOwner}")
                        }
                    }
                    return
                }
                if(p1.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    l("Connection changed")
                    val p2pInfo = p1.getParcelableExtra(EXTRA_WIFI_P2P_INFO) as? WifiP2pInfo
                    val p2pGroup = p1.getParcelableExtra(EXTRA_WIFI_P2P_GROUP) as? WifiP2pGroup
                    val networkInfo = p1.getParcelableExtra(EXTRA_NETWORK_INFO) as? NetworkInfo
                    l("Got p2p info $p2pInfo ${p2pInfo?.groupOwnerAddress} ${p2pInfo?.groupFormed} ${p2pInfo?.isGroupOwner} network info $networkInfo")
                    l("Got p2pGroup $p2pGroup ${p2pGroup?.isGroupOwner} ${p2pGroup?.networkName} ${p2pGroup?.`interface`}")
                    if(p2pInfo?.groupFormed == true && !groupAlreadyFormed) {
                        if(p2pGroup?.isGroupOwner == true) {
                            thread {
                                l("I'm group owner, starting socket!")
                                val ss = ServerSocket(4242)
                                l("Accepting")
                                val newClient = ss.accept()
                                l("Accepted $newClient!")
                                val input = newClient.getInputStream().bufferedReader()
                                val output = newClient.getOutputStream().bufferedWriter()

                                while (true) {
                                    l("Let's go")
                                    if (latestLocation != null) {
                                        output.write("${latestLocation?.latitude} ${latestLocation?.longitude}\n")
                                    } else {
                                        output.write("0.0 0.0\n")
                                    }
                                    output.flush()
                                    val received = input.readLine()
                                    l("Received line $received")
                                    if(received == null) break
                                    Thread.sleep(2000)
                                }
                            }
                        } else {
                            thread {
                                l("Client, connecting to server!")
                                val socket = Socket(p2pInfo.groupOwnerAddress, 4242)
                                val input = socket.getInputStream().bufferedReader()
                                val output = socket.getOutputStream().bufferedWriter()
                                l("Connected!")
                                while(true) {
                                    val received = input.readLine()
                                    l("Received line $received")

                                    l("Let's go")
                                    if (latestLocation != null) {
                                        output.write("${latestLocation?.latitude} ${latestLocation?.longitude}\n")
                                    } else {
                                        output.write("0.0 0.0\n")
                                    }
                                    output.flush()
                                }

                            }
                        }
                        groupAlreadyFormed = true
                    }
                    return
                }
                if(p1.action == WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION) {
                    l("This device changed")
                    val p2pDevice = p1.getParcelableExtra<WifiP2pDevice>(EXTRA_WIFI_P2P_DEVICE)
                    l("This device is ${p2pDevice?.deviceName} ${p2pDevice?.status} ${p2pDevice?.deviceAddress} ${p2pDevice?.primaryDeviceType}")
                    return
                }
            }
        }, intentFilter)

        l("A")
        p2pService = getSystemService(WifiP2pManager::class.java)
        l("B")
        channel = p2pService.initialize(this, mainLooper) {
            l("Channel disconnected")
        }
        l("C")

        p2pService.discoverPeers(channel, object: ActionListener {
            override fun onSuccess() {
                l("Successfully discovered peers")
            }

            override fun onFailure(p0: Int) {
                l("Failed discovering peers")
            }
        })

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "_test", "_bikefriend._tcp", emptyMap())
        p2pService.addLocalService(channel, serviceInfo, object: ActionListener {
            override fun onSuccess() {
                l("Register bikefriend successful")
            }

            override fun onFailure(p0: Int) {
                l("Register bikefriend failed")
            }
        })

        val txtListener = DnsSdTxtRecordListener { fullDomain, record, device ->
            l("Got TXT $fullDomain $record $device")
        }

        val srvListener = DnsSdServiceResponseListener { instanceName, registrationType, device ->
            l("Got service reponse $instanceName $registrationType $device")
            if(registrationType == "_bikefriend._tcp.local.") {
                p2pService.connect(channel, WifiP2pConfig().apply {
                    deviceAddress = device.deviceAddress
                    wps.setup = WpsInfo.PBC
                }, object : ActionListener {
                    override fun onSuccess() {
                        l("Connected to ${device.deviceName}")
                    }

                    override fun onFailure(p0: Int) {
                        l("Failed to connect to ${device.deviceName}")
                    }
                })
            }
        }

        p2pService.setDnsSdResponseListeners(channel, srvListener, txtListener)
        val srvRequest = WifiP2pDnsSdServiceRequest.newInstance()
        p2pService.addServiceRequest(
            channel,
            srvRequest,
            object: ActionListener {
                override fun onSuccess() {
                    l("Successfully requested services")
                }

                override fun onFailure(p0: Int) {
                    l("Failed requesting services")
                }
            })

        p2pService.discoverServices(channel, object: ActionListener {
            override fun onSuccess() {
                l("Successfully discovered services")
            }

            override fun onFailure(p0: Int) {
                l("Failed discovering services")
            }
        })

        val tv = TextView(this)
        tv.text = "Hello world"
        setContentView(tv)
    }

    override fun onPause() {
        super.onPause()
        //channel.close()
    }
}