package com.voipgrid.vialer.util;

import com.voipgrid.vialer.R;
import com.voipgrid.vialer.VialerApplication;

import androidx.annotation.NonNull;

/**
 * Utils class for PhoneNumber
 */
public class PhoneNumberUtils {

    /**
     * Format phonenumber to allowed format for i.e. SIP uri's
     * @param phoneNumber number to format
     * @return String formatted phoneNumber
     */
    public static String format(String phoneNumber) {
        return phoneNumber.replaceAll("[^*+0-9]","");
    }

    /**
     * Format mobile number for systemuser api. This is a best effort method. NOTE: The return
     * value does not have to be a valid phone number. Check with isValidMobileNumber.
     * @param mobileNumber The mobile number to format.
     * @return String formatted mobile number.
     */
    public static String formatMobileNumber(String mobileNumber) {
        mobileNumber = format(mobileNumber);

        if (mobileNumber.startsWith("00")) {
            mobileNumber = mobileNumber.replaceFirst("[0]{2}", "+");
        }

        return mobileNumber;
    }

    /**
     * Validate the mobile number for systemuser api requests.
     * @param mobileNumber The mobile number to check.
     * @return boolean If the number is valid.
     */
    public static boolean isValidMobileNumber(String mobileNumber) {
        if (!mobileNumber.startsWith("+")) {
            return false;
        }

        return mobileNumber.equals(formatMobileNumber(mobileNumber));

    }

    public static boolean isAnonymousNumber(String number) {
        return number != null && number.endsWith("x");
    }

    /**
     * If the number appears to be anonymous, a mask will be applied, otherwise returns the original number.
     *
     * @param number
     * @return
     */
    public static @NonNull String maskAnonymousNumber(String number) {
        return isAnonymousNumber(number) ? VialerApplication.get().getString(R.string.supressed_number) : number;
    }
}
