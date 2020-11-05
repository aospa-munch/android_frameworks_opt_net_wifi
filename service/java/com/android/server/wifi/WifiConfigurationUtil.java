/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.ALL_ZEROS_MAC_ADDRESS;

import static com.android.server.wifi.util.NativeUtil.addEnclosingQuotes;

import android.net.IpConfiguration;
import android.net.MacAddress;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiScanner;
import android.os.PatternMatcher;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.NativeUtil;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Objects;

/**
 * WifiConfiguration utility for any {@link android.net.wifi.WifiConfiguration} related operations.
 * Currently contains:
 *   > Helper method to check if the WifiConfiguration object is visible to the provided users.
 *   > Helper methods to identify the encryption of a WifiConfiguration object.
 */
public class WifiConfigurationUtil {
    private static final String TAG = "WifiConfigurationUtil";

    /**
     * Constants used for validating external config objects.
     */
    private static final int ENCLOSING_QUOTES_LEN = 2;
    private static final int SSID_UTF_8_MIN_LEN = 1 + ENCLOSING_QUOTES_LEN;
    private static final int SSID_UTF_8_MAX_LEN = // wifigbk++
                        WifiGbk.MAX_SSID_UTF_LENGTH + ENCLOSING_QUOTES_LEN;
    private static final int SSID_HEX_MIN_LEN = 2;
    private static final int SSID_HEX_MAX_LEN = 64;
    private static final int PSK_ASCII_MIN_LEN = 8 + ENCLOSING_QUOTES_LEN;
    private static final int SAE_ASCII_MIN_LEN = 1 + ENCLOSING_QUOTES_LEN;
    private static final int PSK_SAE_ASCII_MAX_LEN = 63 + ENCLOSING_QUOTES_LEN;
    private static final int PSK_SAE_HEX_LEN = 64;
    private static final int WEP104_KEY_BYTES_LEN = 13;
    private static final int WEP40_KEY_BYTES_LEN = 5;

    @VisibleForTesting
    public static final String PASSWORD_MASK = "*";
    private static final String MATCH_EMPTY_SSID_PATTERN_PATH = "";
    private static final Pair<MacAddress, MacAddress> MATCH_NONE_BSSID_PATTERN =
            new Pair<>(MacAddress.BROADCAST_ADDRESS, MacAddress.BROADCAST_ADDRESS);
    private static final Pair<MacAddress, MacAddress> MATCH_ALL_BSSID_PATTERN =
            new Pair<>(ALL_ZEROS_MAC_ADDRESS, ALL_ZEROS_MAC_ADDRESS);

