/*
 * Copyright (C) 2017 The LineageOS Project
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

package com.android.internal.telephony;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemProperties;
import android.service.carrier.CarrierIdentifier;
import android.util.Log;

import static com.android.internal.telephony.RILConstants.*;

import java.util.List;

/**
 * Custom Qualcomm RIL for Xiaomi Mi Max/Prime
 * *************************************************************************
 * Remap Android 7.1 RILConstants to match Xiaomi's Android 7.0 RIL
 * Note: Build with matching cm-14.0-caf RIL
 * Fixes import/export contacts from/to SIM card
 * -------------------------------------------------------------------------
 * Remap unsupported DC-HSPAP RAT=20 to HSPAP RAT=15
 * Fixes missing H+ status bar icon for RAT=20 use cases
 *
 * {@hide}
 */
public class MiMaxRIL extends RIL implements CommandsInterface {

    private static final String TAG = "MiMaxRIL";
    private static final boolean DEBUG =
                "1".equals(SystemProperties.get("ro.telephony.MiMaxRIL.debug", "0"));

    public MiMaxRIL(Context context, int preferredNetworkType, int cdmaSubscription) {
        this(context, preferredNetworkType, cdmaSubscription, null);
    }

    public MiMaxRIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context, preferredNetworkType, cdmaSubscription, instanceId);
    }

    @Override
    protected void
    send(RILRequest rr) {
        int origRequest = rr.mRequest;
        switch(origRequest) {
            case RIL_REQUEST_SIM_GET_ATR:
            case RIL_REQUEST_CAF_SIM_OPEN_CHANNEL_WITH_P2:
            case RIL_REQUEST_GET_ADN_RECORD:
            case RIL_REQUEST_UPDATE_ADN_RECORD:
                rr.mRequest -= 2; // adjust for our RIL
                break;
        }

        if (origRequest != rr.mRequest) {
            int dataPosition = rr.mParcel.dataPosition();
            rr.mParcel.setDataPosition(0);
            rr.mParcel.writeInt(rr.mRequest);
            rr.mParcel.setDataPosition(dataPosition);

            if (DEBUG) Log.d(TAG, "send: Remapped request before sending from " +
                       requestToString(origRequest) + "[" + origRequest + "] to " +
                       requestToString(rr.mRequest) + "[" + rr.mRequest + "]");
        }

        super.send(rr);
    }

    @Override
    protected RILRequest
    processSolicited (Parcel p, int type) {
        int serial, error;
        boolean found = false;
        int dataPosition = p.dataPosition(); // Save off position within the parcel
        serial = p.readInt();
        error = p.readInt();
        int origRequest = -1;
        RILRequest rr = null;
        RILRequest tr = null;

        // Pre-process the reply before popping it
        synchronized (mRequestList) {
            tr = mRequestList.get(serial);
            origRequest = tr.mRequest;
            if (tr != null && tr.mSerial == serial) {
                if (error == 0 || p.dataAvail() > 0) {
                    try {
                        switch (tr.mRequest) {
                            // Get those we're interested in
                            case RIL_REQUEST_DATA_REGISTRATION_STATE:
                                rr = tr;
                                break;
                            case RIL_REQUEST_SET_ALLOWED_CARRIERS:
                            case RIL_REQUEST_GET_ALLOWED_CARRIERS:
                            case RIL_REQUEST_SIM_GET_ATR:
                            case RIL_REQUEST_CAF_SIM_OPEN_CHANNEL_WITH_P2:
                                tr.mRequest += 2; // revert adjust for our RIL
                                break;
                        }
                    } catch (Throwable thr) {
                        // Exceptions here usually mean invalid RIL responses
                        if (tr.mResult != null) {
                            AsyncResult.forMessage(tr.mResult, null, thr);
                            tr.mResult.sendToTarget();
                        }
                        return tr;
                    }
                }
            }
        }

        if (origRequest != -1 && origRequest != tr.mRequest) {
            // revert back request remapping in mRequestList before
            // forwarding it to the super class for response processing
            mRequestList.remove(serial);
            mRequestList.append(serial, tr);

            if (DEBUG) Log.d(TAG, "processSolicited: Reverting remapped request response before processing from " +
                       requestToString(origRequest) + "[" + origRequest + "] to " +
                       requestToString(tr.mRequest) + "[" + tr.mRequest + "]");
        }

        if (rr == null) {
            // Nothing we care about, go up
            p.setDataPosition(dataPosition);

            // Forward responses that we're not overriding to the super class
            return super.processSolicited(p, type);
        }
        rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            return rr;
        }

        Object ret = null;
        if (error == 0 || p.dataAvail() > 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_DATA_REGISTRATION_STATE:
                    ret = responseDataRegistrationState(p);
                    break;
                default:
                    throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            }
            //break;
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                               + " " + retToString(rr.mRequest, ret));
        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }

        return rr;
    }

    private Object
    responseDataRegistrationState(Parcel p) {
        String response[] = (String[]) responseStrings(p); // All data from parcel get popped
        
        if (DEBUG) Log.d(TAG, "RIL reported RAT=" + response[3]);

        /* Our RIL reports a value of 20 for DC-HSPAP.
           However, this isn't supported in AOSP. So, map it to HSPAP instead */
        if (response.length > 4 && response[0].equals("1") && response[3].equals("20")) {
            response[3] = "15";
            if (DEBUG) Log.d(TAG, "RIL reported RAT=20 -> Reporting to framework RAT=" + response[3]);
        }

        return response;
    }

    @Override
    protected void
    processUnsolicited(Parcel p, int type) {
        int dataPosition = p.dataPosition();
        int origResponse = p.readInt();
        int newResponse = origResponse;
        try {
            switch(origResponse) {
                case RIL_UNSOL_PCO_DATA:
                case RIL_UNSOL_RESPONSE_ADN_INIT_DONE:
                    newResponse += 1; // adjust Uncol response for processing
                    break;
            }
        } catch (Throwable tr) {
            Log.e(TAG, "Exception processing unsol response: " + origResponse +
                "Exception:" + tr.toString());
            return;
        }

        if (newResponse != origResponse) {
            p.setDataPosition(dataPosition);
            p.writeInt(newResponse);

            if (DEBUG) Log.d(TAG, "processUnsolicited: Remapped Uncol response from " +
                       responseToString(origResponse) + "[" + origResponse + "] to " +
                       responseToString(newResponse) + "[" +  newResponse + "]" +
                       " before processing");
        }

        p.setDataPosition(dataPosition);
        super.processUnsolicited(p, type);
    }

    // Our RIL doesn't support these...
    @Override
    public void setAllowedCarriers(List<CarrierIdentifier> carriers, Message response) {
        if (DEBUG) Log.d(TAG, "setAllowedCarriers: not supported");
        if (response != null) {
            CommandException ex = new CommandException(CommandException.Error.REQUEST_NOT_SUPPORTED);
            AsyncResult.forMessage(response, null, ex);
            response.sendToTarget();
        }
    }

    @Override
    public void getAllowedCarriers(Message response) {
        if (DEBUG) Log.d(TAG, "getAllowedCarriers: not supported");
        if (response != null) {
            CommandException ex = new CommandException(CommandException.Error.REQUEST_NOT_SUPPORTED);
            AsyncResult.forMessage(response, null, ex);
            response.sendToTarget();
        }
    }
}
