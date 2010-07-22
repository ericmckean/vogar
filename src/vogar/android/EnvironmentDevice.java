/*
 * Copyright (C) 2010 The Android Open Source Project
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

package vogar.android;

import java.io.File;
import vogar.Action;
import vogar.Environment;

public final class EnvironmentDevice extends Environment {
    final AndroidSdk androidSdk;
    final File runnerDir;
    final File vogarTemp;
    final File dalvikCache;
    final int firstMonitorPort;
    final int numRunners;

    public EnvironmentDevice(boolean cleanBefore, boolean cleanAfter, Integer debugPort,
            int firstMonitorPort, int numRunners, File localTemp, File runnerDir,
            AndroidSdk androidSdk, int monitorTimeoutSeconds) {
        super(cleanBefore, cleanAfter, debugPort, localTemp, monitorTimeoutSeconds);
        this.androidSdk = androidSdk;
        this.runnerDir = runnerDir;
        this.vogarTemp = new File(runnerDir, "tmp");
        this.dalvikCache = new File(runnerDir.getParentFile(), "dalvik-cache");
        this.firstMonitorPort = firstMonitorPort;
        this.numRunners = numRunners;
    }

    public AndroidSdk getAndroidSdk() {
        return androidSdk;
    }

    public File getRunnerDir() {
        return runnerDir;
    }

    /**
     * Returns an environment variable assignment to configure where the VM will
     * store its dexopt files. This must be set on production devices and is
     * optional for development devices.
     */
    public String getAndroidData() {
        // The VM wants the parent directory of a directory named "dalvik-cache"
        return "ANDROID_DATA=" + dalvikCache.getParentFile();
    }

    @Override public void prepare() {
        androidSdk.waitForDevice();
        // Even if runner dir is /vogar/run, the grandparent will be / (and non-null)
        androidSdk.waitForNonEmptyDirectory(runnerDir.getParentFile().getParentFile(), 5 * 60);
        androidSdk.remount();
        if (cleanBefore()) {
            androidSdk.rm(runnerDir);
        }
        androidSdk.mkdirs(runnerDir);
        androidSdk.mkdir(vogarTemp);
        androidSdk.mkdir(dalvikCache);
        for (int i = 0; i < numRunners; i++) {
            androidSdk.forwardTcp(firstMonitorPort + i, firstMonitorPort + i);
        }
        if (getDebugPort() != null) {
            androidSdk.forwardTcp(getDebugPort(), getDebugPort());
        }
    }

    @Override public void prepareUserDir(Action action) {
        File actionClassesDirOnDevice = actionClassesDirOnDevice(action);
        androidSdk.mkdir(actionClassesDirOnDevice);
        File resourcesDirectory = action.getResourcesDirectory();
        if (resourcesDirectory != null) {
            androidSdk.push(resourcesDirectory, actionClassesDirOnDevice);
        }
        action.setUserDir(actionClassesDirOnDevice);
    }

    private File actionClassesDirOnDevice(Action action) {
        return new File(runnerDir, action.getName());
    }

    @Override public void cleanup(Action action) {
        super.cleanup(action);
        if (cleanAfter()) {
            androidSdk.rm(actionClassesDirOnDevice(action));
        }
    }

    @Override public void shutdown() {
        super.shutdown();
        if (cleanAfter()) {
            androidSdk.rm(runnerDir);
        }
    }
}
