package com.llmperf.llmperf_apk.data.metrics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.llmperf.llmperf_apk.data.location.LocationHelper
import com.llmperf.llmperf_apk.data.model.RanMetrics
import cz.mroczis.netmonster.core.INetMonster
import cz.mroczis.netmonster.core.db.model.NetworkType
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.CellLte
import cz.mroczis.netmonster.core.model.cell.CellNr
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers a minimal RAN metrics snapshot:
 * network type (incl. NR NSA/SA distinction), signal level from Telephony, and
 * rsrp/rsrq/sinr of the serving cell from NetMonster.
 */
class RanMetricsCollector(private val context: Context) {

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    fun hasRequiredPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun collect(): RanMetrics? {
        if (!hasRequiredPermissions()) return null

        return withContext(Dispatchers.IO) {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val netMonster = NetMonsterFactory.get(context)
            val cells = netMonster.getCells()
            val primaryCell = cells.firstOrNull { it.connectionStatus is PrimaryConnection }
            val subscriptionId = primaryCell?.subscriptionId
                ?: SubscriptionManager.getDefaultDataSubscriptionId()

            // The secondary NR carrier attached to the LTE anchor in NSA mode is reported
            // as its own CellNr entry (not the PrimaryConnection cell) — pick the one with
            // the most complete signal report, same as Exfo-GenAI's NetMonsterHelper.
            val nrCell = cells.filterIsInstance<CellNr>()
                .filter { it.signal.ssRsrp != null || it.signal.ssRsrq != null || it.signal.ssSinr != null }
                .minByOrNull { nullSignalFieldCount(it) }

            val ratType = readRatType(telephonyManager, netMonster, subscriptionId, nrCell)
            val signalLevel = readSignalLevel(telephonyManager)

            // LTE anchor cell — present in plain LTE and as the NSA anchor. NetMonster
            // reports it as the PrimaryConnection cell in both cases.
            val lteCell = primaryCell as? CellLte

            // NR signal — the NSA secondary carrier (nrCell) if attached, otherwise the
            // primary cell itself when it's a standalone (SA) NR cell.
            val nrSignalCell = nrCell ?: (primaryCell as? CellNr)

            // NetMonster's CellNr (sourced from getAllCellInfo()) often leaves ssSinr (and
            // sometimes ssRsrp/ssRsrq) null on many OEMs even when a live value is available —
            // fall back to the raw SignalStrength snapshot, same second source Exfo-GenAI reads
            // (MainViewmodel.populateCellList's activeSignalStrength, from
            // TelephonyManager.signalStrength.cellSignalStrengths).
            val activeNrSignal = readActiveNrSignalStrength(telephonyManager)

            val primaryPci = when (primaryCell) {
                is CellLte -> primaryCell.pci
                is CellNr -> primaryCell.pci
                else -> null
            }

            // Non-fatal: a denied permission, disabled GPS, or no address for the fix
            // just leaves these fields null, same as Exfo-GenAI's executeAllTasks usage
            // (promptEnableGPS = false — this runs on a background poll, not user action).
            val cityAndState = LocationHelper(context).getCityAndState(promptEnableGPS = false)

            RanMetrics(
                carrierName = readCarrierName(subscriptionId),
                connectionLabel = readConnectionLabel(telephonyManager, primaryPci, ratType),
                networkType = ratType,
                signalLevel = signalLevel,
                rsrp = lteCell?.signal?.rsrp,
                rsrq = lteCell?.signal?.rsrq,
                snr = lteCell?.signal?.snr,
                enb = lteCell?.enb,
                ssRsrp = nrSignalCell?.signal?.ssRsrp?.toDouble()
                    ?: activeNrSignal?.ssRsrp.asAvailableOrNull()?.toDouble(),
                ssRsrq = nrSignalCell?.signal?.ssRsrq?.toDouble()
                    ?: activeNrSignal?.ssRsrq.asAvailableOrNull()?.toDouble(),
                ssSinr = nrSignalCell?.signal?.ssSinr?.toDouble()
                    ?: activeNrSignal?.ssSinr.asAvailableOrNull()?.toDouble(),
                latitude = cityAndState?.latitude,
                longitude = cityAndState?.longitude,
                city = cityAndState?.city,
                state = cityAndState?.state,
                country = cityAndState?.country
            )
        }
    }

