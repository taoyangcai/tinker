/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.loader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.tencent.tinker.loader.app.TinkerApplication;
import com.tencent.tinker.loader.hotplug.ComponentHotplug;
import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareIntentUtil;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.SharePatchInfo;
import com.tencent.tinker.loader.shareutil.ShareSecurityCheck;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.io.File;

/**
 * Created by zhangshaowen on 16/3/10.
 * Warning, it is special for loader classes, they can't change through tinker patch.
 * thus, it's reference class must put in the tinkerPatch.dex.loader{} and the android main dex pattern through gradle
 */
public class TinkerLoader extends AbstractTinkerLoader {
    private static final String TAG = "Tinker.TinkerLoader";

    /**
     * the patch info file
     */
    private SharePatchInfo patchInfo;

    /**
     * only main process can handle patch version change or incomplete
     */
    @Override
    public Intent tryLoad(TinkerApplication app) {
        Intent resultIntent = new Intent();

        long begin = SystemClock.elapsedRealtime();
        tryLoadPatchFilesInternal(app, resultIntent);
        long cost = SystemClock.elapsedRealtime() - begin;
        ShareIntentUtil.setIntentPatchCostTime(resultIntent, cost);
        return resultIntent;
    }

    private void tryLoadPatchFilesInternal(TinkerApplication app, Intent resultIntent) {
        final int tinkerFlag = app.getTinkerFlags();

        if (!ShareTinkerInternals.isTinkerEnabled(tinkerFlag)) {
            Log.w(TAG, "tryLoadPatchFiles: tinker is disable, just return");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_DISABLE);
            return;
        }
        if (ShareTinkerInternals.isInPatchProcess(app)) {
            Log.w(TAG, "tryLoadPatchFiles: we don't load patch with :patch process itself, just return");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_DISABLE);
            return;

        }
        //tinker
        File patchDirectoryFile = SharePatchFileUtil.getPatchDirectory(app);
        if (patchDirectoryFile == null) {
            Log.w(TAG, "tryLoadPatchFiles:getPatchDirectory == null");
            //treat as not exist
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_DIRECTORY_NOT_EXIST);
            return;
        }
        String patchDirectoryPath = patchDirectoryFile.getAbsolutePath();

        //check patch directory whether exist
        if (!patchDirectoryFile.exists()) {
            Log.w(TAG, "tryLoadPatchFiles:patch dir not exist:" + patchDirectoryPath);
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_DIRECTORY_NOT_EXIST);
            return;
        }

        //tinker/patch.info
        File patchInfoFile = SharePatchFileUtil.getPatchInfoFile(patchDirectoryPath);

        //check patch info file whether exist
        if (!patchInfoFile.exists()) {
            Log.w(TAG, "tryLoadPatchFiles:patch info not exist:" + patchInfoFile.getAbsolutePath());
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_NOT_EXIST);
            return;
        }
        //old = 641e634c5b8f1649c75caf73794acbdf
        //new = 2c150d8560334966952678930ba67fa8
        File patchInfoLockFile = SharePatchFileUtil.getPatchInfoLockFile(patchDirectoryPath);

        patchInfo = SharePatchInfo.readAndCheckPropertyWithLock(patchInfoFile, patchInfoLockFile);
        if (patchInfo == null) {
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_CORRUPTED);
            return;
        }

        String oldVersion = patchInfo.oldVersion;
        String newVersion = patchInfo.newVersion;
        String oatDex = patchInfo.oatDir;

        if (oldVersion == null || newVersion == null || oatDex == null) {
            //it is nice to clean patch
            Log.w(TAG, "tryLoadPatchFiles:onPatchInfoCorrupted");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_CORRUPTED);
            return;
        }

        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_OLD_VERSION, oldVersion);
        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_NEW_VERSION, newVersion);

        boolean mainProcess = ShareTinkerInternals.isInMainProcess(app);
        boolean versionChanged = !(oldVersion.equals(newVersion));
        boolean oatModeChanged = oatDex.equals(ShareConstants.CHANING_DEX_OPTIMIZE_PATH) && mainProcess;
        oatDex = ShareTinkerInternals.getCurrentOatMode(app, oatDex);
        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_OAT_DIR, oatDex);

        String version = oldVersion;

        if (versionChanged && mainProcess) {
            version = newVersion;
        }
        if (ShareTinkerInternals.isNullOrNil(version)) {
            Log.w(TAG, "tryLoadPatchFiles:version is blank, wait main process to restart");
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_INFO_BLANK);
            return;
        }

        //patch-641e634c
        String patchName = SharePatchFileUtil.getPatchVersionDirectory(version);
        if (patchName == null) {
            Log.w(TAG, "tryLoadPatchFiles:patchName is null");
            //we may delete patch info file
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_VERSION_DIRECTORY_NOT_EXIST);
            return;
        }
        //tinker/patch.info/patch-641e634c
        String patchVersionDirectory = patchDirectoryPath + "/" + patchName;

        File patchVersionDirectoryFile = new File(patchVersionDirectory);

        if (!patchVersionDirectoryFile.exists()) {
            Log.w(TAG, "tryLoadPatchFiles:onPatchVersionDirectoryNotFound");
            //we may delete patch info file
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_VERSION_DIRECTORY_NOT_EXIST);
            return;
        }

        //tinker/patch.info/patch-641e634c/patch-641e634c.apk
        final String patchVersionFileRelPath = SharePatchFileUtil.getPatchVersionFile(version);
        File patchVersionFile = (patchVersionFileRelPath != null ? new File(patchVersionDirectoryFile.getAbsolutePath(), patchVersionFileRelPath) : null);

        if (!SharePatchFileUtil.isLegalFile(patchVersionFile)) {
            Log.w(TAG, "tryLoadPatchFiles:onPatchVersionFileNotFound");
            //we may delete patch info file
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_VERSION_FILE_NOT_EXIST);
            return;
        }

        ShareSecurityCheck securityCheck = new ShareSecurityCheck(app);

        int returnCode = ShareTinkerInternals.checkTinkerPackage(app, tinkerFlag, patchVersionFile, securityCheck);
        if (returnCode != ShareConstants.ERROR_PACKAGE_CHECK_OK) {
            Log.w(TAG, "tryLoadPatchFiles:checkTinkerPackage");
            resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_PACKAGE_PATCH_CHECK, returnCode);
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_PACKAGE_CHECK_FAIL);
            return;
        }

        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_PACKAGE_CONFIG, securityCheck.getPackagePropertiesIfPresent());

        final boolean isEnabledForDex = ShareTinkerInternals.isTinkerEnabledForDex(tinkerFlag);

        if (isEnabledForDex) {
            //tinker/patch.info/patch-641e634c/dex
            boolean dexCheck = TinkerDexLoader.checkComplete(patchVersionDirectory, securityCheck, oatDex, resultIntent);
            if (!dexCheck) {
                //file not found, do not load patch
                Log.w(TAG, "tryLoadPatchFiles:dex check fail");
                return;
            }
        }

        final boolean isEnabledForNativeLib = ShareTinkerInternals.isTinkerEnabledForNativeLib(tinkerFlag);

        if (isEnabledForNativeLib) {
            //tinker/patch.info/patch-641e634c/lib
            boolean libCheck = TinkerSoLoader.checkComplete(patchVersionDirectory, securityCheck, resultIntent);
            if (!libCheck) {
                //file not found, do not load patch
                Log.w(TAG, "tryLoadPatchFiles:native lib check fail");
                return;
            }
        }

        //check resource
        final boolean isEnabledForResource = ShareTinkerInternals.isTinkerEnabledForResource(tinkerFlag);
        Log.w(TAG, "tryLoadPatchFiles:isEnabledForResource:" + isEnabledForResource);
        if (isEnabledForResource) {
            boolean resourceCheck = TinkerResourceLoader.checkComplete(app, patchVersionDirectory, securityCheck, resultIntent);
            if (!resourceCheck) {
                //file not found, do not load patch
                Log.w(TAG, "tryLoadPatchFiles:resource check fail");
                return;
            }
        }
        //only work for art platform oat，because of interpret, refuse 4.4 art oat
        //android o use quicken default, we don't need to use interpret mode
        boolean isSystemOTA = ShareTinkerInternals.isVmArt()
            && ShareTinkerInternals.isSystemOTA(patchInfo.fingerPrint)
            && Build.VERSION.SDK_INT >= 21 && !ShareTinkerInternals.isAfterAndroidO();

        resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_SYSTEM_OTA, isSystemOTA);

        //we should first try rewrite patch info file, if there is a error, we can't load jar
        if ((mainProcess && versionChanged)
             || oatModeChanged) {
            patchInfo.oldVersion = version;
            patchInfo.oatDir = oatDex;

            //update old version to new
            if (!SharePatchInfo.rewritePatchInfoFileWithLock(patchInfoFile, patchInfo, patchInfoLockFile)) {
                ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_REWRITE_PATCH_INFO_FAIL);
                Log.w(TAG, "tryLoadPatchFiles:onReWritePatchInfoCorrupted");
                return;
            }
            if (oatModeChanged) {
                // delete interpret odex
                // for android o, directory change. Fortunately, we don't need to support android o interpret mode any more
                Log.i(TAG, "tryLoadPatchFiles:oatModeChanged, try to delete interpret optimize files");
                SharePatchFileUtil.deleteDir(patchVersionDirectory + "/" + ShareConstants.INTERPRET_DEX_OPTIMIZE_PATH);
            }
        }
        if (!checkSafeModeCount(app)) {
            resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_EXCEPTION, new TinkerRuntimeException("checkSafeModeCount fail"));
            ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_UNCAUGHT_EXCEPTION);
            Log.w(TAG, "tryLoadPatchFiles:checkSafeModeCount fail");
            return;
        }

        //now we can load patch jar
        if (isEnabledForDex) {
            boolean loadTinkerJars = TinkerDexLoader.loadTinkerJars(app, patchVersionDirectory, oatDex, resultIntent, isSystemOTA);

            if (isSystemOTA) {
                // update fingerprint after load success
                patchInfo.fingerPrint = Build.FINGERPRINT;
                patchInfo.oatDir = loadTinkerJars ? ShareConstants.INTERPRET_DEX_OPTIMIZE_PATH : ShareConstants.DEFAULT_DEX_OPTIMIZE_PATH;
                // reset to false
                oatModeChanged = false;

                if (!SharePatchInfo.rewritePatchInfoFileWithLock(patchInfoFile, patchInfo, patchInfoLockFile)) {
                    ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_PATCH_REWRITE_PATCH_INFO_FAIL);
                    Log.w(TAG, "tryLoadPatchFiles:onReWritePatchInfoCorrupted");
                    return;
                }
                // update oat dir
                resultIntent.putExtra(ShareIntentUtil.INTENT_PATCH_OAT_DIR, patchInfo.oatDir);
            }
            if (!loadTinkerJars) {
                Log.w(TAG, "tryLoadPatchFiles:onPatchLoadDexesFail");
                return;
            }
        }

        //now we can load patch resource
        if (isEnabledForResource) {
            boolean loadTinkerResources = TinkerResourceLoader.loadTinkerResources(app, patchVersionDirectory, resultIntent);
            if (!loadTinkerResources) {
                Log.w(TAG, "tryLoadPatchFiles:onPatchLoadResourcesFail");
                return;
            }
        }

        // Init component hotplug support.
        if (isEnabledForDex && isEnabledForResource) {
            ComponentHotplug.install(app, securityCheck);
        }

        // kill all other process if oat mode change
        if (oatModeChanged) {
            ShareTinkerInternals.killAllOtherProcess(app);
            Log.i(TAG, "tryLoadPatchFiles:oatModeChanged, try to kill all other process");
        }
        //all is ok!
        ShareIntentUtil.setIntentReturnCode(resultIntent, ShareConstants.ERROR_LOAD_OK);
        Log.i(TAG, "tryLoadPatchFiles: load end, ok!");
        return;
    }

    private boolean checkSafeModeCount(TinkerApplication application) {
        String processName = ShareTinkerInternals.getProcessName(application);
        String preferName = ShareConstants.TINKER_OWN_PREFERENCE_CONFIG + processName;
        //each process have its own SharedPreferences file
        SharedPreferences sp = application.getSharedPreferences(preferName, Context.MODE_PRIVATE);
        int count = sp.getInt(ShareConstants.TINKER_SAFE_MODE_COUNT, 0) + 1;
        Log.w(TAG, "tinker safe mode preferName:" + preferName + " count:" + count);
        if (count >= ShareConstants.TINKER_SAFE_MODE_MAX_COUNT) {
            sp.edit().putInt(ShareConstants.TINKER_SAFE_MODE_COUNT, 0).commit();
            return false;
        }
        application.setUseSafeMode(true);
        sp.edit().putInt(ShareConstants.TINKER_SAFE_MODE_COUNT, count).commit();
        return true;
    }
}