    /**
     * Checks if the provided |wepKeys| array contains any non-null value;
     */
    public static boolean hasAnyValidWepKey(String[] wepKeys) {
        for (int i = 0; i < wepKeys.length; i++) {
            if (wepKeys[i] != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to check if the provided |config| corresponds to a PSK network or not.
     */
    public static boolean isConfigForPskNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK);
    }

    /**
     * Helper method to check if the provided |config| corresponds to a WAPI PSK network or not.
     */
    public static boolean isConfigForWapiPskNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WAPI_PSK);
    }

    /**
     * Helper method to check if the provided |config| corresponds to a WAPI CERT network or not.
     */
    public static boolean isConfigForWapiCertNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WAPI_CERT);
    }

    /**
     * Helper method to check if the provided |config| corresponds to an SAE network or not.
     */
    public static boolean isConfigForSaeNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE);
    }

    /**
     * Helper method to check if the provided |config| corresponds to an OWE network or not.
     */
    public static boolean isConfigForOweNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE);
    }

    /**
     * Helper method to check if the provided |config| corresponds to a EAP network or not.
     */
    public static boolean isConfigForEapNetwork(WifiConfiguration config) {
        return (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X));
    }

    /**
     * Helper method to check if the provided |config| corresponds to a EAP Suite-B network or not.
     */
    public static boolean isConfigForEapSuiteBNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192);
    }

    /**
     * Helper method to check if the provided |config| corresponds to a WEP network or not.
     */
    public static boolean isConfigForWepNetwork(WifiConfiguration config) {
        return (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && hasAnyValidWepKey(config.wepKeys));
    }

    /**
     * Helper method to check if the provided |config| corresponds to an open or enhanced
     * open network, or not.
     */
    public static boolean isConfigForOpenNetwork(WifiConfiguration config) {
        return (!(isConfigForWepNetwork(config) || isConfigForPskNetwork(config)
                || isConfigForEapNetwork(config) || isConfigForSaeNetwork(config)
                || isConfigForEapSuiteBNetwork(config)));
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * IP parameters have changed or not.
     *
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if IP parameters have changed, false otherwise.
     */
    public static boolean hasIpChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (existingConfig.getIpAssignment() != newConfig.getIpAssignment()) {
            return true;
        }
        if (newConfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
            return !Objects.equals(existingConfig.getStaticIpConfiguration(),
                    newConfig.getStaticIpConfiguration());
        }
        return false;
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * proxy parameters have changed or not.
     *
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if proxy parameters have changed, false if no existing config and proxy settings
     * are NONE, false otherwise.
     */
    public static boolean hasProxyChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (existingConfig == null) {
            return newConfig.getProxySettings() != IpConfiguration.ProxySettings.NONE;
        }
        if (newConfig.getProxySettings() != existingConfig.getProxySettings()) {
            return true;
        }
        return !Objects.equals(existingConfig.getHttpProxy(), newConfig.getHttpProxy());
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * MAC randomization setting has changed or not.
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if MAC randomization setting setting changed or the existing confiuration is
     * null and the newConfig is setting macRandomizationSetting to the default value.
     */
    public static boolean hasMacRandomizationSettingsChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (existingConfig == null) {
            return newConfig.macRandomizationSetting != WifiConfiguration.RANDOMIZATION_AUTO;
        }
        return newConfig.macRandomizationSetting != existingConfig.macRandomizationSetting;
    }

    /**
     * Compare existing and new WifiEnterpriseConfig objects after a network update and return if
     * credential parameters have changed or not.
     *
     * @param existingEnterpriseConfig Existing WifiConfiguration object corresponding to the
     *                                 network.
     * @param newEnterpriseConfig      New WifiConfiguration object corresponding to the network.
     * @return true if credentials have changed, false otherwise.
     */
    @VisibleForTesting
    public static boolean hasEnterpriseConfigChanged(WifiEnterpriseConfig existingEnterpriseConfig,
            WifiEnterpriseConfig newEnterpriseConfig) {
        if (existingEnterpriseConfig != null && newEnterpriseConfig != null) {
            if (existingEnterpriseConfig.getEapMethod() != newEnterpriseConfig.getEapMethod()) {
                return true;
            }
            if (existingEnterpriseConfig.isAuthenticationSimBased()) {
                // No other credential changes for SIM based methods.
                // The SIM card is the credential.
                return false;
            }
            if (existingEnterpriseConfig.getPhase2Method()
                    != newEnterpriseConfig.getPhase2Method()) {
                return true;
            }
            if (getEapSimNum(existingEnterpriseConfig) != getEapSimNum(newEnterpriseConfig)){
                return true;
            }
            if (!TextUtils.equals(existingEnterpriseConfig.getIdentity(),
                                  newEnterpriseConfig.getIdentity())) {
                return true;
            }
            if (!TextUtils.equals(existingEnterpriseConfig.getAnonymousIdentity(),
                    newEnterpriseConfig.getAnonymousIdentity())) {
                return true;
            }
            if (!TextUtils.equals(existingEnterpriseConfig.getPassword(),
                                    newEnterpriseConfig.getPassword())) {
                return true;
            }
            X509Certificate[] existingCaCerts = existingEnterpriseConfig.getCaCertificates();
            X509Certificate[] newCaCerts = newEnterpriseConfig.getCaCertificates();
            if (!Arrays.equals(existingCaCerts, newCaCerts)) {
                return true;
            }
            if (!Arrays.equals(newEnterpriseConfig.getCaCertificateAliases(),
                    existingEnterpriseConfig.getCaCertificateAliases())) {
                return true;
            }
            if (!TextUtils.equals(newEnterpriseConfig.getClientCertificateAlias(),
                    existingEnterpriseConfig.getClientCertificateAlias())) {
                return true;
            }
            if (!TextUtils.equals(newEnterpriseConfig.getAltSubjectMatch(),
                    existingEnterpriseConfig.getAltSubjectMatch())) {
                return true;
            }
            if (newEnterpriseConfig.getOcsp() != existingEnterpriseConfig.getOcsp()) {
                return true;
            }
        } else {
            // One of the configs may have an enterpriseConfig
            if (existingEnterpriseConfig != null || newEnterpriseConfig != null) {
                return true;
            }
        }
        return false;
    }

    public static int getEapSimNum(WifiEnterpriseConfig enterpriseConfig){
        int mSimNum =0;
        if (enterpriseConfig.getSimNum() != null
                             && !enterpriseConfig.getSimNum().isEmpty()) {
            mSimNum = Integer.parseInt(enterpriseConfig.getSimNum());
        }
        return mSimNum;
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * credential parameters have changed or not.
     *
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if credentials have changed, false otherwise.
     */
    public static boolean hasCredentialChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (!Objects.equals(existingConfig.allowedKeyManagement,
                newConfig.allowedKeyManagement)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedProtocols, newConfig.allowedProtocols)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedAuthAlgorithms,
                newConfig.allowedAuthAlgorithms)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedPairwiseCiphers,
                newConfig.allowedPairwiseCiphers)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedGroupCiphers,
                newConfig.allowedGroupCiphers)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedGroupManagementCiphers,
                newConfig.allowedGroupManagementCiphers)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedSuiteBCiphers,
                newConfig.allowedSuiteBCiphers)) {
            return true;
        }
        if (!Objects.equals(existingConfig.preSharedKey, newConfig.preSharedKey)) {
            return true;
        }
        if (!Arrays.equals(existingConfig.wepKeys, newConfig.wepKeys)) {
            return true;
        }
        if (existingConfig.wepTxKeyIndex != newConfig.wepTxKeyIndex) {
            return true;
        }
        if (existingConfig.hiddenSSID != newConfig.hiddenSSID) {
            return true;
        }
        if (existingConfig.requirePmf != newConfig.requirePmf) {
            return true;
        }
        if (existingConfig.carrierId != newConfig.carrierId) {
            return true;
        }
        if (hasEnterpriseConfigChanged(existingConfig.enterpriseConfig,
                newConfig.enterpriseConfig)) {
            return true;
        }
        return false;
    }

    private static boolean validateSsid(String ssid, boolean isAdd) {
        if (isAdd) {
            if (ssid == null) {
                Log.e(TAG, "validateSsid : null string");
                return false;
            }
        } else {
            if (ssid == null) {
                // This is an update, so the SSID can be null if that is not being changed.
                return true;
            }
        }
        if (ssid.isEmpty()) {
            Log.e(TAG, "validateSsid failed: empty string");
            return false;
        }
        if (ssid.startsWith("\"")) {
            // UTF-8 SSID string
            byte[] ssidBytes = ssid.getBytes(StandardCharsets.UTF_8);
            if (ssidBytes.length < SSID_UTF_8_MIN_LEN) {
                Log.e(TAG, "validateSsid failed: utf-8 ssid string size too small: "
                        + ssidBytes.length);
                return false;
            }
            if (ssidBytes.length > SSID_UTF_8_MAX_LEN) {
                Log.e(TAG, "validateSsid failed: utf-8 ssid string size too large: "
                        + ssidBytes.length);
                return false;
            }
        } else {
            // HEX SSID string
            if (ssid.length() < SSID_HEX_MIN_LEN) {
                Log.e(TAG, "validateSsid failed: hex string size too small: " + ssid.length());
                return false;
            }
            if (ssid.length() > SSID_HEX_MAX_LEN) {
                Log.e(TAG, "validateSsid failed: hex string size too large: " + ssid.length());
                return false;
            }
        }
        try {
            NativeUtil.decodeSsid(ssid);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "validateSsid failed: malformed string: " + ssid);
            return false;
        }
        return true;
    }

    private static boolean validateBssid(MacAddress bssid) {
        if (bssid == null) return true;
        if (bssid.getAddressType() != MacAddress.TYPE_UNICAST) {
            Log.e(TAG, "validateBssid failed: invalid bssid");
            return false;
        }
        return true;
    }

    private static boolean validateBssid(String bssid) {
        if (bssid == null) return true;
        if (bssid.isEmpty()) {
            Log.e(TAG, "validateBssid failed: empty string");
            return false;
        }
        // Allow reset of bssid with "any".
        if (bssid.equals(ClientModeImpl.SUPPLICANT_BSSID_ANY)) return true;
        MacAddress bssidMacAddress;
        try {
            bssidMacAddress = MacAddress.fromString(bssid);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "validateBssid failed: malformed string: " + bssid);
            return false;
        }
        if (!validateBssid(bssidMacAddress)) {
            return false;
        }
        return true;
    }

    private static boolean validatePassword(String password, boolean isAdd, boolean isSae) {
        if (isAdd) {
            if (password == null) {
                Log.e(TAG, "validatePassword: null string");
                return false;
            }
        } else {
            if (password == null) {
                // This is an update, so the psk can be null if that is not being changed.
                return true;
            } else if (password.equals(PASSWORD_MASK)) {
                // This is an update, so the app might have returned back the masked password, let
                // it thru. WifiConfigManager will handle it.
                return true;
            }
        }
        if (password.isEmpty()) {
            Log.e(TAG, "validatePassword failed: empty string");
            return false;
        }
        if (password.startsWith("\"")) {
            // ASCII PSK string
            byte[] passwordBytes = password.getBytes(StandardCharsets.US_ASCII);
            int targetMinLength;

            if (isSae) {
                targetMinLength = SAE_ASCII_MIN_LEN;
            } else {
                targetMinLength = PSK_ASCII_MIN_LEN;
            }
            if (passwordBytes.length < targetMinLength) {
                Log.e(TAG, "validatePassword failed: ASCII string size too small: "
                        + passwordBytes.length);
                return false;
            }
            if (passwordBytes.length > PSK_SAE_ASCII_MAX_LEN) {
                Log.e(TAG, "validatePassword failed: ASCII string size too large: "
                        + passwordBytes.length);
                return false;
            }
        } else {
            // HEX PSK string
            if (password.length() != PSK_SAE_HEX_LEN) {
                Log.e(TAG, "validatePassword failed: hex string size mismatch: "
                        + password.length());
                return false;
            }
        }
        try {
            NativeUtil.hexOrQuotedStringToBytes(password);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "validatePassword failed: malformed string: " + password);
            return false;
        }
        return true;
    }

    private static boolean validateWepKeys(String[] wepKeys, int wepTxKeyIndex, boolean isAdd) {
        if (isAdd) {
            if (wepKeys == null) {
                Log.e(TAG, "validateWepKeys: null string");
                return false;
            }
        } else {
            if (wepKeys == null) {
                // This is an update, so the psk can be null if that is not being changed.
                return true;
            } else {
                boolean allMaskedKeys = true;
                for (int i = 0; i < wepKeys.length; i++) {
                    if (wepKeys[i] != null && !TextUtils.equals(wepKeys[i], PASSWORD_MASK)) {
                        allMaskedKeys = false;
                    }
                }
                if (allMaskedKeys) {
                    // This is an update, so the app might have returned back the masked password,
                    // let it thru. WifiConfigManager will handle it.
                    return true;
                }
            }
        }
        for (int i = 0; i < wepKeys.length; i++) {
            if (wepKeys[i] != null) {
                try {
                    ArrayList<Byte> wepKeyBytes =
                            NativeUtil.hexOrQuotedStringToBytes(wepKeys[i]);
                    if (wepKeyBytes.size() != WEP40_KEY_BYTES_LEN
                            && wepKeyBytes.size() != WEP104_KEY_BYTES_LEN) {
                        Log.e(TAG, "validateWepKeys: invalid wep key length "
                                + wepKeys[i].length() + " at index " + i);
                        return false;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "validateWepKeys: invalid wep key at index " + i, e);
                    return false;
                }
            }
        }
        if (wepTxKeyIndex >= wepKeys.length) {
            Log.e(TAG, "validateWepKeys: invalid wep tx key index " + wepTxKeyIndex
                    + " wepKeys len: " + wepKeys.length);
            return false;
        }
        return true;
    }

    private static boolean validateBitSet(BitSet bitSet, int validValuesLength) {
        if (bitSet == null) return false;
        BitSet clonedBitset = (BitSet) bitSet.clone();
        clonedBitset.clear(0, validValuesLength);
        return clonedBitset.isEmpty();
    }

    private static boolean validateBitSets(WifiConfiguration config) {
        // 1. Check |allowedKeyManagement|.
        if (!validateBitSet(config.allowedKeyManagement,
                WifiConfiguration.KeyMgmt.strings.length)) {
            Log.e(TAG, "validateBitsets failed: invalid allowedKeyManagement bitset "
                    + config.allowedKeyManagement);
            return false;
        }
        // 2. Check |allowedProtocols|.
        if (!validateBitSet(config.allowedProtocols,
                WifiConfiguration.Protocol.strings.length)) {
            Log.e(TAG, "validateBitsets failed: invalid allowedProtocols bitset "
                    + config.allowedProtocols);
            return false;
        }
        // 3. Check |allowedAuthAlgorithms|.
        if (!validateBitSet(config.allowedAuthAlgorithms,
                WifiConfiguration.AuthAlgorithm.strings.length)) {
            Log.e(TAG, "validateBitsets failed: invalid allowedAuthAlgorithms bitset "
                    + config.allowedAuthAlgorithms);
            return false;
        }
        // 4. Check |allowedGroupCiphers|.
        if (!validateBitSet(config.allowedGroupCiphers,
                WifiConfiguration.GroupCipher.strings.length)) {
            Log.e(TAG, "validateBitsets failed: invalid allowedGroupCiphers bitset "
                    + config.allowedGroupCiphers);
            return false;
        }
        // 5. Check |allowedPairwiseCiphers|.
        if (!validateBitSet(config.allowedPairwiseCiphers,
                WifiConfiguration.PairwiseCipher.strings.length)) {
            Log.e(TAG, "validateBitsets failed: invalid allowedPairwiseCiphers bitset "
                    + config.allowedPairwiseCiphers);
            return false;
        }
        return true;
    }

    private static boolean validateKeyMgmt(BitSet keyMgmnt) {
        if (keyMgmnt.cardinality() > 1) {
            if (keyMgmnt.cardinality() > 3) {
                Log.e(TAG, "validateKeyMgmt failed: cardinality > 3");
                return false;
            }
            if (!keyMgmnt.get(WifiConfiguration.KeyMgmt.WPA_EAP)) {
                Log.e(TAG, "validateKeyMgmt failed: not WPA_EAP");
                return false;
            }
            if (!keyMgmnt.get(WifiConfiguration.KeyMgmt.IEEE8021X)
                    && !keyMgmnt.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
                Log.e(TAG, "validateKeyMgmt failed: not PSK or 8021X");
                return false;
            }
            if (keyMgmnt.cardinality() == 3
                    && !keyMgmnt.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
                Log.e(TAG, "validateKeyMgmt failed: not SUITE_B_192");
                return false;
            }
        }
        return true;
    }

    private static boolean validateIpConfiguration(IpConfiguration ipConfig) {
        if (ipConfig == null) {
            Log.e(TAG, "validateIpConfiguration failed: null IpConfiguration");
            return false;
        }
        if (ipConfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
            StaticIpConfiguration staticIpConfig = ipConfig.getStaticIpConfiguration();
            if (staticIpConfig == null) {
                Log.e(TAG, "validateIpConfiguration failed: null StaticIpConfiguration");
                return false;
            }
            if (staticIpConfig.getIpAddress() == null) {
                Log.e(TAG, "validateIpConfiguration failed: null static ip Address");
                return false;
            }
        }
        return true;
    }

    /**
     * Enums to specify if the provided config is being validated for add or update.
     */
    public static final boolean VALIDATE_FOR_ADD = true;
    public static final boolean VALIDATE_FOR_UPDATE = false;

    /**
     * Validate the configuration received from an external application.
     *
     * This method checks for the following parameters:
     * 1. {@link WifiConfiguration#SSID}
     * 2. {@link WifiConfiguration#BSSID}
     * 3. {@link WifiConfiguration#preSharedKey}
     * 4. {@link WifiConfiguration#allowedKeyManagement}
     * 5. {@link WifiConfiguration#allowedProtocols}
     * 6. {@link WifiConfiguration#allowedAuthAlgorithms}
     * 7. {@link WifiConfiguration#allowedGroupCiphers}
     * 8. {@link WifiConfiguration#allowedPairwiseCiphers}
     * 9. {@link WifiConfiguration#getIpConfiguration()}
     *
     * @param config {@link WifiConfiguration} received from an external application.
     * @param isAdd {@link #VALIDATE_FOR_ADD} to indicate a network config received for an add,
     *              {@link #VALIDATE_FOR_UPDATE} for a network config received for an update.
     *              These 2 cases need to be handled differently because the config received for an
     *              update could contain only the fields that are being changed.
     * @return true if the parameters are valid, false otherwise.
     */
    public static boolean validate(WifiConfiguration config, boolean isAdd) {
        if (!validateSsid(config.SSID, isAdd)) {
            return false;
        }
        if (!validateBssid(config.BSSID)) {
            return false;
        }
        if (!validateBitSets(config)) {
            return false;
        }
        if (!validateKeyMgmt(config.allowedKeyManagement)) {
            return false;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && config.wepKeys != null
                && !validateWepKeys(config.wepKeys, config.wepTxKeyIndex, isAdd)) {
            return false;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                && !validatePassword(config.preSharedKey, isAdd, false)) {
            return false;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
            // PMF mandatory for OWE networks
            if (!config.requirePmf) {
                Log.e(TAG, "PMF must be enabled for OWE networks");
                return false;
            }
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            // PMF mandatory for WPA3-Personal networks
            if (!config.requirePmf) {
                Log.e(TAG, "PMF must be enabled for SAE networks");
                return false;
            }
            if (!validatePassword(config.preSharedKey, isAdd, true)) {
                return false;
            }
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
            // PMF mandatory for WPA3-Enterprise networks
            if (!config.requirePmf) {
                Log.e(TAG, "PMF must be enabled for Suite-B 192-bit networks");
                return false;
            }
        }
        // b/153435438: Added to deal with badly formed WifiConfiguration from apps.
        if (config.preSharedKey != null && !config.needsPreSharedKey()) {
            Log.e(TAG, "preSharedKey set with an invalid KeyMgmt, resetting KeyMgmt to WPA_PSK");
            config.allowedKeyManagement.clear();
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        if (!validateIpConfiguration(config.getIpConfiguration())) {
            return false;
        }
        // TBD: Validate some enterprise params as well in the future here.
        return true;
    }

    private static boolean validateBssidPattern(
            Pair<MacAddress, MacAddress> bssidPatternMatcher) {
        if (bssidPatternMatcher == null) return true;
        MacAddress baseAddress = bssidPatternMatcher.first;
        MacAddress mask = bssidPatternMatcher.second;
        if (baseAddress.getAddressType() != MacAddress.TYPE_UNICAST) {
            Log.e(TAG, "validateBssidPatternMatcher failed : invalid base address: " + baseAddress);
            return false;
        }
        if (mask.equals(ALL_ZEROS_MAC_ADDRESS)
                && !baseAddress.equals(ALL_ZEROS_MAC_ADDRESS)) {
            Log.e(TAG, "validateBssidPatternMatcher failed : invalid mask/base: " + mask + "/"
                    + baseAddress);
            return false;
        }
        return true;
    }

    // TODO(b/113878056): Some of this is duplicated in {@link WifiNetworkConfigBuilder}.
    // Merge them somehow?.
    private static boolean isValidNetworkSpecifier(WifiNetworkSpecifier specifier) {
        PatternMatcher ssidPatternMatcher = specifier.ssidPatternMatcher;
        Pair<MacAddress, MacAddress> bssidPatternMatcher = specifier.bssidPatternMatcher;
        if (ssidPatternMatcher == null || bssidPatternMatcher == null) {
            return false;
        }
        if (ssidPatternMatcher.getPath() == null || bssidPatternMatcher.first == null
                || bssidPatternMatcher.second == null) {
            return false;
        }
        return true;
    }

    private static boolean isMatchNoneNetworkSpecifier(WifiNetworkSpecifier specifier) {
        PatternMatcher ssidPatternMatcher = specifier.ssidPatternMatcher;
        Pair<MacAddress, MacAddress> bssidPatternMatcher = specifier.bssidPatternMatcher;
        if (ssidPatternMatcher.getType() != PatternMatcher.PATTERN_PREFIX
                && ssidPatternMatcher.getPath().equals(MATCH_EMPTY_SSID_PATTERN_PATH)) {
            return true;
        }
        if (bssidPatternMatcher.equals(MATCH_NONE_BSSID_PATTERN)) {
            return true;
        }
        return false;
    }

    private static boolean isMatchAllNetworkSpecifier(WifiNetworkSpecifier specifier) {
        PatternMatcher ssidPatternMatcher = specifier.ssidPatternMatcher;
        Pair<MacAddress, MacAddress> bssidPatternMatcher = specifier.bssidPatternMatcher;
        if (ssidPatternMatcher.match(MATCH_EMPTY_SSID_PATTERN_PATH)
                && bssidPatternMatcher.equals(MATCH_ALL_BSSID_PATTERN)) {
            return true;
        }
        return false;
    }

    /**
     * Validate the configuration received from an external application inside
     * {@link WifiNetworkSpecifier}.
     *
     * This method checks for the following parameters:
     * 1. {@link WifiNetworkSpecifier#ssidPatternMatcher}
     * 2. {@link WifiNetworkSpecifier#bssidPatternMatcher}
     * 3. {@link WifiConfiguration#SSID}
     * 4. {@link WifiConfiguration#BSSID}
     * 5. {@link WifiConfiguration#preSharedKey}
     * 6. {@link WifiConfiguration#allowedKeyManagement}
     * 7. {@link WifiConfiguration#allowedProtocols}
     * 8. {@link WifiConfiguration#allowedAuthAlgorithms}
     * 9. {@link WifiConfiguration#allowedGroupCiphers}
     * 10. {@link WifiConfiguration#allowedPairwiseCiphers}
     * 11. {@link WifiConfiguration#getIpConfiguration()}
     *
     * @param specifier Instance of {@link WifiNetworkSpecifier}.
     * @return true if the parameters are valid, false otherwise.
     */
    public static boolean validateNetworkSpecifier(WifiNetworkSpecifier specifier) {
        if (!isValidNetworkSpecifier(specifier)) {
            Log.e(TAG, "validateNetworkSpecifier failed : invalid network specifier");
            return false;
        }
        if (isMatchNoneNetworkSpecifier(specifier)) {
            Log.e(TAG, "validateNetworkSpecifier failed : match-none specifier");
            return false;
        }
        if (isMatchAllNetworkSpecifier(specifier)) {
            Log.e(TAG, "validateNetworkSpecifier failed : match-all specifier");
            return false;
        }
        WifiConfiguration config = specifier.wifiConfiguration;
        if (specifier.ssidPatternMatcher.getType() == PatternMatcher.PATTERN_LITERAL) {
            // For literal SSID matches, the value should satisfy SSID requirements.
            // WifiConfiguration.SSID needs quotes around ASCII SSID.
            if (!validateSsid(addEnclosingQuotes(specifier.ssidPatternMatcher.getPath()), true)) {
                return false;
            }
        } else {
            if (config.hiddenSSID) {
                Log.e(TAG, "validateNetworkSpecifier failed : ssid pattern not supported "
                        + "for hidden networks");
                return false;
            }
        }
        if (Objects.equals(specifier.bssidPatternMatcher.second, MacAddress.BROADCAST_ADDRESS)) {
            // For literal BSSID matches, the value should satisfy MAC address requirements.
            if (!validateBssid(specifier.bssidPatternMatcher.first)) {
                return false;
            }
        } else {
            if (!validateBssidPattern(specifier.bssidPatternMatcher)) {
                return false;
            }
        }
        if (!validateBitSets(config)) {
            return false;
        }
        if (!validateKeyMgmt(config.allowedKeyManagement)) {
            return false;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                && !validatePassword(config.preSharedKey, true, false)) {
            return false;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
            // PMF mandatory for OWE networks
            if (!config.requirePmf) {
                return false;
            }
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            // PMF mandatory for WPA3-Personal networks
            if (!config.requirePmf) {
                return false;
            }
            if (!validatePassword(config.preSharedKey, true, true)) {
                return false;
            }
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
            // PMF mandatory for WPA3-Enterprise networks
            if (!config.requirePmf) {
                return false;
            }
        }
        // TBD: Validate some enterprise params as well in the future here.
        return true;
    }

    /**
     * Check if the provided two networks are the same.
     * Note: This does not check if network selection BSSID's are the same.
     *
     * @param config  Configuration corresponding to a network.
     * @param config1 Configuration corresponding to another network.
     *
     * @return true if |config| and |config1| are the same network.
     *         false otherwise.
     */
    public static boolean isSameNetwork(WifiConfiguration config, WifiConfiguration config1) {
        if (config == null && config1 == null) {
            return true;
        }
        if (config == null || config1 == null) {
            return false;
        }
        if (config.networkId != config1.networkId) {
            return false;
        }
        if (!Objects.equals(config.SSID, config1.SSID)) {
            return false;
        }
        if (WifiConfigurationUtil.hasCredentialChanged(config, config1)) {
            return false;
        }
        return true;
    }

    /**
     * Create a PnoNetwork object from the provided WifiConfiguration.
     *
     * @param config      Configuration corresponding to the network.
     * @return PnoNetwork object corresponding to the network.
     */
    public static WifiScanner.PnoSettings.PnoNetwork createPnoNetwork(
            WifiConfiguration config) {
        WifiScanner.PnoSettings.PnoNetwork pnoNetwork =
                new WifiScanner.PnoSettings.PnoNetwork(config.SSID);
        if (config.hiddenSSID) {
            pnoNetwork.flags |= WifiScanner.PnoSettings.PnoNetwork.FLAG_DIRECTED_SCAN;
        }
        pnoNetwork.flags |= WifiScanner.PnoSettings.PnoNetwork.FLAG_A_BAND;
        pnoNetwork.flags |= WifiScanner.PnoSettings.PnoNetwork.FLAG_G_BAND;
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            pnoNetwork.authBitField |= WifiScanner.PnoSettings.PnoNetwork.AUTH_CODE_PSK;
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            pnoNetwork.authBitField |= WifiScanner.PnoSettings.PnoNetwork.AUTH_CODE_EAPOL;
        } else {
            pnoNetwork.authBitField |= WifiScanner.PnoSettings.PnoNetwork.AUTH_CODE_OPEN;
        }
        return pnoNetwork;
    }


    /**
     * General WifiConfiguration list sorting algorithm:
     * 1, Place the fully enabled networks first.
     * 2. Next place all the temporarily disabled networks.
     * 3. Place the permanently disabled networks last (Permanently disabled networks are removed
     * before WifiConfigManager uses this comparator today!).
     *
     * Among the networks with the same status, sort them in the order determined by the return of
     * {@link #compareNetworksWithSameStatus(WifiConfiguration, WifiConfiguration)} method
     * implementation.
     */
    public abstract static class WifiConfigurationComparator implements
            Comparator<WifiConfiguration> {
        private static final int ENABLED_NETWORK_SCORE = 3;
        private static final int TEMPORARY_DISABLED_NETWORK_SCORE = 2;
        private static final int PERMANENTLY_DISABLED_NETWORK_SCORE = 1;

        @Override
        public int compare(WifiConfiguration a, WifiConfiguration b) {
            int configAScore = getNetworkStatusScore(a);
            int configBScore = getNetworkStatusScore(b);
            if (configAScore == configBScore) {
                return compareNetworksWithSameStatus(a, b);
            } else {
                return Integer.compare(configBScore, configAScore);
            }
        }

        // This needs to be implemented by the connected/disconnected PNO list comparator.
        abstract int compareNetworksWithSameStatus(WifiConfiguration a, WifiConfiguration b);

        /**
         * Returns an integer representing a score for each configuration. The scores are assigned
         * based on the status of the configuration. The scores are assigned according to the order:
         * Fully enabled network > Temporarily disabled network > Permanently disabled network.
         */
        private int getNetworkStatusScore(WifiConfiguration config) {
            if (config.getNetworkSelectionStatus().isNetworkEnabled()) {
                return ENABLED_NETWORK_SCORE;
            } else if (config.getNetworkSelectionStatus().isNetworkTemporaryDisabled()) {
                return TEMPORARY_DISABLED_NETWORK_SCORE;
            } else {
                return PERMANENTLY_DISABLED_NETWORK_SCORE;
            }
        }
    }
}