    /**
     * NetMonster classifies NR as NSA/SA/LTE using its own cell-source heuristics —
     * the same approach as Exfo-GenAI's NetMonsterHelper.getRatType, and what the "5G"
     * badge on carrier UIs is actually based on. This differs from
     * TelephonyManager.dataNetworkType, which reports NR even in NSA mode where the
     * anchor radio is still LTE. An "NSA" classification with no real NR secondary-cell
     * signal is demoted to LTE, mirroring Exfo-GenAI's fallback for when the NR leg
     * hasn't attached yet (dataNetworkType flips to NR before NetMonster sees NR signal).
     */
    @SuppressLint("MissingPermission")
    private fun readRatType(
        telephonyManager: TelephonyManager,
        netMonster: INetMonster,
        subscriptionId: Int,
        nrCell: CellNr?
    ): String =
        when (netMonster.getNetworkType(subscriptionId)) {
            is NetworkType.Nr.Nsa -> if (nrCell != null) "NSA" else "LTE"
            is NetworkType.Nr.Sa -> "SA"
            is NetworkType.Lte -> "LTE"
            else -> readLegacyNetworkType(telephonyManager)
        }

    @SuppressLint("MissingPermission")
    private fun readCarrierName(subscriptionId: Int): String? {
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        return subscriptionManager.getActiveSubscriptionInfo(subscriptionId)?.carrierName?.toString()
    }

    /**
     * Builds a friendly "<Operator> 5G" style label, same as Exfo-GenAI's
     * MainViewmodel.populateCellData: the operator name comes from
     * CellIdentity.operatorAlphaLong — the name the serving tower itself broadcasts
     * (PLMN long-alpha) — matched to our primary cell by PCI, not from
     * SubscriptionManager (which reflects the SIM's billing carrier and can differ
     * from the network actually being used, e.g. on MVNOs or while roaming). The
     * generation suffix reuses our existing ratType classification.
     */
    @SuppressLint("MissingPermission")
    private fun readConnectionLabel(
        telephonyManager: TelephonyManager,
        primaryPci: Int?,
        ratType: String
    ): String? {
        val operatorAlphaLong = readOperatorAlphaLong(telephonyManager, primaryPci) ?: return null
        val generation = when (ratType) {
            "NSA", "SA" -> "5G"
            "LTE" -> "4G"
            "UMTS" -> "3G"
            "GSM" -> "2G"
            else -> return operatorAlphaLong
        }
        return "$operatorAlphaLong $generation"
    }

    @SuppressLint("MissingPermission")
    private fun readOperatorAlphaLong(telephonyManager: TelephonyManager, pci: Int?): String? {
        if (pci == null) return null

        return telephonyManager.allCellInfo?.firstNotNullOfOrNull { cellInfo ->
            when (cellInfo) {
                is CellInfoLte -> (cellInfo.cellIdentity as CellIdentityLte)
                    .takeIf { it.pci == pci }
                    ?.operatorAlphaLong

                is CellInfoNr -> (cellInfo.cellIdentity as CellIdentityNr)
                    .takeIf { it.pci == pci }
                    ?.operatorAlphaLong

                else -> null
            }
        }?.toString()
    }

    @SuppressLint("MissingPermission")
    private fun readLegacyNetworkType(telephonyManager: TelephonyManager): String =
        when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            else -> "UNKNOWN"
        }

    @SuppressLint("MissingPermission")
    private fun readSignalLevel(telephonyManager: TelephonyManager): Int =
        telephonyManager.signalStrength?.level ?: -1

    @SuppressLint("MissingPermission")
    private fun readActiveNrSignalStrength(telephonyManager: TelephonyManager): CellSignalStrengthNr? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return telephonyManager.signalStrength
            ?.cellSignalStrengths
            ?.filterIsInstance<CellSignalStrengthNr>()
            ?.firstOrNull()
    }

    /** SignalStrength getters return Int.MAX_VALUE (2147483647) rather than null when unset. */
    private fun Int?.asAvailableOrNull(): Int? = this?.takeIf { it != Int.MAX_VALUE }

    private fun nullSignalFieldCount(cell: CellNr): Int =
        listOf(cell.signal.ssRsrp, cell.signal.ssRsrq, cell.signal.ssSinr).count { it == null }
}
